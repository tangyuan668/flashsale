package com.flashsale.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.activity.dto.ActivityCreateRequest;
import com.flashsale.activity.entity.Activity;
import com.flashsale.activity.entity.ActivityItem;
import com.flashsale.activity.mapper.ActivityItemMapper;
import com.flashsale.activity.mapper.ActivityMapper;
import com.flashsale.activity.vo.ActivityItemVO;
import com.flashsale.activity.vo.ActivityVO;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 活动服务
 */
@Slf4j
@Service
public class ActivityService {

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private ActivityItemMapper activityItemMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 创建活动
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createActivity(ActivityCreateRequest request) {
        // 验证时间
        if (request.getEndTime().isBefore(request.getStartTime())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "结束时间不能早于开始时间");
        }

        // 创建活动
        Activity activity = new Activity();
        activity.setName(request.getName());
        activity.setDescription(request.getDescription());
        activity.setCoverImage(request.getCoverImage());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setStatus(0); // 待开始
        activity.setPreheatStatus(0); // 未预热

        activityMapper.insert(activity);

        // 创建活动商品
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (ActivityCreateRequest.ActivityItemRequest itemRequest : request.getItems()) {
                ActivityItem item = new ActivityItem();
                item.setActivityId(activity.getId());
                item.setItemId(itemRequest.getItemId());
                item.setItemName(itemRequest.getItemName());
                item.setItemImage(itemRequest.getItemImage());
                item.setOriginalPrice(BigDecimal.valueOf(itemRequest.getOriginalPrice()));
                item.setSeckillPrice(BigDecimal.valueOf(itemRequest.getSeckillPrice()));
                item.setStock(itemRequest.getStock());
                item.setLimitPerUser(itemRequest.getLimitPerUser() != null ? itemRequest.getLimitPerUser() : 1);
                item.setSortOrder(itemRequest.getSortOrder() != null ? itemRequest.getSortOrder() : 0);

                activityItemMapper.insert(item);
            }
        }

        log.info("创建活动成功: activityId={}", activity.getId());
        return activity.getId();
    }

    /**
     * 获取活动列表
     */
    public List<ActivityVO> getActivityList() {
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Activity::getStatus, 1) // 进行中的活动
                .orderByAsc(Activity::getStartTime)
                .last("LIMIT 10"); // 限制返回数量

        List<Activity> activities = activityMapper.selectList(wrapper);

        return activities.stream().map(activity -> {
            ActivityVO vo = new ActivityVO();
            BeanUtils.copyProperties(activity, vo);
            vo.setStatusDesc(getStatusDesc(activity.getStatus()));

            // 查询活动商品
            List<ActivityItem> items = activityItemMapper.selectList(
                    new LambdaQueryWrapper<ActivityItem>()
                            .eq(ActivityItem::getActivityId, activity.getId())
            );

            List<ActivityItemVO> itemVOs = items.stream().map(item -> {
                ActivityItemVO itemVO = new ActivityItemVO();
                BeanUtils.copyProperties(item, itemVO);
                return itemVO;
            }).collect(Collectors.toList());

            vo.setItems(itemVOs);
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 获取活动详情
     */
    public ActivityVO getActivityDetail(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        ActivityVO vo = new ActivityVO();
        BeanUtils.copyProperties(activity, vo);
        vo.setStatusDesc(getStatusDesc(activity.getStatus()));

        // 查询活动商品
        List<ActivityItem> items = activityItemMapper.selectList(
                new LambdaQueryWrapper<ActivityItem>()
                        .eq(ActivityItem::getActivityId, activityId)
                        .orderByAsc(ActivityItem::getSortOrder)
        );

        List<ActivityItemVO> itemVOs = items.stream().map(item -> {
            ActivityItemVO itemVO = new ActivityItemVO();
            BeanUtils.copyProperties(item, itemVO);
            return itemVO;
        }).collect(Collectors.toList());

        vo.setItems(itemVOs);

        return vo;
    }

    /**
     * 库存预热
     */
    @Transactional(rollbackFor = Exception.class)
    public void preheatStock(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        if (activity.getPreheatStatus() == 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存已预热");
        }

        // 获取分布式锁
        RLock lock = redissonClient.getLock(RedisConstant.STOCK_PREHEAT_LOCK + activityId);
        try {
            if (lock.tryLock(10, 30, java.util.concurrent.TimeUnit.SECONDS)) {
                // 查询活动商品
                List<ActivityItem> items = activityItemMapper.selectList(
                        new LambdaQueryWrapper<ActivityItem>()
                                .eq(ActivityItem::getActivityId, activityId)
                );

                // 预热库存到Redis
                for (ActivityItem item : items) {
                    String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + item.getItemId();
                    redisTemplate.opsForValue().set(stockKey, item.getStock());

                    log.info("库存预热: activityId={}, itemId={}, stock={}",
                            activityId, item.getItemId(), item.getStock());
                }

                // 更新预热状态
                activity.setPreheatStatus(1);
                activityMapper.updateById(activity);

                log.info("库存预热成功: activityId={}", activityId);
            } else {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存预热失败");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 更新活动状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateActivityStatus(Long activityId, Integer status) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        // 获取分布式锁
        RLock lock = redissonClient.getLock(RedisConstant.ACTIVITY_STATUS_LOCK + activityId);
        try {
            if (lock.tryLock(10, 30, java.util.concurrent.TimeUnit.SECONDS)) {
                activity.setStatus(status);
                activityMapper.updateById(activity);

                log.info("活动状态更新: activityId={}, status={}", activityId, status);
            } else {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "状态更新失败");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取状态描述
     */
    private String getStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "待开始";
            case 1 -> "进行中";
            case 2 -> "已结束";
            case 3 -> "已取消";
            default -> "未知";
        };
    }

    /**
     * 定时任务：自动更新活动状态（每分钟执行）
     */
    @Scheduled(cron = "0 * * * * ?")
    public void autoUpdateActivityStatus() {
        log.debug("开始执行活动状态检查任务");

        LocalDateTime now = LocalDateTime.now();

        // 将待开始且已到开始时间的活动更新为进行中
        activityMapper.update(null,
                new com.baomidou.mybatisplus.core.update.LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, 1)
                        .eq(Activity::getStatus, 0)
                        .le(Activity::getStartTime, now)
                        .ge(Activity::getEndTime, now)
        );

        // 将进行中且已过结束时间的活动更新为已结束
        activityMapper.update(null,
                new com.baomidou.mybatisplus.core.update.LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, 2)
                        .eq(Activity::getStatus, 1)
                        .lt(Activity::getEndTime, now)
        );

        log.debug("活动状态检查任务完成");
    }

    @PostConstruct
    public void init() {
        log.info("ActivityService 初始化完成");
    }
}
