package com.flashsale.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.entity.LocalMessage;
import com.flashsale.order.mapper.LocalMessageMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 本地消息服务（Order 服务）
 * 高并发场景下使用内存队列缓冲，定时批量刷入数据库
 */
@Slf4j
@Service
public class LocalMessageService {

    @Autowired
    private LocalMessageMapper localMessageMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int DEFAULT_MAX_RETRY = 5;
    private static final int RETRY_INTERVAL_SECONDS = 30;

    /** 内存缓冲队列，容量 10000 */
    private static final int QUEUE_CAPACITY = 10000;
    private final BlockingQueue<LocalMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /** 跟踪已发送成功的消息，解决 buffer 与 markMessageAsSent 的 race condition */
    private final ConcurrentHashMap<String, Boolean> sentFlags = new ConcurrentHashMap<>();

    /** 批量写入大小 */
    private static final int BATCH_SIZE = 500;

    /**
     * 保存待发送消息（缓冲模式）
     * 消息先放入内存队列，由定时任务批量刷入数据库
     * 队列满时降级为直接写库，保证消息不丢
     * @param businessNo 业务ID（订单号）
     * @param topic 消息主题
     * @param messageBody 消息内容
     * @return 消息ID（缓冲模式下返回 null）
     */
    public Long saveMessage(String businessNo, String topic, Object messageBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(messageBody);

            LocalMessage message = new LocalMessage();
            message.setBusinessNo(businessNo);
            message.setTopic(topic);
            message.setMessageBody(jsonBody);
            message.setStatus(0);
            message.setRetryCount(0);
            message.setMaxRetry(DEFAULT_MAX_RETRY);
            message.setNextRetryTime(LocalDateTime.now());
            message.setRemark("订单创建消息");

            // 尝试放入队列，满时降级为直接写库
            if (!messageQueue.offer(message)) {
                log.warn("消息队列已满，降级为直接写库: businessNo={}", businessNo);
                saveMessageDirect(message);
            }

            return null;
        } catch (Exception e) {
            log.error("保存本地消息失败: businessNo={}", businessNo, e);
            throw new RuntimeException("保存本地消息失败", e);
        }
    }

    /**
     * 直接写库（降级模式）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveMessageDirect(LocalMessage message) {
        localMessageMapper.insert(message);
        log.info("直接写库保存本地消息成功: businessNo={}, topic={}, messageId={}",
                message.getBusinessNo(), message.getTopic(), message.getId());
    }

    /**
     * 批量刷入数据库
     * 从队列中取出最多 BATCH_SIZE 条消息，一次性 INSERT
     * flush 前检查 sentFlags，已发送成功的消息直接以 status=1 入库
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void flushMessages() {
        List<LocalMessage> batch = new ArrayList<>(BATCH_SIZE);
        messageQueue.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        // 检查 sentFlags，将已发送的消息标记为 status=1
        for (LocalMessage message : batch) {
            if (sentFlags.remove(message.getBusinessNo()) != null) {
                message.setStatus(1); // 已发送
                message.setRemark("发送成功");
            }
        }

        try {
            localMessageMapper.insertBatch(batch);
            log.info("批量写入本地消息成功: batchSize={}", batch.size());
        } catch (Exception e) {
            log.error("批量写入本地消息失败，逐条降级写入: batchSize={}", batch.size(), e);
            for (LocalMessage message : batch) {
                try {
                    localMessageMapper.insert(message);
                } catch (Exception ex) {
                    log.error("逐条写入也失败: businessNo={}", message.getBusinessNo(), ex);
                }
            }
        }
    }

    /**
     * 获取当前缓冲队列大小（用于监控）
     */
    public int getQueueSize() {
        return messageQueue.size();
    }

    /**
     * 应用关闭时刷入剩余消息，防止丢消息
     */
    @PreDestroy
    public void shutdown() {
        int remaining = messageQueue.size();
        if (remaining > 0) {
            log.info("应用关闭，刷入剩余缓冲消息: remaining={}", remaining);
            // 分批 flush，防止单次事务过大
            while (!messageQueue.isEmpty()) {
                flushMessages();
            }
            log.info("缓冲消息刷入完成");
        }
    }

    /**
     * 尝试发送消息并更新状态（CAS 方式防止并发重复发送）
     * @param message 本地消息
     * @return 是否发送成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean trySendMessage(LocalMessage message) {
        LambdaUpdateWrapper<LocalMessage> claimWrapper = new LambdaUpdateWrapper<>();
        claimWrapper.eq(LocalMessage::getId, message.getId())
                .eq(LocalMessage::getStatus, 0)
                .eq(LocalMessage::getRetryCount, message.getRetryCount())
                .set(LocalMessage::getStatus, 3)
                .set(LocalMessage::getRemark, "发送中");

        int claimed = localMessageMapper.update(null, claimWrapper);
        if (claimed == 0) {
            log.debug("消息已被其他实例处理，跳过: messageId={}, businessNo={}",
                    message.getId(), message.getBusinessNo());
            return false;
        }

        try {
            rocketMQTemplate.syncSend(message.getTopic(), message.getMessageBody());

            LambdaUpdateWrapper<LocalMessage> successWrapper = new LambdaUpdateWrapper<>();
            successWrapper.eq(LocalMessage::getId, message.getId())
                    .eq(LocalMessage::getStatus, 3)
                    .set(LocalMessage::getStatus, 1)
                    .set(LocalMessage::getRemark, "发送成功");
            localMessageMapper.update(null, successWrapper);

            log.info("消息发送成功: messageId={}, businessNo={}", message.getId(), message.getBusinessNo());
            return true;

        } catch (Exception e) {
            LambdaUpdateWrapper<LocalMessage> failWrapper = new LambdaUpdateWrapper<>();
            failWrapper.eq(LocalMessage::getId, message.getId())
                    .eq(LocalMessage::getStatus, 3)
                    .set(LocalMessage::getStatus, 0)
                    .set(LocalMessage::getRetryCount, message.getRetryCount() + 1)
                    .set(LocalMessage::getNextRetryTime,
                            LocalDateTime.now().plusSeconds(RETRY_INTERVAL_SECONDS * (message.getRetryCount() + 1)));

            if (message.getRetryCount() + 1 >= message.getMaxRetry()) {
                failWrapper.set(LocalMessage::getStatus, 2)
                        .set(LocalMessage::getRemark, "超过最大重试次数: " + e.getMessage());
            }
            localMessageMapper.update(null, failWrapper);

            log.error("消息发送失败: messageId={}, businessNo={}, retryCount={}",
                    message.getId(), message.getBusinessNo(), message.getRetryCount() + 1, e);
            return false;
        }
    }

    /**
     * 处理待发送的消息（定时任务调用）
     */
    public void processPendingMessages() {
        List<LocalMessage> pendingMessages = localMessageMapper.selectPendingMessages(LocalDateTime.now(), 100);

        if (!pendingMessages.isEmpty()) {
            log.info("发现{}条待发送消息，开始处理", pendingMessages.size());

            int successCount = 0;
            int failCount = 0;

            for (LocalMessage message : pendingMessages) {
                boolean success = trySendMessage(message);
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            log.info("待发送消息处理完成，成功{}条，失败{}条", successCount, failCount);
        }
    }

    /**
     * 根据业务号更新消息状态为已发送
     * 先尝试 DB UPDATE，如果消息还在缓冲队列中（未入库），则标记 sentFlags 供 flush 时使用
     * @param businessNo 业务ID（订单号）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markMessageAsSent(String businessNo) {
        LambdaUpdateWrapper<LocalMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LocalMessage::getBusinessNo, businessNo)
                .eq(LocalMessage::getStatus, 0)
                .set(LocalMessage::getStatus, 1)
                .set(LocalMessage::getRemark, "发送成功");
        int affected = localMessageMapper.update(null, wrapper);
        if (affected == 0) {
            // 消息还在缓冲队列中未入库，标记 sentFlags 供 flush 时使用
            sentFlags.put(businessNo, true);
        }
    }
}
