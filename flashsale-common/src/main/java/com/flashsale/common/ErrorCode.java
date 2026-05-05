package com.flashsale.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误码 1xxx
    SUCCESS(200, "成功"),
    SYSTEM_ERROR(1000, "系统错误"),
    PARAM_ERROR(1001, "参数错误"),
    NOT_FOUND(1002, "资源不存在"),

    // 用户相关 2xxx
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_ALREADY_EXISTS(2002, "用户已存在"),
    PASSWORD_ERROR(2003, "密码错误"),
    TOKEN_INVALID(2004, "Token无效"),
    TOKEN_EXPIRED(2005, "Token已过期"),
    USER_NOT_LOGIN(2006, "用户未登录"),

    // 活动相关 3xxx
    ACTIVITY_NOT_FOUND(3001, "活动不存在"),
    ACTIVITY_NOT_STARTED(3002, "活动未开始"),
    ACTIVITY_ENDED(3003, "活动已结束"),
    ACTIVITY_NOT_AVAILABLE(3004, "活动不可用"),

    // 库存相关 4xxx
    STOCK_NOT_ENOUGH(4001, "库存不足"),
    STOCK_Deduct_FAILED(4002, "扣减库存失败"),

    // 订单相关 5xxx
    ORDER_NOT_FOUND(5001, "订单不存在"),
    ORDER_CREATE_FAILED(5002, "创建订单失败"),
    ORDER_ALREADY_EXISTS(5003, "订单已存在"),
    REPEAT_PURCHASE(5004, "重复购买"),

    // 限流相关 6xxx
    RATE_LIMIT_EXCEEDED(6001, "请求过于频繁，请稍后再试"),
    ANTI_BRUSH_BLOCKED(6002, "触发防刷限制"),

    // RocketMQ相关 7xxx
    MQ_SEND_FAILED(7001, "消息发送失败");

    private final Integer code;
    private final String message;
}
