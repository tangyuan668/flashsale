package com.flashsale.activity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 活动商品VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityItemVO {

    /**
     * ID
     */
    private Long id;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 商品ID
     */
    private Long itemId;

    /**
     * 商品名称
     */
    private String itemName;

    /**
     * 商品图片
     */
    private String itemImage;

    /**
     * 原价
     */
    private BigDecimal originalPrice;

    /**
     * 秒杀价
     */
    private BigDecimal seckillPrice;

    /**
     * 库存数量
     */
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
