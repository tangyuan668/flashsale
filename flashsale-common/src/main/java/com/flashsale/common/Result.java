package com.flashsale.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一返回结果封装
 *
 * @param <T> 数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /**
     * 状态码
     */
    private Integer code;

    /**
     * 消息
     */
    private String message;

    /**
     * 数据
     */
    private T data;

    /**
     * 成功返回（无数据）
     */
    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    /**
     * 成功返回（有数据）
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 成功返回（自定义消息）
     */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    /**
     * 失败返回
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    /**
     * 失败返回（自定义状态码）
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败返回（使用错误码枚举）
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}
