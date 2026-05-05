package com.flashsale.inventory.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存扣减响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockDeductResponse {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 剩余库存
     */
    private Integer remainingStock;

    /**
     * 消息
     */
    private String message;

    public static StockDeductResponse success(Integer remainingStock) {
        return new StockDeductResponse(true, remainingStock, "扣减成功");
    }

    public static StockDeductResponse fail(String message) {
        return new StockDeductResponse(false, 0, message);
    }
}
