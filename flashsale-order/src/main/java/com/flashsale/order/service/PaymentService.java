package com.flashsale.order.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.order.entity.OrderInfo;
import com.flashsale.order.entity.Payment;
import com.flashsale.order.mapper.OrderInfoMapper;
import com.flashsale.order.mapper.PaymentMapper;
import com.flashsale.order.vo.PayResponse;
import com.flashsale.order.vo.PaymentInfoResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 支付服务
 */
@Slf4j
@Service
public class PaymentService {

    @Autowired
    private PaymentMapper paymentMapper;

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${order.snowflake.worker-id:1}")
    private long workerId;

    @Value("${order.snowflake.datacenter-id:1}")
    private long datacenterId;

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    /**
     * 发起支付
     * <p>
     * 流程：
     * 1. 幂等检查（payment:done:{orderNo}）
     * 2. 分布式锁（payment:lock:{orderNo}）
     * 3. 校验订单（存在 + 属于用户 + status==0）
     * 4. 生成支付流水号
     * 5. 模拟支付网关
     * 6. 事务内：插入支付记录 + CAS更新订单状态 0→1
     * 7. 设置支付完成标记
     */
    public PayResponse initiatePayment(Long userId, String orderNo, String payMethod) {
        // Step 1: 幂等检查 —— 已支付过直接返回成功
        String doneKey = RedisConstant.PAY_DONE_PREFIX + orderNo;
        Object existingPayNo = redisTemplate.opsForValue().get(doneKey);
        if (existingPayNo != null) {
            log.info("支付幂等命中，orderNo={}, payNo={}", orderNo, existingPayNo);
            return PayResponse.success(existingPayNo.toString());
        }

        // Step 2: 分布式锁，防止并发支付
        String lockKey = RedisConstant.PAY_LOCK_PREFIX + orderNo;
        String payNo = generatePayNo();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, payNo, 300, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            throw new BusinessException(ErrorCode.PAY_DUPLICATE_REQUEST);
        }

        try {
            // Step 3: 校验订单
            LambdaQueryWrapper<OrderInfo> orderQuery = new LambdaQueryWrapper<>();
            orderQuery.eq(OrderInfo::getOrderNo, orderNo);
            OrderInfo order = orderInfoMapper.selectOne(orderQuery);

            if (order == null || !order.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
            }
            if (order.getStatus() != 0) {
                throw new BusinessException(ErrorCode.PAY_ORDER_NOT_PENDING);
            }

            // Step 4: 模拟支付网关 —— 生成模拟交易号
            String transactionNo = simulateTransactionNo(payMethod);
            log.info("模拟支付网关处理，orderNo={}, payMethod={}, payNo={}", orderNo, payMethod, payNo);

            // Step 5: 事务内完成支付记录 + 订单状态更新
            transactionTemplate.executeWithoutResult(status -> {
                // 5a. 插入支付记录
                Payment payment = new Payment();
                payment.setPayNo(payNo);
                payment.setOrderNo(orderNo);
                payment.setUserId(userId);
                payment.setAmount(order.getTotalAmount());
                payment.setPayMethod(payMethod);
                payment.setStatus(1); // 成功
                payment.setTransactionNo(transactionNo);
                payment.setPayTime(LocalDateTime.now());
                paymentMapper.insert(payment);

                // 5b. CAS 更新订单状态 0 → 1
                LambdaUpdateWrapper<OrderInfo> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(OrderInfo::getOrderNo, orderNo)
                        .eq(OrderInfo::getStatus, 0)
                        .set(OrderInfo::getStatus, 1)
                        .set(OrderInfo::getPayTime, LocalDateTime.now());
                int affected = orderInfoMapper.update(null, updateWrapper);
                if (affected == 0) {
                    throw new RuntimeException("CAS失败：订单状态已并发变更");
                }
            });

            // Step 6: 设置支付完成标记
            redisTemplate.opsForValue().set(doneKey, payNo, 86400, TimeUnit.SECONDS);

            log.info("支付成功，orderNo={}, payNo={}", orderNo, payNo);
            return PayResponse.success(payNo);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("支付处理异常，orderNo={}", orderNo, e);
            throw new BusinessException(ErrorCode.PAY_FAILED);
        }
    }

    /**
     * 查询支付信息
     */
    public PaymentInfoResponse getPaymentInfo(Long userId, String orderNo) {
        // 校验订单归属
        LambdaQueryWrapper<OrderInfo> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo order = orderInfoMapper.selectOne(orderQuery);

        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 查询支付记录
        LambdaQueryWrapper<Payment> paymentQuery = new LambdaQueryWrapper<>();
        paymentQuery.eq(Payment::getOrderNo, orderNo)
                .orderByDesc(Payment::getCreateTime)
                .last("LIMIT 1");
        Payment payment = paymentMapper.selectOne(paymentQuery);

        if (payment == null) {
            return null;
        }

        // 转换为响应对象
        PaymentInfoResponse response = new PaymentInfoResponse();
        response.setOrderNo(payment.getOrderNo());
        response.setPayNo(payment.getPayNo());
        response.setAmount(payment.getAmount());
        response.setPayMethod(payment.getPayMethod());
        response.setPayMethodDesc(getPayMethodDesc(payment.getPayMethod()));
        response.setStatus(payment.getStatus());
        response.setStatusDesc(getPaymentStatusDesc(payment.getStatus()));
        response.setTransactionNo(payment.getTransactionNo());
        response.setPayTime(payment.getPayTime());
        response.setCreateTime(payment.getCreateTime());
        return response;
    }

    private String generatePayNo() {
        return "PAY" + snowflake.nextId();
    }

    private String simulateTransactionNo(String payMethod) {
        return payMethod.toUpperCase() + "_" + snowflake.nextId();
    }

    private String getPayMethodDesc(String payMethod) {
        return switch (payMethod) {
            case "alipay" -> "支付宝";
            case "wechat" -> "微信支付";
            case "mock" -> "模拟支付";
            default -> payMethod;
        };
    }

    private String getPaymentStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "处理中";
            case 1 -> "成功";
            case 2 -> "失败";
            default -> "未知";
        };
    }
}
