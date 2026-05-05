package com.flashsale.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 秒杀请求
 */
@Data
public class SeckillRequest {

    /**
     * 活动ID
     */
    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    /**
     * 商品ID
     */
    @NotNull(message = "商品ID不能为空")
    private Long itemId;

    /**
     * 购买数量
     */
    @NotNull(message = "购买数量不能为空")
    @Positive(message = "购买数量必须大于0")
    private Integer quantity;
}
