package com.flashsale.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 给活动添加商品请求
 */
@Data
public class ActivityItemAddRequest {

    /**
     * 商品ID
     */
    @NotNull(message = "商品ID不能为空")
    private Long itemId;

    /**
     * 商品名称
     */
    @NotNull(message = "商品名称不能为空")
    private String itemName;

    /**
     * 商品图片
     */
    private String itemImage;

    /**
     * 原价
     */
    @NotNull(message = "原价不能为空")
    private BigDecimal originalPrice;

    /**
     * 秒杀价
     */
    @NotNull(message = "秒杀价不能为空")
    private BigDecimal seckillPrice;

    /**
     * 库存数量
     */
    @NotNull(message = "库存数量不能为空")
    private Integer stock;

    /**
     * 每人限购数量
     */
    private Integer limitPerUser;

    /**
     * 排序序号
     */
    private Integer sortOrder;
}
