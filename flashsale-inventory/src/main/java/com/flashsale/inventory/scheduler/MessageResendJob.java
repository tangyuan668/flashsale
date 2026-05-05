package com.flashsale.inventory.scheduler;

import com.flashsale.inventory.service.InventoryService;
import com.flashsale.inventory.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息重发定时任务（Inventory 服务）
 */
@Slf4j
@Component
public class MessageResendJob {

    @Autowired
    private LocalMessageService localMessageService;

    @Autowired
    private InventoryService inventoryService;

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

    /**
     * 定时批量刷入缓冲消息
     * 每200ms执行一次
     */
    @Scheduled(fixedRate = 200)
    public void flushBufferedMessages() {
        try {
            localMessageService.flushMessages();
        } catch (Exception e) {
            log.error("消息缓冲刷入定时任务执行失败", e);
        }
    }

    /**
     * 定时批量刷入库存日志
     * 每1秒执行一次
     */
    @Scheduled(fixedRate = 1000)
    public void flushInventoryLogs() {
        try {
            inventoryService.flushInventoryLogs();
        } catch (Exception e) {
            log.error("库存日志刷入定时任务执行失败", e);
        }
    }
}
