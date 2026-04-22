package com.flashsale.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表实体
 * 用于保障库存扣减和 MQ 消息的最终一致性
 */
@Data
@TableName("local_message")
public class LocalMessage {

    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务ID（订单号）
     */
    private String businessNo;

    /**
     * 消息主题
     */
    private String topic;

    /**
     * 消息内容（JSON）
     */
    private String messageBody;

    /**
     * 消息状态: 0-待发送, 1-已发送, 2-发送失败, 3-发送中(防止并发重复发送的临时状态)
     */
    private Integer status;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetry;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryTime;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
