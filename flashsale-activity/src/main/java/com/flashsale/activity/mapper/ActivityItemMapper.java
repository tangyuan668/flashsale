package com.flashsale.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.activity.entity.ActivityItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 活动商品Mapper
 */
@Mapper
public interface ActivityItemMapper extends BaseMapper<ActivityItem> {
}
