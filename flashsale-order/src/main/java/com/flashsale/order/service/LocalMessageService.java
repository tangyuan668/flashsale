package com.flashsale.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.entity.LocalMessage;
import com.flashsale.order.mapper.LocalMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息服务（Order 服务）
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

    /**
     * 保存待发送消息
     * 使用独立事务，确保消息记录不受外层业务事务回滚影响
     * @param businessNo 业务ID（订单号）
     * @param topic 消息主题
     * @param messageBody 消息内容
     * @return 消息ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long saveMessage(String businessNo, String topic, Object messageBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(messageBody);

            LocalMessage message = new LocalMessage();
            message.setBusinessNo(businessNo);
            message.setTopic(topic);
            message.setMessageBody(jsonBody);
            message.setStatus(0); // 待发送
            message.setRetryCount(0);
            message.setMaxRetry(DEFAULT_MAX_RETRY);
            message.setNextRetryTime(LocalDateTime.now());
            message.setRemark("订单创建消息");

            localMessageMapper.insert(message);
            log.info("保存本地消息成功: businessNo={}, topic={}, messageId={}", businessNo, topic, message.getId());

            return message.getId();
        } catch (Exception e) {
            log.error("保存本地消息失败: businessNo={}", businessNo, e);
            throw new RuntimeException("保存本地消息失败", e);
        }
    }

    /**
     * 尝试发送消息并更新状态（CAS 方式防止并发重复发送）
     * @param message 本地消息
     * @return 是否发送成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean trySendMessage(LocalMessage message) {
        // 先 CAS 抢占状态：只有 status=0 的才能被更新为"发送中"(status=3)
        // 使用当前 retryCount 作为版本校验，防止并发重复处理
        LambdaUpdateWrapper<LocalMessage> claimWrapper = new LambdaUpdateWrapper<>();
        claimWrapper.eq(LocalMessage::getId, message.getId())
                .eq(LocalMessage::getStatus, 0) // 必须是待发送状态
                .eq(LocalMessage::getRetryCount, message.getRetryCount()) // CAS 校验重试次数
                .set(LocalMessage::getStatus, 3) // 发送中（临时状态）
                .set(LocalMessage::getRemark, "发送中");

        int claimed = localMessageMapper.update(null, claimWrapper);
        if (claimed == 0) {
            // CAS 失败，说明其他实例正在处理或已处理
            log.debug("消息已被其他实例处理，跳过: messageId={}, businessNo={}",
                    message.getId(), message.getBusinessNo());
            return false;
        }

        try {
            // 抢占成功，发送 MQ
            rocketMQTemplate.syncSend(message.getTopic(), message.getMessageBody());

            // 发送成功，更新状态
            LambdaUpdateWrapper<LocalMessage> successWrapper = new LambdaUpdateWrapper<>();
            successWrapper.eq(LocalMessage::getId, message.getId())
                    .eq(LocalMessage::getStatus, 3) // 必须是发送中状态
                    .set(LocalMessage::getStatus, 1) // 已发送
                    .set(LocalMessage::getRemark, "发送成功");
            localMessageMapper.update(null, successWrapper);

            log.info("消息发送成功: messageId={}, businessNo={}", message.getId(), message.getBusinessNo());
            return true;

        } catch (Exception e) {
            // 发送失败，恢复为待发送并增加重试次数
            LambdaUpdateWrapper<LocalMessage> failWrapper = new LambdaUpdateWrapper<>();
            failWrapper.eq(LocalMessage::getId, message.getId())
                    .eq(LocalMessage::getStatus, 3) // 必须是发送中状态
                    .set(LocalMessage::getStatus, 0) // 恢复为待发送
                    .set(LocalMessage::getRetryCount, message.getRetryCount() + 1)
                    .set(LocalMessage::getNextRetryTime,
                            LocalDateTime.now().plusSeconds(RETRY_INTERVAL_SECONDS * (message.getRetryCount() + 1)));

            // 超过最大重试次数，标记为失败
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
     * 使用独立事务，确保状态更新不受外层业务事务回滚影响
     * @param businessNo 业务ID（订单号）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markMessageAsSent(String businessNo) {
        LambdaUpdateWrapper<LocalMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LocalMessage::getBusinessNo, businessNo)
                .eq(LocalMessage::getStatus, 0)
                .set(LocalMessage::getStatus, 1)
                .set(LocalMessage::getRemark, "发送成功");
        localMessageMapper.update(null, wrapper);
    }
}
