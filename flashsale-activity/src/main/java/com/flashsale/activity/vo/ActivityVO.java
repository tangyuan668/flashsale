package com.flashsale.activity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动信息VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityVO {

    /**
     * 活动ID
     */
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
     * 状态描述
     */
    private String statusDesc;

    /**
     * 库存预热状态: 0-未预热, 1-已预热
     */
    private Integer preheatStatus;

    /**
     * 活动商品列表
     */
    private List<ActivityItemVO> items;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
