package com.flashsale.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 支付请求
 */
@Data
public class PayRequest {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotBlank(message = "支付方式不能为空")
    @Pattern(regexp = "^(alipay|wechat|mock)$", message = "不支持的支付方式")
    private String payMethod;
}
