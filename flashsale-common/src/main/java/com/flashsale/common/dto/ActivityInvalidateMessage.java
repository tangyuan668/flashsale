package com.flashsale.common.dto;

import lombok.Data;

/**
 * 活动失效消息（活动删除/过期时通知订单服务）
 */
@Data
public class ActivityInvalidateMessage {

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 失效原因: delete-活动删除, expired-活动过期
     */
    private String reason;
}
