package com.flashsale.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付详情响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInfoResponse {

    private String orderNo;
    private String payNo;
    private BigDecimal amount;
    private String payMethod;
    private String payMethodDesc;
    private Integer status;
    private String statusDesc;
    private String transactionNo;
    private LocalDateTime payTime;
    private LocalDateTime createTime;
}
