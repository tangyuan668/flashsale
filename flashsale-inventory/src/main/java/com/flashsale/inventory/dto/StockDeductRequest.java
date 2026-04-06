package com.flashsale.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 库存扣减请求
 */
@Data
public class StockDeductRequest {

    /**
     * 订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

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
     * 扣减数量
     */
    @NotNull(message = "扣减数量不能为空")
    @Positive(message = "扣减数量必须大于0")
    private Integer quantity;

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;
}
