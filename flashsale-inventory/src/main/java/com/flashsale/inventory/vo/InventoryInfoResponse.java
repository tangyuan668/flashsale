package com.flashsale.inventory.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存信息响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryInfoResponse {

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 商品ID
     */
    private Long itemId;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 可用库存
     */
    private Integer availableStock;

    /**
     * 冻结库存
     */
    private Integer frozenStock;
}
