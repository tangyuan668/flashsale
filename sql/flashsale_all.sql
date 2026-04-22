-- =============================================
-- FlashSale 秒杀系统 - 完整数据库初始化脚本
-- =============================================
-- 执行方式: mysql -u root -p < flashsale_all.sql
-- =============================================

-- =============================================
-- 用户数据库 - flashsale_user
-- =============================================
USE `flashsale_user`;

-- ---------------------------------------------
-- 用户表
-- ---------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `phone` VARCHAR(11) NOT NULL COMMENT '手机号',
    `password` VARCHAR(128) NOT NULL COMMENT '密码(BCrypt加密)',
    `nickname` VARCHAR(32) DEFAULT NULL COMMENT '昵称',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-禁用',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ---------------------------------------------
-- 初始化测试数据
-- ---------------------------------------------
INSERT INTO `user` (`phone`, `password`, `nickname`, `status`) VALUES
('13800138000', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '测试用户1', 1),
('13800138001', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '测试用户2', 1);
-- 密码都是: 123456

-- =============================================
-- 活动数据库 - flashsale_activity
-- =============================================
USE `flashsale_activity`;

-- ---------------------------------------------
-- 秒杀活动表
-- ---------------------------------------------
DROP TABLE IF EXISTS `activity`;
CREATE TABLE `activity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    `name` VARCHAR(64) NOT NULL COMMENT '活动名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '活动描述',
    `cover_image` VARCHAR(255) DEFAULT NULL COMMENT '封面图URL',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-待开始, 1-进行中, 2-已结束, 3-已取消',
    `preheat_status` TINYINT NOT NULL DEFAULT 0 COMMENT '库存预热状态: 0-未预热, 1-已预热',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

-- ---------------------------------------------
-- 活动商品关联表
-- ---------------------------------------------
DROP TABLE IF EXISTS `activity_item`;
CREATE TABLE `activity_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `item_name` VARCHAR(128) NOT NULL COMMENT '商品名称',
    `item_image` VARCHAR(255) DEFAULT NULL COMMENT '商品图片',
    `original_price` DECIMAL(10,2) NOT NULL COMMENT '原价',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    `stock` INT NOT NULL COMMENT '库存数量',
    `limit_per_user` INT NOT NULL DEFAULT 1 COMMENT '每人限购数量',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_activity_item` (`activity_id`, `item_id`),
    KEY `idx_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动商品关联表';

-- ---------------------------------------------
-- 初始化测试数据
-- ---------------------------------------------
INSERT INTO `activity` (`name`, `description`, `start_time`, `end_time`, `status`) VALUES
('iPhone 15 Pro 秒杀', 'iPhone 15 Pro 256G 极速抢购', '2024-01-01 10:00:00', '2024-12-31 23:59:59', 0),
('AirPods Pro 2 秒杀', 'AirPods Pro 2 限量秒杀', '2024-01-01 10:00:00', '2024-12-31 23:59:59', 0);

INSERT INTO `activity_item` (`activity_id`, `item_id`, `item_name`, `original_price`, `seckill_price`, `stock`, `limit_per_user`) VALUES
(1, 1001, 'iPhone 15 Pro 256G', 8999.00, 6999.00, 100, 1),
(2, 1002, 'AirPods Pro 2', 1999.00, 1599.00, 200, 1);

-- =============================================
-- 库存数据库 - flashsale_inventory
-- =============================================
USE `flashsale_inventory`;

-- ---------------------------------------------
-- 库存表
-- ---------------------------------------------
DROP TABLE IF EXISTS `inventory`;
CREATE TABLE `inventory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `total_stock` INT NOT NULL COMMENT '总库存',
    `available_stock` INT NOT NULL COMMENT '可用库存',
    `frozen_stock` INT NOT NULL DEFAULT 0 COMMENT '冻结库存(已下单未支付)',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_activity_item` (`activity_id`, `item_id`),
    KEY `idx_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表';

-- ---------------------------------------------
-- 库存变动日志表
-- ---------------------------------------------
DROP TABLE IF EXISTS `inventory_log`;
CREATE TABLE `inventory_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `order_no` VARCHAR(64) DEFAULT NULL COMMENT '订单号',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `change_type` TINYINT NOT NULL COMMENT '变动类型: 1-扣减, 2-回滚, 3-释放冻结',
    `change_amount` INT NOT NULL COMMENT '变动数量',
    `before_stock` INT NOT NULL COMMENT '变动前库存',
    `after_stock` INT NOT NULL COMMENT '变动后库存',
    `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_activity_item` (`activity_id`, `item_id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存变动日志表';

-- ---------------------------------------------
-- 本地消息表（用于保障 MQ 消息可靠性）
-- ---------------------------------------------
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
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

-- ---------------------------------------------
-- 初始化测试数据
-- ---------------------------------------------
INSERT INTO `inventory` (`activity_id`, `item_id`, `total_stock`, `available_stock`, `frozen_stock`, `version`) VALUES
(1, 1001, 100, 100, 0, 0),
(2, 1002, 200, 200, 0, 0);

-- =============================================
-- 订单数据库 - flashsale_order
-- =============================================
USE `flashsale_order`;

-- ---------------------------------------------
-- 订单表
-- ---------------------------------------------
DROP TABLE IF EXISTS `order_info`;
CREATE TABLE `order_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态: 0-待支付, 1-已支付, 2-已取消, 3-已超时',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
    `cancel_time` DATETIME DEFAULT NULL COMMENT '取消时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_activity_id` (`activity_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- ---------------------------------------------
-- 订单明细表
-- ---------------------------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_id` BIGINT NOT NULL COMMENT '订单ID',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `item_name` VARCHAR(128) NOT NULL COMMENT '商品名称',
    `item_image` VARCHAR(255) DEFAULT NULL COMMENT '商品图片',
    `price` DECIMAL(10,2) NOT NULL COMMENT '单价',
    `quantity` INT NOT NULL COMMENT '数量',
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '小计金额',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 1-已删除, 0-未删除',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

-- ---------------------------------------------
-- 本地消息表（用于保障 MQ 消息可靠性）
-- ---------------------------------------------
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
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

-- =============================================
-- 初始化完成
-- =============================================
