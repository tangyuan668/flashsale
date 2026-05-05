package com.flashsale.common.dto;

import lombok.Data;

/**
 * 库存扣减请求
 */
@Data
public class StockDeductRequest {

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 商品ID
     */
    private Long itemId;

    /**
     * 扣减数量
     */
    private Integer quantity;

    /**
     * 用户ID
     */
    private Long userId;
}
