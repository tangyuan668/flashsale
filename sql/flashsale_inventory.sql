-- =============================================
-- 库存数据库 - flashsale_inventory
-- =============================================

CREATE DATABASE IF NOT EXISTS `flashsale_inventory` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
-- 初始化测试数据
-- ---------------------------------------------
INSERT INTO `inventory` (`activity_id`, `item_id`, `total_stock`, `available_stock`, `frozen_stock`, `version`) VALUES
(1, 1001, 100, 100, 0, 0),
(2, 1002, 200, 200, 0, 0);
