package com.flashsale.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.inventory.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表 Mapper
 */
@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /**
     * 查询需要重试的消息
     * @param now 当前时间
     * @param limit 限制数量
     * @return 待重试的消息列表
     */
    @Select("SELECT * FROM local_message " +
            "WHERE status = 0 " +
            "AND next_retry_time <= #{now} " +
            "AND retry_count < max_retry " +
            "ORDER BY id ASC " +
            "LIMIT #{limit}")
    List<LocalMessage> selectPendingMessages(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
