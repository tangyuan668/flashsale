package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResponse {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 消息
     */
    private String message;

    public static SeckillResponse success(String orderNo) {
        return new SeckillResponse(true, orderNo, "下单成功");
    }

    public static SeckillResponse processing(String orderNo) {
        return new SeckillResponse(null, orderNo, "下单处理中");
    }

    public static SeckillResponse fail(String message) {
        return new SeckillResponse(false, null, message);
    }
}
