package com.flashsale.common.constant;

/**
 * RocketMQ常量
 */
public class MqConstant {

    /**
     * 秒杀订单主题
     */
    public static final String SECKILL_ORDER_TOPIC = "SECKILL_ORDER_TOPIC";

    /**
     * 订单创建消费者组
     */
    public static final String ORDER_CREATE_CONSUMER_GROUP = "ORDER_CREATE_CONSUMER_GROUP";

    /**
     * 库存扣减消费者组
     */
    public static final String STOCK_DEDUCT_CONSUMER_GROUP = "STOCK_DEDUCT_CONSUMER_GROUP";

    /**
     * 订单通知主题
     */
    public static final String ORDER_NOTIFY_TOPIC = "ORDER_NOTIFY_TOPIC";

    /**
     * 订单通知消费者组
     */
    public static final String ORDER_NOTIFY_CONSUMER_GROUP = "ORDER_NOTIFY_CONSUMER_GROUP";
}
