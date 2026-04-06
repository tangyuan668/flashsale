package com.flashsale.order.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.inventory.service.InventoryService.StockDeductMessage;
import com.flashsale.order.dto.SeckillRequest;
import com.flashsale.order.entity.OrderInfo;
import com.flashsale.order.entity.OrderItem;
import com.flashsale.order.mapper.OrderInfoMapper;
import com.flashsale.order.mapper.OrderItemMapper;
import com.flashsale.order.vo.OrderDetailResponse;
import com.flashsale.order.vo.OrderItemVO;
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

    private static final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 秒杀下单（异步处理）
     */
    public SeckillResponse seckill(Long userId, SeckillRequest request) {
        // 生成订单号
        String orderNo = generateOrderNo();

        // 构造库存扣减请求
        InventoryService.StockDeductRequest deductRequest = new InventoryService.StockDeductRequest();
        deductRequest.setOrderNo(orderNo);
        deductRequest.setActivityId(request.getActivityId());
        deductRequest.setItemId(request.getItemId());
        deductRequest.setQuantity(request.getQuantity());
        deductRequest.setUserId(userId);

        // TODO: 调用库存服务扣减库存（这里简化处理，通过MQ异步处理）

        // 发送MQ消息到订单创建队列
        OrderCreateMessage message = new OrderCreateMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setActivityId(request.getActivityId());
        message.setItemId(request.getItemId());
        message.setQuantity(request.getQuantity());

        try {
            rocketMQTemplate.syncSend(MqConstant.SECKILL_ORDER_TOPIC, message);
            log.info("发送秒杀订单消息: orderNo={}, userId={}", orderNo, userId);

            // 异步处理，立即返回
            return SeckillResponse.success(orderNo);
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败: orderNo={}", orderNo, e);
            return SeckillResponse.fail("下单失败，请重试");
        }
    }

    /**
     * 处理订单创建（MQ消费者）
     */
    @Transactional(rollbackFor = Exception.class)
    public void processOrderCreate(OrderCreateMessage message) {
        log.info("处理订单创建: orderNo={}", message.getOrderNo());

        // 1. 调用库存服务扣减库存
        // TODO: 通过Feign调用Inventory服务

        // 2. 创建订单
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderNo(message.getOrderNo());
        orderInfo.setUserId(message.getUserId());
        orderInfo.setActivityId(message.getActivityId());
        orderInfo.setTotalAmount(BigDecimal.ZERO); // 从活动服务获取价格
        orderInfo.setStatus(0); // 待支付

        orderInfoMapper.insert(orderInfo);

        // 3. 创建订单明细
        // TODO: 从活动服务获取商品信息
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderInfo.getId());
        orderItem.setOrderNo(message.getOrderNo());
        orderItem.setItemId(message.getItemId());
        orderItem.setItemName("商品名称"); // TODO: 从活动服务获取
        orderItem.setPrice(BigDecimal.ZERO); // TODO: 从活动服务获取
        orderItem.setQuantity(message.getQuantity());
        orderItem.setTotalAmount(BigDecimal.ZERO);

        orderItemMapper.insert(orderItem);

        // 4. 设置订单超时取消（15分钟）
        String orderKey = "order:timeout:" + message.getOrderNo();
        redisTemplate.opsForValue().set(orderKey, orderInfo.getId(), 15, TimeUnit.MINUTES);

        log.info("订单创建成功: orderNo={}", message.getOrderNo());
    }

    /**
     * 处理库存扣减结果（MQ消费者）
     */
    @Transactional(rollbackFor = Exception.class)
    public void processStockDeductResult(StockDeductMessage message) {
        log.info("处理库存扣减结果: orderNo={}, success={}", message.getOrderNo(), message.getSuccess());

        if (Boolean.TRUE.equals(message.getSuccess())) {
            // 库存扣减成功，创建订单
            // 这里可以补充订单信息
        } else {
            // 库存扣减失败，取消订单
            cancelOrder(message.getOrderNo(), "库存不足");
        }
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

        // 回滚库存
        // TODO: 调用库存服务回滚

        log.info("订单取消: orderNo={}, reason={}", orderNo, reason);
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
}
