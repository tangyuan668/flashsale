package com.flashsale.order.scheduler;

import com.flashsale.order.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息重发定时任务（Order 服务）
 */
@Slf4j
@Component
public class MessageResendJob {

    @Autowired
    private LocalMessageService localMessageService;

    /**
     * 定时处理待发送的消息
     * 每30秒执行一次
     */
    @Scheduled(fixedRate = 30000)
    public void resendPendingMessages() {
        try {
            localMessageService.processPendingMessages();
        } catch (Exception e) {
            log.error("消息重发定时任务执行失败", e);
        }
    }
}
