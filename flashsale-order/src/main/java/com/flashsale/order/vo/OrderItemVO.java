package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单明细VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemVO {

    /**
     * ID
     */
    private Long id;

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
     * 单价
     */
    private BigDecimal price;

    /**
     * 数量
     */
    private Integer quantity;

    /**
     * 小计金额
     */
    private BigDecimal totalAmount;
}
