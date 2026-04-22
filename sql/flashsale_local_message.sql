-- 本地消息表
-- 用于保障库存扣减和 MQ 消息的最终一致性

CREATE TABLE IF NOT EXISTS `local_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `business_no` VARCHAR(64) NOT NULL COMMENT '业务ID（订单号）',
    `topic` VARCHAR(128) NOT NULL COMMENT '消息主题',
    `message_body` TEXT NOT NULL COMMENT '消息内容（JSON）',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '消息状态: 0-待发送, 1-已发送, 2-发送失败',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `max_retry` INT NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    `next_retry_time` DATETIME NOT NULL COMMENT '下次重试时间',
    `remark` VARCHAR(512) COMMENT '备注',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_business_no` (`business_no`),
    KEY `idx_status_retry` (`status`, `next_retry_time`, `retry_count`),
    KEY `idx_topic` (`topic`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
