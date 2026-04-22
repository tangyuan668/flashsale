package com.flashsale.order.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.common.dto.OrderCreateMessage;
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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
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

    @Value("${order.snowflake.worker-id:1}")
    private long workerId;

    @Value("${order.snowflake.datacenter-id:1}")
    private long datacenterId;

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        log.info("订单服务初始化雪花算法: workerId={}, datacenterId={}", workerId, datacenterId);
    }

    /**
     * 秒杀下单（异步处理）
     */
    public SeckillResponse seckill(Long userId, SeckillRequest request) {
        // 0. 前置幂等检查：快速拒绝重复请求，避免浪费后续资源
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + request.getActivityId() + ":" + request.getItemId();
        String purchaseKey = "user:purchase:" + userId + ":" + stockKey;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(purchaseKey))) {
            return SeckillResponse.fail("您已购买过该商品，每人限购一件");
        }
        String deductingKey = "deducting:" + userId + ":" + stockKey;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(deductingKey))) {
            return SeckillResponse.fail("订单处理中，请勿重复提交");
        }

        // 1. 前置校验：检查活动商品信息并校验限购数量
        try {
            ActivityDto activityDto = activityFeignClient.getActivityDetail(request.getActivityId()).getData();
            if (activityDto == null || activityDto.getItems() == null) {
                return SeckillResponse.fail("活动或商品不存在");
            }

            // 查找目标商品
            ActivityItemDto targetItem = activityDto.getItems().stream()
                    .filter(item -> item.getItemId().equals(request.getItemId()))
                    .findFirst()
                    .orElse(null);

            if (targetItem == null) {
                return SeckillResponse.fail("商品不存在");
            }

            // 校验购买数量不超过限购数量
            if (request.getQuantity() > targetItem.getLimitPerUser()) {
                return SeckillResponse.fail("每人限购" + targetItem.getLimitPerUser() + "件");
            }

        } catch (Exception e) {
            log.error("获取活动信息失败: activityId={}", request.getActivityId(), e);
            return SeckillResponse.fail("获取活动信息失败，请稍后重试");
        }

        // 2. 生成订单号
        String orderNo = generateOrderNo();

        // 3. 记录处理中的订单（5分钟超时）
        String processingKey = "order:processing:" + orderNo;
        redisTemplate.opsForValue().set(processingKey, "1", 5, TimeUnit.MINUTES);
        // 使用 ZSET 记录过期时间，便于定时任务扫描
        long expireTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        redisTemplate.opsForZSet().add("order:processing:zset", orderNo, expireTime);
        log.info("记录处理中订单: orderNo={}, userId={}", orderNo, userId);

        // 4. 构造订单创建消息
        OrderCreateMessage message = new OrderCreateMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setActivityId(request.getActivityId());
        message.setItemId(request.getItemId());
        message.setQuantity(request.getQuantity());

        // 5. 保存到本地消息表
        localMessageService.saveMessage(orderNo, MqConstant.SECKILL_ORDER_TOPIC, message);

        // 6. 尝试发送 MQ（发送失败由定时任务重试）
        try {
            rocketMQTemplate.syncSend(MqConstant.SECKILL_ORDER_TOPIC, message);
            log.info("发送秒杀订单消息成功: orderNo={}, userId={}", orderNo, userId);
            // 立即更新本地消息表状态为已发送
            localMessageService.markMessageAsSent(orderNo);
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
            // 消息已保存到本地消息表，定时任务会重试，用户可以正常获取处理中状态
        }

        // 7. 返回处理中状态
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
            // 清理扣减中标记（使用与 InventoryService 相同的 key 格式）
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + message.getActivityId() + ":" + message.getItemId();
            String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
            redisTemplate.delete(deductingKey);
            log.info("库存扣减失败，已记录: orderNo={}", message.getOrderNo());
        }
    }

    /**
     * 库存扣减成功后创建订单
     */
    private void createOrderAfterStockDeducted(StockDeductMessage message) {
        // 0. 检查订单是否已被补偿（库存已回滚）
        // 场景：库存补偿任务已回滚库存，但库存结果消息延迟到达
        // 如果订单已被补偿，则拒绝创建订单，避免数据不一致
        String compensatedKey = "order:compensated:" + message.getOrderNo();
        Boolean isCompensated = redisTemplate.hasKey(compensatedKey);
        if (Boolean.TRUE.equals(isCompensated)) {
            String reason = redisTemplate.opsForValue().get(compensatedKey).toString();
            log.warn("订单已被补偿，拒绝创建: orderNo={}, reason={}", message.getOrderNo(), reason);

            // 记录失败状态，用户可看到失败原因
            String failKey = "order:fail:" + message.getOrderNo();
            String failReason = "订单处理超时，请重新下单";
            redisTemplate.opsForValue().set(failKey, failReason, 24, TimeUnit.HOURS);

            // 清理扣减中标记
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + message.getActivityId() + ":" + message.getItemId();
            String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
            redisTemplate.delete(deductingKey);
            // 清理处理中标记
            redisTemplate.delete("order:processing:" + message.getOrderNo());
            redisTemplate.opsForZSet().remove("order:processing:zset", message.getOrderNo());

            return;
        }

        // 0.5. 检查用户是否已购买（防止延迟消息重复创建订单）
        // 场景：用户重试成功创建订单后，旧的延迟库存结果消息到达
        // 如果用户已有购买标记，则拒绝创建，避免重复订单
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + message.getActivityId() + ":" + message.getItemId();
        String purchaseKey = "user:purchase:" + message.getUserId() + ":" + stockKey;
        Boolean hasPurchased = redisTemplate.hasKey(purchaseKey);
        if (Boolean.TRUE.equals(hasPurchased)) {
            String existingOrderNo = redisTemplate.opsForValue().get(purchaseKey).toString();
            log.warn("用户已有订单，拒绝创建延迟订单: orderNo={}, existingOrderNo={}",
                    message.getOrderNo(), existingOrderNo);

            // 记录失败状态，用户可看到失败原因
            String failKey = "order:fail:" + message.getOrderNo();
            String failReason = "订单重复，您已有成功订单";
            redisTemplate.opsForValue().set(failKey, failReason, 24, TimeUnit.HOURS);

            // 发送库存回滚消息
            com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                    new com.flashsale.common.dto.StockRollbackMessage();
            rollbackMessage.setOrderNo(message.getOrderNo());
            rollbackMessage.setActivityId(message.getActivityId());
            rollbackMessage.setItemId(message.getItemId());
            rollbackMessage.setQuantity(message.getQuantity());
            rollbackMessage.setUserId(message.getUserId());

            try {
                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                log.info("因重复订单发送库存回滚消息: orderNo={}, existingOrderNo={}",
                        message.getOrderNo(), existingOrderNo);
            } catch (Exception e) {
                log.error("发送库存回滚消息失败: orderNo={}", message.getOrderNo(), e);
            }

            // 清理处理中标记
            redisTemplate.delete("order:processing:" + message.getOrderNo());
            redisTemplate.opsForZSet().remove("order:processing:zset", message.getOrderNo());

            return;
        }

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
            // 清理处理中标记
            redisTemplate.delete("order:processing:" + message.getOrderNo());
            redisTemplate.opsForZSet().remove("order:processing:zset", message.getOrderNo());
            // 清理扣减中标记（使用与 InventoryService 相同的 key 格式）
            String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
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
            // 清理处理中标记
            redisTemplate.delete("order:processing:" + message.getOrderNo());
            redisTemplate.opsForZSet().remove("order:processing:zset", message.getOrderNo());
            // 清理扣减中标记（使用与 InventoryService 相同的 key 格式）
            String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
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
        purchaseKey = "user:purchase:" + message.getUserId() + ":" + stockKey;
        redisTemplate.opsForValue().set(purchaseKey, message.getOrderNo(), 24, TimeUnit.HOURS);
        // 清理扣减中标记（使用与 InventoryService 相同的 key 格式）
        String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
        redisTemplate.delete(deductingKey);
        // 清理处理中标记
        redisTemplate.delete("order:processing:" + message.getOrderNo());
        redisTemplate.opsForZSet().remove("order:processing:zset", message.getOrderNo());

        log.info("订单创建成功: orderNo={}, totalAmount={}", message.getOrderNo(), orderInfo.getTotalAmount());
    }

    /**
     * 获取订单详情
     */
    public OrderDetailResponse getOrderDetail(String orderNo, Long userId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 校验订单归属
        if (!orderInfo.getUserId().equals(userId)) {
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
     * 取消订单（CAS 乐观锁方式，防止多实例重复处理）
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long userId, String orderNo, String reason) {
        // 先查询订单信息（用于后续业务处理）
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 校验订单归属
        if (!orderInfo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // CAS 方式抢占更新：只有 status=0 的才能更新为 status=2
        LambdaUpdateWrapper<OrderInfo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(OrderInfo::getOrderNo, orderNo)
                .eq(OrderInfo::getStatus, 0)  // ← CAS 条件：必须是待支付状态
                .set(OrderInfo::getStatus, 2)  // 已取消
                .set(OrderInfo::getCancelTime, LocalDateTime.now());

        int affected = orderInfoMapper.update(null, updateWrapper);

        if (affected == 0) {
            // CAS 失败，说明订单已被其他实例处理或状态已变更
            log.info("订单已被其他实例处理或状态已变更，跳过取消: orderNo={}, currentStatus={}",
                    orderNo, orderInfo.getStatus());
            return;
        }

        // CAS 抢占成功，继续后续处理

        // 获取订单明细
        List<OrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
        );

        if (!orderItems.isEmpty()) {
            OrderItem orderItem = orderItems.get(0);

            // 构造库存回滚消息
            com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                    new com.flashsale.common.dto.StockRollbackMessage();
            rollbackMessage.setOrderNo(orderNo);
            rollbackMessage.setActivityId(orderInfo.getActivityId());
            rollbackMessage.setItemId(orderItem.getItemId());
            rollbackMessage.setQuantity(orderItem.getQuantity());
            rollbackMessage.setUserId(orderInfo.getUserId());

            // 保存到本地消息表（可靠发送）
            localMessageService.saveMessage(orderNo, MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);

            // 尝试发送 MQ（发送失败由定时任务重试）
            try {
                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                log.info("发送库存回滚消息成功: orderNo={}", orderNo);
                // 立即更新本地消息表状态为已发送
                localMessageService.markMessageAsSent(orderNo);
            } catch (Exception e) {
                log.error("发送库存回滚消息失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
                // 消息已保存到本地消息表，定时任务会重试
            }
        }

        // 清理用户购买标记（使用与 InventoryService 相同的 key 格式）
        for (OrderItem orderItem : orderItems) {
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + orderInfo.getActivityId() + ":" + orderItem.getItemId();
            String purchaseKey = "user:purchase:" + orderInfo.getUserId() + ":" + stockKey;
            redisTemplate.delete(purchaseKey);
        }

        log.info("订单取消成功: orderNo={}, reason={}", orderNo, reason);
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
     * 检查用户对指定商品是否有成功的订单（排除指定订单号）
     * 用于库存服务补偿回滚时判断用户是否已重试成功
     *
     * @param userId 用户ID
     * @param activityId 活动ID
     * @param itemId 商品ID
     * @param excludeOrderNo 排除的订单号（当前检查的失败订单）
     * @return 是否有成功订单
     */
    public boolean checkUserHasSuccessOrder(Long userId, Long activityId, Long itemId, String excludeOrderNo) {
        // 查询该用户对指定商品的有效订单（排除当前失败订单）
        // 只有待支付(0)和已支付(1)的订单才算成功订单
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getUserId, userId)
                .eq(OrderInfo::getActivityId, activityId)
                .ne(OrderInfo::getOrderNo, excludeOrderNo) // 排除当前订单
                .in(OrderInfo::getStatus, 0, 1); // 只查询待支付或已支付的订单

        OrderInfo order = orderInfoMapper.selectOne(wrapper);

        if (order == null) {
            return false;
        }

        // 检查该订单是否包含指定商品
        LambdaQueryWrapper<OrderItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(OrderItem::getOrderNo, order.getOrderNo())
                .eq(OrderItem::getItemId, itemId);

        boolean hasItem = orderItemMapper.selectCount(itemWrapper) > 0;

        if (hasItem) {
            log.debug("用户有后续成功订单: userId={}, activityId={}, itemId={}, successOrderNo={}",
                    userId, activityId, itemId, order.getOrderNo());
        }

        return hasItem;
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
                        cancelOrder(order.getUserId(), order.getOrderNo(), "订单超时未支付");
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

    /**
     * 定时任务：标记超时未创建的订单为失败
     * 每分钟执行一次，扫描处理中超过5分钟的订单
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60000)
    public void markTimeoutOrdersAsFailed() {
        try {
            long now = System.currentTimeMillis();

            // 扫描 ZSET 中过期的订单（score < 当前时间）
            // 使用 Redis 的 ZRANGEBYSCORE 命令
            Set<Object> expiredOrders = redisTemplate.opsForZSet().rangeByScore(
                    "order:processing:zset",
                    0,
                    now
            );

            if (expiredOrders == null || expiredOrders.isEmpty()) {
                return;
            }

            log.info("发现{}个超时未创建的订单，开始处理", expiredOrders.size());

            int markedCount = 0;
            for (Object obj : expiredOrders) {
                String orderNo = obj.toString();
                try {
                    // 检查订单是否已创建
                    if (checkOrderExists(orderNo)) {
                        // 订单已创建，从 ZSET 中移除
                        redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                        redisTemplate.delete("order:processing:" + orderNo);
                        continue;
                    }

                    // 检查是否已有失败标记
                    String failKey = "order:fail:" + orderNo;
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(failKey))) {
                        // 已有失败标记，从 ZSET 中移除
                        redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                        redisTemplate.delete("order:processing:" + orderNo);
                        continue;
                    }

                    // 设置失败标记
                    redisTemplate.opsForValue().set(failKey, "订单创建超时，请重试", 24, TimeUnit.HOURS);
                    // 从 ZSET 中移除
                    redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                    redisTemplate.delete("order:processing:" + orderNo);

                    markedCount++;
                    log.info("标记超时订单为失败: orderNo={}", orderNo);

                } catch (Exception e) {
                    log.error("标记超时订单失败: orderNo={}", orderNo, e);
                }
            }

            if (markedCount > 0) {
                log.info("超时订单标记完成，共标记{}笔", markedCount);
            }

        } catch (Exception e) {
            log.error("标记超时订单定时任务执行失败", e);
        }
    }
}
