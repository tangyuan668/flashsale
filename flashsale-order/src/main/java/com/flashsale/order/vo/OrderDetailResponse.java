package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailResponse {

    /**
     * 订单ID
     */
    private Long id;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 订单总金额
     */
    private BigDecimal totalAmount;

    /**
     * 订单状态: 0-待支付, 1-已支付, 2-已取消, 3-已超时
     */
    private Integer status;

    /**
     * 状态描述
     */
    private String statusDesc;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 取消时间
     */
    private LocalDateTime cancelTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 订单明细
     */
    private List<OrderItemVO> items;
}
