package com.flashsale.common.constant;

/**
 * JWT常量
 */
public class JwtConstant {

    /**
     * JWT密钥
     */
    public static final String SECRET_KEY = "flashsale-secret-key-2024-graduation-project";

    /**
     * Token过期时间（毫秒）- 7天
     */
    public static final Long EXPIRATION = 7 * 24 * 60 * 60 * 1000L;

    /**
     * Token header key
     */
    public static final String TOKEN_HEADER = "Authorization";

    /**
     * Token前缀
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * 用户ID key
     */
    public static final String USER_ID_KEY = "userId";

    /**
     * 手机号 key
     */
    public static final String PHONE_KEY = "phone";
}
