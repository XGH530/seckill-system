package com.xgh.seckill.job;

import com.xgh.seckill.common.constant.SeckillConstants;
import com.xgh.seckill.entity.SeckillActivity;
import com.xgh.seckill.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示环境活动窗口自动续期（仅 demo profile 生效）
 *
 * 【为什么需要它】
 * demo 模式用 H2 内存库，活动时间在应用启动那一刻按「启动时间 ±N 小时」算好写死。
 * 应用连续运行超过 24 小时后，所有活动的 end_time 都已过期，
 * 秒杀接口会一律返回 601「秒杀活动已结束」——演示环境因此"自然失效"。
 *
 * 【设计取舍：为什么用定时任务，而不是启动时算个更长的窗口？】
 * 1. 窗口设太长（比如 10 年）会让"活动进行中/未开始/已结束"三个状态
 *    在界面上永远只显示一个，反而演示不了状态流转；
 * 2. 定时续期能让窗口始终是「1 小时前开始、24 小时后结束」的自然状态，
 *    前端倒计时、活动状态标签都能正常展示；
 * 3. 逻辑隔离在 demo profile，生产环境（mysql profile）不会加载这个 Bean，
 *    不存在"偷偷改生产活动时间"的风险——这也是它必须独立成类的原因。
 *
 * 【注意】这里只滚动"时间窗口"，不动库存与订单。
 * 库存重置/占位清理仍走运维接口 /admin/activity/{id}/reset-stock，
 * 两件事语义不同，不能合并。
 *
 * @author 向国豪
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoActivityRefreshJob {

    private final SeckillActivityMapper activityMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 每次续期后，活动窗口 = [now - 1 小时, now + 24 小时] */
    private static final int START_OFFSET_HOURS = -1;
    private static final int END_OFFSET_HOURS = 24;

    /**
     * 每 2 分钟检查一次活动窗口是否已过期，过期则整体后移。
     *
     * 【为什么是 2 分钟】演示场景对时效不敏感（窗口按天计），
     * 2 分钟足够保证"哪怕刚过期也会很快被救回来"，同时开销可忽略（一次全表扫描，活动只有几条）。
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    public void refreshExpiredActivities() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> activities = activityMapper.selectList(null);
        int refreshed = 0;

        for (SeckillActivity activity : activities) {
            if (activity.getEndTime() == null || !now.isAfter(activity.getEndTime())) {
                continue; // 窗口仍有效，不动
            }

            LocalDateTime newStart = now.plusHours(START_OFFSET_HOURS);
            LocalDateTime newEnd = now.plusHours(END_OFFSET_HOURS);

            activity.setStartTime(newStart);
            activity.setEndTime(newEnd);
            activityMapper.updateById(activity);

            // 活动缓存里存的是整个对象（含时间字段），必须删掉，
            // 否则下一次秒杀请求仍会读到「已过期」的旧缓存对象 —— 这正是线上 601 的成因。
            redisTemplate.delete(SeckillConstants.ACTIVITY_KEY + activity.getId());
            // 空值缓存一并清理，避免极端情况下被空的否定缓存挡住
            redisTemplate.delete(SeckillConstants.NULL_CACHE_KEY + "activity:" + activity.getId());

            refreshed++;
            log.info("[演示环境] 活动窗口已自动续期 activityId={} 新窗口={} ~ {}",
                    activity.getId(), newStart, newEnd);
        }

        if (refreshed > 0) {
            log.info("[演示环境] 本轮共续期 {} 个活动的窗口", refreshed);
        }
    }
}
