package com.flashsale.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.order.entity.OrderInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单Mapper
 */
@Mapper
public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

    /**
     * 按日期统计订单量和销售额
     */
    @Select("SELECT DATE(create_time) AS date, COUNT(*) AS orderCount, COALESCE(SUM(CASE WHEN status = 1 THEN total_amount ELSE 0 END), 0) AS salesAmount " +
            "FROM order_info WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) GROUP BY DATE(create_time) ORDER BY date")
    List<Map<String, Object>> selectOrderTrend(int days);

    /**
     * 按活动统计销量排行
     */
    @Select("SELECT activity_id, COUNT(*) AS salesCount FROM order_info WHERE status = 1 GROUP BY activity_id ORDER BY salesCount DESC LIMIT #{limit}")
    List<Map<String, Object>> selectActivityRank(int limit);

    /**
     * 按订单状态统计分布
     */
    @Select("SELECT status, COUNT(*) AS count FROM order_info GROUP BY status")
    List<Map<String, Object>> selectStatusDistribution();

    /**
     * 按小时统计订单分布
     */
    @Select("SELECT HOUR(create_time) AS hour, COUNT(*) AS count FROM order_info WHERE create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY) GROUP BY HOUR(create_time) ORDER BY hour")
    List<Map<String, Object>> selectHourlyDistribution(@Param("days") int days);
}
