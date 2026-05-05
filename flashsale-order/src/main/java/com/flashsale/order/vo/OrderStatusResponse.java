package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单状态响应（用于轮询查询）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusResponse {

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 状态: PROCESSING-处理中, SUCCESS-成功, FAILED-失败
     */
    private String status;

    /**
     * 状态描述
     */
    private String message;

    /**
     * 订单ID（成功时返回）
     */
    private Long orderId;

    /**
     * 总金额（成功时返回）
     */
    private java.math.BigDecimal totalAmount;

    /**
     * 创建订单状态响应（处理中）
     */
    public static OrderStatusResponse processing(String orderNo) {
        return new OrderStatusResponse(orderNo, "PROCESSING", "处理中，请稍候", null, null);
    }

    /**
     * 创建订单状态响应（成功）
     */
    public static OrderStatusResponse success(String orderNo, Long orderId, java.math.BigDecimal totalAmount) {
        return new OrderStatusResponse(orderNo, "SUCCESS", "抢购成功", orderId, totalAmount);
    }

    /**
     * 创建订单状态响应（失败）
     */
    public static OrderStatusResponse failed(String orderNo, String message) {
        return new OrderStatusResponse(orderNo, "FAILED", message, null, null);
    }
}
