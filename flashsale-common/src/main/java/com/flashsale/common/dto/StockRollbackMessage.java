package com.flashsale.common.dto;

import lombok.Data;

/**
 * 库存回滚消息
 */
@Data
public class StockRollbackMessage {
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
     * 回滚数量
     */
    private Integer quantity;

    /**
     * 用户ID
     */
    private Long userId;
}
