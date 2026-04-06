package com.flashsale.activity.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动实体
 */
@Data
@TableName("activity")
public class Activity {

    /**
     * 活动ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 活动名称
     */
    private String name;

    /**
     * 活动描述
     */
    private String description;

    /**
     * 封面图URL
     */
    private String coverImage;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 状态: 0-待开始, 1-进行中, 2-已结束, 3-已取消
     */
    private Integer status;

    /**
     * 库存预热状态: 0-未预热, 1-已预热
     */
    private Integer preheatStatus;

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

    /**
     * 逻辑删除: 1-已删除, 0-未删除
     */
    @TableLogic
    private Integer deleted;
}
