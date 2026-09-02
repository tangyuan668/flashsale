package com.flashsale.common.constant;

/**
 * Redis常量
 */
public class RedisConstant {

    /**
     * Token黑名单前缀
     */
    public static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /**
     * 用户登录Token前缀
     */
    public static final String USER_TOKEN_PREFIX = "user:token:";

    /**
     * 库存缓存前缀
     */
    public static final String STOCK_CACHE_PREFIX = "stock:cache:";

    /**
     * 活动缓存前缀
     */
    public static final String ACTIVITY_CACHE_PREFIX = "activity:cache:";

    /**
     * 用户购买记录前缀（防重复购买）
     */
    public static final String USER_PURCHASE_PREFIX = "user:purchase:";

    /**
     * 限流前缀
     */
    public static final String RATE_LIMIT_PREFIX = "rate:limit:";

    /**
     * IP黑名单前缀
     */
    public static final String IP_BLACKLIST_PREFIX = "ip:blacklist:";

    /**
     * 库存预热锁
     */
    public static final String STOCK_PREHEAT_LOCK = "stock:preheat:lock";

    /**
     * 活动状态切换锁
     */
    public static final String ACTIVITY_STATUS_LOCK = "activity:status:lock:";

    /**
     * 支付处理锁前缀（防重复提交）
     * Key: payment:lock:{orderNo}  TTL: 300s
     */
    public static final String PAY_LOCK_PREFIX = "payment:lock:";

    /**
     * 支付完成标记前缀（幂等）
     * Key: payment:done:{orderNo}  TTL: 24h
     */
    public static final String PAY_DONE_PREFIX = "payment:done:";
}
