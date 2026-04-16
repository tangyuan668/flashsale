package com.flashsale.order.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.order.dto.SeckillRequest;
import com.flashsale.order.entity.OrderInfo;
import com.flashsale.order.entity.OrderItem;
import com.flashsale.order.mapper.OrderInfoMapper;
import com.flashsale.order.mapper.OrderItemMapper;
import com.flashsale.order.feign.ActivityFeignClient;
import com.flashsale.order.feign.ActivityDto;
import com.flashsale.order.feign.ActivityDto.ActivityItemDto;
import com.flashsale.order.vo.OrderDetailResponse;
import com.flashsale.order.vo.OrderItemVO;
import com.flashsale.order.vo.OrderStatusResponse;
import com.flashsale.order.vo.SeckillResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务
 */
@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ActivityFeignClient activityFeignClient;

    @Autowired
    private LocalMessageService localMessageService;

    private static final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 秒杀下单（异步处理）
     */
    public SeckillResponse seckill(Long userId, SeckillRequest request) {
        // 生成订单号
        String orderNo = generateOrderNo();

        // 构造订单创建消息
        OrderCreateMessage message = new OrderCreateMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setActivityId(request.getActivityId());
        message.setItemId(request.getItemId());
        message.setQuantity(request.getQuantity());

        // 保存到本地消息表
        localMessageService.saveMessage(orderNo, MqConstant.SECKILL_ORDER_TOPIC, message);

        // 尝试发送 MQ（发送失败由定时任务重试）
        try {
            rocketMQTemplate.syncSend(MqConstant.SECKILL_ORDER_TOPIC, message);
            log.info("发送秒杀订单消息成功: orderNo={}, userId={}", orderNo, userId);
            // 立即更新本地消息表状态为已发送
            localMessageService.markMessageAsSent(orderNo);
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
            // 消息已保存到本地消息表，定时任务会重试，用户可以正常获取处理中状态
        }

        // 返回处理中状态
        return SeckillResponse.processing(orderNo);
    }

    /**
     * 处理订单创建（MQ消费者）
     * 注意：此方法已废弃，现在使用方案B（双MQ），库存扣减由InventoryConsumer处理
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void processOrderCreate(OrderCreateMessage message) {
        log.info("processOrderCreate已废弃，订单由processStockDeductResult创建: orderNo={}",
                message.getOrderNo());
        // 此方法保留兼容性，实际订单创建由 processStockDeductResult 处理
    }

    /**
     * 处理库存扣减结果（MQ消费者）
     */
    @Transactional(rollbackFor = Exception.class)
    public void processStockDeductResult(StockDeductMessage message) {
        log.info("处理库存扣减结果: orderNo={}, success={}", message.getOrderNo(), message.getSuccess());

        if (Boolean.TRUE.equals(message.getSuccess())) {
            // 库存扣减成功，创建订单
            createOrderAfterStockDeducted(message);
        } else {
            // 库存扣减失败，在Redis中记录失败状态
            String failKey = "order:fail:" + message.getOrderNo();
            redisTemplate.opsForValue().set(failKey, "库存不足或已售罄", 24, TimeUnit.HOURS);
            // 清理扣减中标记
            String deductingKey = "deducting:" + message.getUserId() + ":stock:" + message.getActivityId() + ":" + message.getItemId();
            redisTemplate.delete(deductingKey);
            log.info("库存扣减失败，已记录: orderNo={}", message.getOrderNo());
        }
    }

    /**
     * 库存扣减成功后创建订单
     */
    private void createOrderAfterStockDeducted(StockDeductMessage message) {
        // 1. 从活动服务获取商品信息
        ActivityDto activityDto = null;
        try {
            activityDto = activityFeignClient.getActivityDetail(message.getActivityId()).getData();
        } catch (Exception e) {
            log.error("获取活动信息失败: activityId={}", message.getActivityId(), e);
        }

        if (activityDto == null || activityDto.getItems() == null) {
            log.error("活动信息不存在: activityId={}", message.getActivityId());
            String failKey = "order:fail:" + message.getOrderNo();
            redisTemplate.opsForValue().set(failKey, "活动信息不存在", 24, TimeUnit.HOURS);
            // 清理扣减中标记
            String deductingKey = "deducting:" + message.getUserId() + ":stock:" + message.getActivityId() + ":" + message.getItemId();
            redisTemplate.delete(deductingKey);
            return;
        }

        // 2. 查找对应的商品
        ActivityItemDto targetItem = activityDto.getItems().stream()
                .filter(item -> item.getItemId().equals(message.getItemId()))
                .findFirst()
                .orElse(null);

        if (targetItem == null) {
            log.error("商品不存在: itemId={}", message.getItemId());
            String failKey = "order:fail:" + message.getOrderNo();
            redisTemplate.opsForValue().set(failKey, "商品信息不存在", 24, TimeUnit.HOURS);
            // 清理扣减中标记
            String deductingKey = "deducting:" + message.getUserId() + ":stock:" + message.getActivityId() + ":" + message.getItemId();
            redisTemplate.delete(deductingKey);
            return;
        }

        // 3. 创建订单主表
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderNo(message.getOrderNo());
        orderInfo.setUserId(message.getUserId());
        orderInfo.setActivityId(message.getActivityId());
        orderInfo.setTotalAmount(targetItem.getSeckillPrice()
                .multiply(java.math.BigDecimal.valueOf(message.getQuantity())));
        orderInfo.setStatus(0); // 待支付
        orderInfo.setCreateTime(LocalDateTime.now());

        orderInfoMapper.insert(orderInfo);

        // 4. 创建订单明细
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderInfo.getId());
        orderItem.setOrderNo(message.getOrderNo());
        orderItem.setItemId(message.getItemId());
        orderItem.setItemName(targetItem.getItemName());
        orderItem.setItemImage(targetItem.getItemImage());
        orderItem.setPrice(targetItem.getSeckillPrice());
        orderItem.setQuantity(message.getQuantity());
        orderItem.setTotalAmount(targetItem.getSeckillPrice()
                .multiply(java.math.BigDecimal.valueOf(message.getQuantity())));
        orderItem.setCreateTime(LocalDateTime.now());

        orderItemMapper.insert(orderItem);

        // 5. 设置订单超时取消（15分钟）
        String orderKey = "order:timeout:" + message.getOrderNo();
        redisTemplate.opsForValue().set(orderKey, orderInfo.getId(), 15, TimeUnit.MINUTES);

        // 6. 标记用户已购买（防重复购买）
        String purchaseKey = "user:purchase:" + message.getUserId() + ":stock:" + message.getActivityId() + ":" + message.getItemId();
        redisTemplate.opsForValue().set(purchaseKey, message.getOrderNo(), 24, TimeUnit.HOURS);
        // 清理扣减中标记
        String deductingKey = "deducting:" + message.getUserId() + ":stock:" + message.getActivityId() + ":" + message.getItemId();
        redisTemplate.delete(deductingKey);

        log.info("订单创建成功: orderNo={}, totalAmount={}", message.getOrderNo(), orderInfo.getTotalAmount());
    }

    /**
     * 获取订单详情
     */
    public OrderDetailResponse getOrderDetail(String orderNo) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 查询订单明细
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
        );

        List<OrderItemVO> itemVOs = items.stream().map(item -> {
            OrderItemVO vo = new OrderItemVO();
            vo.setId(item.getId());
            vo.setItemId(item.getItemId());
            vo.setItemName(item.getItemName());
            vo.setItemImage(item.getItemImage());
            vo.setPrice(item.getPrice());
            vo.setQuantity(item.getQuantity());
            vo.setTotalAmount(item.getTotalAmount());
            return vo;
        }).toList();

        OrderDetailResponse response = new OrderDetailResponse();
        response.setId(orderInfo.getId());
        response.setOrderNo(orderInfo.getOrderNo());
        response.setUserId(orderInfo.getUserId());
        response.setActivityId(orderInfo.getActivityId());
        response.setTotalAmount(orderInfo.getTotalAmount());
        response.setStatus(orderInfo.getStatus());
        response.setStatusDesc(getStatusDesc(orderInfo.getStatus()));
        response.setPayTime(orderInfo.getPayTime());
        response.setCancelTime(orderInfo.getCancelTime());
        response.setCreateTime(orderInfo.getCreateTime());
        response.setItems(itemVOs);

        return response;
    }

    /**
     * 获取订单状态（用于轮询查询）
     * @param orderNo 订单号
     * @return 订单状态
     */
    public OrderStatusResponse getOrderStatus(String orderNo) {
        // 1. 先检查是否在失败记录中
        String failKey = "order:fail:" + orderNo;
        Object failReason = redisTemplate.opsForValue().get(failKey);
        if (failReason != null) {
            return OrderStatusResponse.failed(orderNo, failReason.toString());
        }

        // 2. 查询订单是否存在
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            // 订单还未创建，返回处理中
            return OrderStatusResponse.processing(orderNo);
        }

        // 3. 返回订单状态
        return OrderStatusResponse.success(orderNo, orderInfo.getId(), orderInfo.getTotalAmount());
    }

    /**
     * 获取我的订单列表
     */
    public List<OrderDetailResponse> getMyOrders(Long userId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getUserId, userId)
                .orderByDesc(OrderInfo::getCreateTime)
                .last("LIMIT 20");

        List<OrderInfo> orderInfos = orderInfoMapper.selectList(wrapper);

        return orderInfos.stream().map(orderInfo -> {
            OrderDetailResponse response = new OrderDetailResponse();
            response.setId(orderInfo.getId());
            response.setOrderNo(orderInfo.getOrderNo());
            response.setUserId(orderInfo.getUserId());
            response.setActivityId(orderInfo.getActivityId());
            response.setTotalAmount(orderInfo.getTotalAmount());
            response.setStatus(orderInfo.getStatus());
            response.setStatusDesc(getStatusDesc(orderInfo.getStatus()));
            response.setPayTime(orderInfo.getPayTime());
            response.setCancelTime(orderInfo.getCancelTime());
            response.setCreateTime(orderInfo.getCreateTime());
            return response;
        }).toList();
    }

    /**
     * 取消订单
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderNo, String reason) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        if (orderInfo.getStatus() != 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单状态不允许取消");
        }

        orderInfo.setStatus(2); // 已取消
        orderInfo.setCancelTime(LocalDateTime.now());
        orderInfoMapper.updateById(orderInfo);

        // 获取订单明细
        List<OrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
        );

        if (!orderItems.isEmpty()) {
            OrderItem orderItem = orderItems.get(0);

            // 发送库存回滚消息
            com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                    new com.flashsale.common.dto.StockRollbackMessage();
            rollbackMessage.setOrderNo(orderNo);
            rollbackMessage.setActivityId(orderInfo.getActivityId());
            rollbackMessage.setItemId(orderItem.getItemId());
            rollbackMessage.setQuantity(orderItem.getQuantity());
            rollbackMessage.setUserId(orderInfo.getUserId());

            try {
                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                log.info("发送库存回滚消息: orderNo={}", orderNo);
            } catch (Exception e) {
                log.error("发送库存回滚消息失败: orderNo={}", orderNo, e);
            }
        }

        // 清理用户购买标记
        for (OrderItem orderItem : orderItems) {
            String purchaseKey = "user:purchase:" + orderInfo.getUserId() + ":stock:"
                    + orderInfo.getActivityId() + ":" + orderItem.getItemId();
            redisTemplate.delete(purchaseKey);
        }

        log.info("订单取消: orderNo={}, reason={}", orderNo, reason);
    }

    /**
     * 检查订单是否存在（内部接口）
     *
     * @param orderNo 订单号
     * @return 订单是否存在
     */
    public boolean checkOrderExists(String orderNo) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        return orderInfoMapper.selectCount(wrapper) > 0;
    }

    /**
     * 生成订单号
     */
    private String generateOrderNo() {
        return String.valueOf(snowflake.nextId());
    }

    /**
     * 获取状态描述
     */
    private String getStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时";
            default -> "未知";
        };
    }

    /**
     * 订单创建消息
     */
    @lombok.Data
    public static class OrderCreateMessage {
        private String orderNo;
        private Long userId;
        private Long activityId;
        private Long itemId;
        private Integer quantity;
    }

    /**
     * 定时任务：取消超时未支付订单
     * 每分钟执行一次，扫描待支付超过15分钟的订单
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60000)
    public void cancelTimeoutOrders() {
        try {
            LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(15);

            // 查询待支付且超时的订单
            LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(OrderInfo::getStatus, 0) // 待支付
                    .lt(OrderInfo::getCreateTime, timeoutThreshold); // 创建时间超过15分钟

            List<OrderInfo> timeoutOrders = orderInfoMapper.selectList(wrapper);

            if (!timeoutOrders.isEmpty()) {
                log.info("发现{}笔超时订单，开始取消", timeoutOrders.size());

                for (OrderInfo order : timeoutOrders) {
                    try {
                        cancelOrder(order.getOrderNo(), "订单超时未支付");
                    } catch (Exception e) {
                        log.error("取消超时订单失败: orderNo={}", order.getOrderNo(), e);
                    }
                }

                log.info("超时订单取消完成，共处理{}笔", timeoutOrders.size());
            }
        } catch (Exception e) {
            log.error("取消超时订单定时任务执行失败", e);
        }
    }
}
