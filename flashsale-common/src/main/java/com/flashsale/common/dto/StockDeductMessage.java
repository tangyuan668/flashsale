package com.flashsale.common.dto;

import lombok.Data;

/**
 * 库存扣减消息
 */
@Data
public class StockDeductMessage {
    private String orderNo;
    private Long activityId;
    private Long itemId;
    private Integer quantity;
    private Long userId;
    private Boolean success;
}
