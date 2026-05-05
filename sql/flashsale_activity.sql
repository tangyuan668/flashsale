-- =============================================
-- 活动数据库 - flashsale_activity
-- =============================================

CREATE DATABASE IF NOT EXISTS `flashsale_activity` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
