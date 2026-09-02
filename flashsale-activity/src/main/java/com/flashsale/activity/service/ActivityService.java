package com.flashsale.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashsale.activity.dto.ActivityCreateRequest;
import com.flashsale.activity.dto.ActivityItemAddRequest;
import com.flashsale.activity.entity.Activity;
import com.flashsale.activity.entity.ActivityItem;
import com.flashsale.activity.mapper.ActivityItemMapper;
import com.flashsale.activity.mapper.ActivityMapper;
import com.flashsale.activity.vo.ActivityItemVO;
import com.flashsale.activity.vo.ActivityVO;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.dto.ActivityInvalidateMessage;
import com.flashsale.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

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
        // 判断当前时间，已在活动范围内则直接设为进行中
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(request.getStartTime()) && !now.isAfter(request.getEndTime())) {
            activity.setStatus(1); // 进行中
        } else {
            activity.setStatus(0); // 待开始
        }
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
     * 给活动添加商品
     */
    @Transactional(rollbackFor = Exception.class)
    public void addItem(Long activityId, ActivityItemAddRequest request) {
        // 1. 检查活动是否存在
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        // 2. 检查商品是否已存在
        LambdaQueryWrapper<ActivityItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ActivityItem::getActivityId, activityId)
                .eq(ActivityItem::getItemId, request.getItemId());
        if (activityItemMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.ITEM_ALREADY_EXISTS);
        }

        // 3. 插入商品记录
        ActivityItem item = new ActivityItem();
        item.setActivityId(activityId);
        item.setItemId(request.getItemId());
        item.setItemName(request.getItemName());
        item.setItemImage(request.getItemImage());
        item.setOriginalPrice(request.getOriginalPrice());
        item.setSeckillPrice(request.getSeckillPrice());
        item.setStock(request.getStock());
        item.setLimitPerUser(request.getLimitPerUser() != null ? request.getLimitPerUser() : 1);
        item.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        activityItemMapper.insert(item);

        // 4. 重置预热状态（新增了商品，需要重新预热）
        activity.setPreheatStatus(0);
        activityMapper.updateById(activity);

        log.info("活动添加商品成功: activityId={}, itemId={}", activityId, request.getItemId());
    }

    /**
     * 下架活动商品
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long activityId, Long itemId) {
        // 1. 检查活动是否存在
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        // 2. 检查商品是否存在于该活动中
        LambdaQueryWrapper<ActivityItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ActivityItem::getActivityId, activityId)
                .eq(ActivityItem::getItemId, itemId);
        ActivityItem item = activityItemMapper.selectOne(wrapper);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在于该活动中");
        }

        // 3. 清理 Redis 缓存
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + itemId;
        String itemKey = "activity:item:" + activityId + ":" + itemId;
        redisTemplate.delete(stockKey);
        redisTemplate.delete(itemKey);

        // 4. 逻辑删除商品记录
        activityItemMapper.deleteById(item.getId());

        log.info("下架活动商品成功: activityId={}, itemId={}", activityId, itemId);
    }

    /**
     * 删除活动
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteActivity(Long activityId) {
        // 1. 检查活动是否存在
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        // 2. 查询关联商品（用于清理 Redis）
        List<ActivityItem> items = activityItemMapper.selectList(
                new LambdaQueryWrapper<ActivityItem>()
                        .eq(ActivityItem::getActivityId, activityId)
        );

        // 3. 清理 Redis 中该活动的库存缓存
        for (ActivityItem item : items) {
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + item.getItemId();
            String itemKey = "activity:item:" + activityId + ":" + item.getItemId();
            redisTemplate.delete(stockKey);
            redisTemplate.delete(itemKey);
        }

        // 4. 逻辑删除活动商品
        activityItemMapper.delete(
                new LambdaQueryWrapper<ActivityItem>()
                        .eq(ActivityItem::getActivityId, activityId)
        );

        // 5. 逻辑删除活动
        activityMapper.deleteById(activityId);

        // 6. 发送活动失效消息，通知订单服务失效待支付订单
        try {
            ActivityInvalidateMessage invalidateMessage = new ActivityInvalidateMessage();
            invalidateMessage.setActivityId(activityId);
            invalidateMessage.setReason("delete");
            rocketMQTemplate.syncSend(MqConstant.ACTIVITY_INVALIDATE_TOPIC, invalidateMessage);
            log.info("发送活动失效消息成功: activityId={}", activityId);
        } catch (Exception e) {
            log.error("发送活动失效消息失败: activityId={}", activityId, e);
        }

        log.info("删除活动成功: activityId={}, 清理Redis缓存商品数={}", activityId, items.size());
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

            vo.setItems(buildItemVOs(items, activity.getId()));
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

        vo.setItems(buildItemVOs(items, activityId));

        return vo;
    }

    /**
     * 构建商品VO列表，用 Redis 实时库存覆盖 MySQL 初始值
     */
    private List<ActivityItemVO> buildItemVOs(List<ActivityItem> items, Long activityId) {
        return items.stream().map(item -> {
            ActivityItemVO itemVO = new ActivityItemVO();
            BeanUtils.copyProperties(item, itemVO);

            // 如果 Redis 中有实时库存，用 Redis 的值覆盖
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + item.getItemId();
            Object redisStock = redisTemplate.opsForValue().get(stockKey);
            if (redisStock != null) {
                itemVO.setStock(Integer.parseInt(redisStock.toString()));
            }

            return itemVO;
        }).collect(Collectors.toList());
    }

    /**
     * 库存预热（增量模式）
     * 已缓存的商品不会被覆盖，仅预热新增商品，支持活动进行中追加商品
     */
    @Transactional(rollbackFor = Exception.class)
    public void preheatStock(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
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

                int newCount = 0;
                int skipCount = 0;

                for (ActivityItem item : items) {
                    String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + item.getItemId();
                    String itemKey = "activity:item:" + activityId + ":" + item.getItemId();

                    // 增量预热：库存 key 不存在才写入，避免覆盖已扣减的库存
                    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(stockKey, item.getStock());
                    if (Boolean.TRUE.equals(isNew)) {
                        newCount++;
                        log.info("增量预热新商品: activityId={}, itemId={}, stock={}",
                                activityId, item.getItemId(), item.getStock());
                    } else {
                        skipCount++;
                        log.debug("商品库存已缓存，跳过库存预热: activityId={}, itemId={}",
                                activityId, item.getItemId());
                    }

                    // 商品元数据始终刷新（可能因 TTL 过期被删除）
                    Map<String, String> itemMeta = new HashMap<>();
                    itemMeta.put("itemId", String.valueOf(item.getItemId()));
                    itemMeta.put("itemName", item.getItemName());
                    itemMeta.put("itemImage", item.getItemImage());
                    itemMeta.put("originalPrice", item.getOriginalPrice().toPlainString());
                    itemMeta.put("seckillPrice", item.getSeckillPrice().toPlainString());
                    itemMeta.put("limitPerUser", String.valueOf(item.getLimitPerUser()));
                    itemMeta.put("stock", String.valueOf(item.getStock()));
                    redisTemplate.opsForHash().putAll(itemKey, itemMeta);
                    redisTemplate.expire(itemKey, 2, TimeUnit.HOURS);
                }

                // 更新预热状态
                activity.setPreheatStatus(1);
                activityMapper.updateById(activity);

                log.info("增量预热完成: activityId={}, 新增={}, 跳过={}", activityId, newCount, skipCount);
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
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, 1)
                        .eq(Activity::getStatus, 0)
                        .le(Activity::getStartTime, now)
                        .ge(Activity::getEndTime, now)
        );

        // 将进行中且已过结束时间的活动更新为已结束
        // 先查出即将过期的活动，用于发 MQ 通知订单服务
        List<Activity> expiringActivities = activityMapper.selectList(
                new LambdaQueryWrapper<Activity>()
                        .eq(Activity::getStatus, 1)
                        .lt(Activity::getEndTime, now)
        );
        if (!expiringActivities.isEmpty()) {
            activityMapper.update(null,
                    new LambdaUpdateWrapper<Activity>()
                            .set(Activity::getStatus, 2)
                            .eq(Activity::getStatus, 1)
                            .lt(Activity::getEndTime, now)
            );

            // 通知订单服务失效这些活动的待支付订单
            for (Activity expiring : expiringActivities) {
                try {
                    ActivityInvalidateMessage invalidateMessage = new ActivityInvalidateMessage();
                    invalidateMessage.setActivityId(expiring.getId());
                    invalidateMessage.setReason("expired");
                    rocketMQTemplate.syncSend(MqConstant.ACTIVITY_INVALIDATE_TOPIC, invalidateMessage);
                    log.info("发送活动过期失效消息: activityId={}", expiring.getId());
                } catch (Exception e) {
                    log.error("发送活动过期失效消息失败: activityId={}", expiring.getId(), e);
                }
            }
        }

        log.debug("活动状态检查任务完成");
    }

    /**
     * 获取活动总数
     */
    public Long getActivityCount() {
        return activityMapper.selectCount(new LambdaQueryWrapper<>());
    }

    @PostConstruct
    public void init() {
        log.info("ActivityService 初始化完成");
    }
}
