package com.flashsale.common.constant;

/**
 * RocketMQ常量
 */
public class MqConstant {

    /**
     * 秒杀订单主题（订单创建消息）
     */
    public static final String SECKILL_ORDER_TOPIC = "SECKILL_ORDER_TOPIC";

    /**
     * 库存扣减结果主题
     */
    public static final String STOCK_RESULT_TOPIC = "STOCK_RESULT_TOPIC";

    /**
     * 库存回滚主题
     */
    public static final String STOCK_ROLLBACK_TOPIC = "STOCK_ROLLBACK_TOPIC";

    /**
     * 活动失效主题（活动删除/过期时通知订单服务）
     */
    public static final String ACTIVITY_INVALIDATE_TOPIC = "ACTIVITY_INVALIDATE_TOPIC";

    /**
     * 库存扣减消费者组（消费订单创建消息）
     */
    public static final String STOCK_DEDUCT_CONSUMER_GROUP = "INVENTORY_DEDUCT_CONSUMER_GROUP";

    /**
     * 订单结果消费者组（消费库存扣减结果消息）
     */
    public static final String ORDER_RESULT_CONSUMER_GROUP = "ORDER_RESULT_CONSUMER_GROUP";

    /**
     * 订单创建消费者组（已废弃）
     */
    @Deprecated
    public static final String ORDER_CREATE_CONSUMER_GROUP = "ORDER_CREATE_CONSUMER_GROUP";
}
