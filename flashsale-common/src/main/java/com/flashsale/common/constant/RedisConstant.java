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
}
