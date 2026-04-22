package com.flashsale.common.dto;

import lombok.Data;

/**
 * 订单创建消息
 */
@Data
public class OrderCreateMessage {
    private String orderNo;
    private Long userId;
    private Long activityId;
    private Long itemId;
    private Integer quantity;
}
