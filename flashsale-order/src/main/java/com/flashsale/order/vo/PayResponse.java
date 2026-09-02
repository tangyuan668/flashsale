package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支付响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayResponse {

    private Boolean success;
    private String payNo;
    private String message;

    public static PayResponse success(String payNo) {
        return new PayResponse(true, payNo, "支付成功");
    }

    public static PayResponse fail(String message) {
        return new PayResponse(false, null, message);
    }
}
