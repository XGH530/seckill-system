package com.xgh.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgh.seckill.common.constant.SeckillConstants;
import com.xgh.seckill.common.exception.BizException;
import com.xgh.seckill.common.result.ResultCode;
import com.xgh.seckill.component.SeckillMetrics;
import com.xgh.seckill.entity.Goods;
import com.xgh.seckill.entity.SeckillActivity;
import com.xgh.seckill.entity.SeckillOrder;
import com.xgh.seckill.entity.User;
import com.xgh.seckill.mapper.GoodsMapper;
import com.xgh.seckill.mapper.SeckillActivityMapper;
import com.xgh.seckill.mapper.SeckillOrderMapper;
import com.xgh.seckill.mapper.UserMapper;
import com.xgh.seckill.service.AdminManageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 后台管理控制台服务实现
 *
 * 【重要说明:演示环境的管理端鉴权】
 * 本项目是单机演示系统,管理端接口(/admin/**)未接入鉴权,便于面试演示与压测脚本直接调用。
 * 真实生产必须补上管理员鉴权(Spring Security @PreAuthorize("hasRole('ADMIN')") 或网关统一鉴权),
 * 这一点在 AdminController 的类注释里也有说明。
 *
 * @author 向国豪
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminManageServiceImpl implements AdminManageService {

    private final GoodsMapper goodsMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DataSource dataSource;

    @Qualifier("rollbackStockScript")
    private final DefaultRedisScript<Long> rollbackStockScript;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String activityStatusText(Integer status) {
        if (status == null) return "-";
        return switch (status) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已结束";
            default -> "未知";
        };
    }

    private static String orderStatusText(Integer status) {
        if (status == null) return "-";
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已退款";
            default -> "未知";
        };
    }

    private static Object nv(Object v) {
        return v == null ? "" : v;
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<SeckillActivity> activities = activityMapper.selectList(null);
        result.put("activityCount", activities.size());
        result.put("runningCount", activities.stream().filter(a -> Objects.equals(a.getStatus(), 1)).count());
        result.put("goodsCount", goodsMapper.selectCount(null));
        result.put("userCount", userMapper.selectCount(null));
        result.put("orderCount", orderMapper.selectCount(null));
        result.put("paidCount", orderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getStatus, SeckillConstants.ORDER_STATUS_PAID)));
        result.put("unpaidCount", orderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getStatus, SeckillConstants.ORDER_STATUS_UNPAID)));
        result.put("canceledCount", orderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getStatus, SeckillConstants.ORDER_STATUS_CANCELED)));
        result.put("todayOrderCount", orderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>().ge(SeckillOrder::getCreateTime, LocalDate.now().atStartOfDay())));

        // ---------- 库存汇总 ----------
        int initialStock = 0;
        int dbStock = 0;
        int redisStock = 0;
        int diffActivities = 0;
        int notWarmed = 0;
        for (SeckillActivity activity : activities) {
            initialStock += activity.getInitialStock() == null ? 0 : activity.getInitialStock();
            dbStock += activity.getSeckillStock() == null ? 0 : activity.getSeckillStock();
            Object redisValue = redisTemplate.opsForValue().get(SeckillConstants.STOCK_KEY + activity.getId());
            if (redisValue == null) {
                // Redis 里没有库存键 = 该活动还没预热,对账时要单独区分出来,不能当成"一致"
                notWarmed++;
                continue;
            }
            int rs = Integer.parseInt(String.valueOf(redisValue));
            redisStock += rs;
            if (rs != (activity.getSeckillStock() == null ? 0 : activity.getSeckillStock())) {
                diffActivities++;
            }
        }
        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("initialStock", initialStock);
        stock.put("dbStock", dbStock);
        stock.put("redisStock", redisStock);
        stock.put("soldStock", initialStock - dbStock);
        stock.put("diffActivities", diffActivities);
        stock.put("notWarmed", notWarmed);
        // 只有"全部预热 + DB 与 Redis 完全相同"才算一致
        stock.put("consistent", notWarmed == 0 && diffActivities == 0);
        result.put("stock", stock);

        // ---------- 实时业务指标 ----------
        result.put("metrics", SeckillMetrics.getInstance().snapshot());
        result.put("serverTime", LocalDateTime.now().format(DATE_TIME));
        return result;
    }

    @Override
    public List<Map<String, Object>> activities() {
        List<SeckillActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().orderByAsc(SeckillActivity::getId));
        List<Map<String, Object>> list = new ArrayList<>();

        for (SeckillActivity activity : activities) {
            Goods goods = activity.getGoodsId() == null ? null : goodsMapper.selectById(activity.getGoodsId());

            Long validOrders = orderMapper.selectCount(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getActivityId, activity.getId())
                    .in(SeckillOrder::getStatus, SeckillConstants.ORDER_STATUS_UNPAID, SeckillConstants.ORDER_STATUS_PAID));
            Long paidOrders = orderMapper.selectCount(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getActivityId, activity.getId())
                    .eq(SeckillOrder::getStatus, SeckillConstants.ORDER_STATUS_PAID));

            Object redisValue = redisTemplate.opsForValue().get(SeckillConstants.STOCK_KEY + activity.getId());
            Integer redisStock = redisValue == null ? null : Integer.valueOf(String.valueOf(redisValue));

            int dbStock = activity.getSeckillStock() == null ? 0 : activity.getSeckillStock();
            int initialStock = activity.getInitialStock() == null ? 0 : activity.getInitialStock();
            int deducted = initialStock - dbStock;
            Integer redisDeducted = redisStock == null ? null : initialStock - redisStock;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", activity.getId());
            m.put("goodsId", activity.getGoodsId());
            m.put("goodsName", goods == null ? "-" : goods.getGoodsName());
            m.put("goodsTitle", goods == null ? "" : nv(goods.getGoodsTitle()));
            m.put("goodsPrice", goods == null ? BigDecimal.ZERO : goods.getGoodsPrice());
            m.put("seckillPrice", activity.getSeckillPrice());
            m.put("initialStock", initialStock);
            m.put("dbStock", dbStock);
            m.put("redisStock", redisStock);
            m.put("deducted", deducted);
            m.put("validOrders", validOrders);
            m.put("paidOrders", paidOrders);
            m.put("status", activity.getStatus());
            m.put("statusText", activityStatusText(activity.getStatus()));
            m.put("startTime", activity.getStartTime() == null ? "" : activity.getStartTime().format(DATE_TIME));
            m.put("endTime", activity.getEndTime() == null ? "" : activity.getEndTime().format(DATE_TIME));
            // 对账:DB 已扣减数 == 有效订单数,且 Redis 已扣减数与之相同
            boolean consistent = deducted == validOrders.intValue()
                    && (redisDeducted == null || redisDeducted == validOrders.intValue());
            m.put("consistent", consistent);
            list.add(m);
        }
        return list;
    }

    @Override
    public void updateActivityStatus(Long activityId, Integer status) {
        if (status == null || status < 0 || status > 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "活动状态只能是 0(未开始) / 1(进行中) / 2(已结束)");
        }
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "活动不存在");
        }
        SeckillActivity update = new SeckillActivity();
        update.setId(activityId);
        update.setStatus(status);
        update.setVersion(activity.getVersion());
        activityMapper.updateById(update);
        // 活动信息有本地缓存与 Redis 缓存,状态变更后清一次,避免读到旧状态
        try {
            redisTemplate.delete(SeckillConstants.ACTIVITY_KEY + activityId);
        } catch (Exception e) {
            log.warn("清除活动缓存失败 activityId={}", activityId, e);
        }
        log.info("活动 {} 状态调整为 {}", activityId, status);
    }
    @Override
    public Map<String, Object> orders(long current, long size, Integer status, Long activityId) {
        LambdaQueryWrapper<SeckillOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(SeckillOrder::getStatus, status);
        }
        if (activityId != null) {
            wrapper.eq(SeckillOrder::getActivityId, activityId);
        }
        wrapper.orderByDesc(SeckillOrder::getId);

        Page<SeckillOrder> page = orderMapper.selectPage(new Page<>(current, size), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (SeckillOrder order : page.getRecords()) {
            User user = order.getUserId() == null ? null : userMapper.selectById(order.getUserId());
            Goods goods = order.getGoodsId() == null ? null : goodsMapper.selectById(order.getGoodsId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", order.getId());
            m.put("orderNo", order.getOrderNo());
            m.put("userId", order.getUserId());
            m.put("username", user == null ? "-" : user.getUsername());
            m.put("nickname", user == null ? "-" : nv(user.getNickname()));
            m.put("activityId", order.getActivityId());
            m.put("goodsId", order.getGoodsId());
            m.put("goodsName", goods == null ? "-" : goods.getGoodsName());
            m.put("seckillPrice", order.getSeckillPrice());
            m.put("status", order.getStatus());
            m.put("statusText", orderStatusText(order.getStatus()));
            m.put("payTime", order.getPayTime() == null ? "" : order.getPayTime().format(DATE_TIME));
            m.put("expireTime", order.getExpireTime() == null ? "" : order.getExpireTime().format(DATE_TIME));
            m.put("createTime", order.getCreateTime() == null ? "" : order.getCreateTime().format(DATE_TIME));
            records.add(m);
        }
        return pageResult(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(String orderNo) {
        SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getOrderNo, orderNo).last("LIMIT 1"));
        if (order == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单不存在");
        }

        // 带 status = 0 条件更新,保证与 MQ 消费者/定时任务并发时只生效一次
        int rows = orderMapper.markCanceled(orderNo);
        if (rows == 0) {
            log.info("订单 {} 已被其它链路处理,跳过关单", orderNo);
            return false;
        }

        // ① 回补 DB 库存(带 stock + n <= initial_stock 保护,防止回补过量)
        activityMapper.rollbackStock(order.getActivityId(), 1);

        // ② 回补 Redis 库存(同样带上限保护)
        SeckillActivity activity = activityMapper.selectById(order.getActivityId());
        if (activity != null) {
            redisTemplate.execute(rollbackStockScript,
                    Collections.singletonList(SeckillConstants.STOCK_KEY + order.getActivityId()),
                    String.valueOf(1),
                    String.valueOf(activity.getInitialStock()));
        }

        // ③ 释放"一人一单"占位,允许该用户重新参与
        redisTemplate.delete(SeckillConstants.USER_ORDER_KEY
                + order.getActivityId() + ":" + order.getUserId());

        log.info("管理员关单成功 orderNo={} activityId={}", orderNo, order.getActivityId());
        return true;
    }

    @Override
    public Map<String, Object> users(long current, long size, String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword)
                    .or().like(User::getPhone, keyword));
        }
        wrapper.orderByDesc(User::getId);

        Page<User> page = userMapper.selectPage(new Page<>(current, size), wrapper);
        List<Map<String, Object>> records = new ArrayList<>();
        for (User user : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", user.getId());
            m.put("username", user.getUsername());
            m.put("nickname", nv(user.getNickname()));
            m.put("phone", nv(user.getPhone()));
            m.put("createTime", user.getCreateTime() == null ? "" : user.getCreateTime().format(DATE_TIME));
            // 订单数:管理端看用户价值的一个小指标
            m.put("orderCount", orderMapper.selectCount(
                    new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getUserId, user.getId())));
            records.add(m);
        }
        return pageResult(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Override
    public List<Map<String, Object>> goods() {
        List<Goods> goodsList = goodsMapper.selectList(
                new LambdaQueryWrapper<Goods>().orderByAsc(Goods::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Goods goods : goodsList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", goods.getId());
            m.put("goodsName", goods.getGoodsName());
            m.put("goodsTitle", nv(goods.getGoodsTitle()));
            m.put("goodsImg", nv(goods.getGoodsImg()));
            m.put("goodsDetail", nv(goods.getGoodsDetail()));
            m.put("goodsPrice", goods.getGoodsPrice());
            m.put("goodsStock", goods.getGoodsStock());
            m.put("activityCount", activityMapper.selectCount(
                    new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getGoodsId, goods.getId())));
            m.put("createTime", goods.getCreateTime() == null ? "" : goods.getCreateTime().format(DATE_TIME));
            list.add(m);
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createGoods(Map<String, Object> body) {
        String name = str(body.get("goodsName"));
        if (name == null || name.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品名称不能为空");
        }
        Goods goods = new Goods();
        goods.setGoodsName(name);
        goods.setGoodsTitle(str(body.get("goodsTitle")));
        goods.setGoodsImg(str(body.get("goodsImg")));
        goods.setGoodsDetail(str(body.get("goodsDetail")));
        goods.setGoodsPrice(decimal(body.get("goodsPrice")));
        goods.setGoodsStock(integer(body.get("goodsStock")));
        goods.setDeleted(0);
        goodsMapper.insert(goods);
        log.info("新增商品 id={} name={}", goods.getId(), goods.getGoodsName());
        return goods.getId();
    }

    @Override
    public void updateGoods(Long id, Map<String, Object> body) {
        Goods exists = goodsMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品不存在");
        }
        Goods goods = new Goods();
        goods.setId(id);
        if (body.containsKey("goodsName")) goods.setGoodsName(str(body.get("goodsName")));
        if (body.containsKey("goodsTitle")) goods.setGoodsTitle(str(body.get("goodsTitle")));
        if (body.containsKey("goodsImg")) goods.setGoodsImg(str(body.get("goodsImg")));
        if (body.containsKey("goodsDetail")) goods.setGoodsDetail(str(body.get("goodsDetail")));
        if (body.containsKey("goodsPrice")) goods.setGoodsPrice(decimal(body.get("goodsPrice")));
        if (body.containsKey("goodsStock")) goods.setGoodsStock(integer(body.get("goodsStock")));
        goodsMapper.updateById(goods);
        log.info("修改商品 id={}", id);
    }

    @Override
    public void deleteGoods(Long id) {
        Goods exists = goodsMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品不存在");
        }
        Long running = activityMapper.selectCount(new LambdaQueryWrapper<SeckillActivity>()
                .eq(SeckillActivity::getGoodsId, id)
                .eq(SeckillActivity::getStatus, 1));
        if (running != null && running > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "该商品还有进行中的秒杀活动,请先结束活动");
        }
        goodsMapper.deleteById(id);
        log.info("删除商品 id={}", id);
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> db = new LinkedHashMap<>();
        long dbStart = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            db.put("ok", true);
            db.put("product", meta.getDatabaseProductName());
            db.put("version", meta.getDatabaseProductVersion());
        } catch (Exception e) {
            db.put("ok", false);
            db.put("error", e.getMessage());
        }
        db.put("latencyMs", (System.nanoTime() - dbStart) / 1_000_000);
        result.put("db", db);

        Map<String, Object> redis = new LinkedHashMap<>();
        long redisStart = System.nanoTime();
        try {
            redis.put("ok", redisTemplate.hasKey("seckill:health:probe") != null);
        } catch (Exception e) {
            redis.put("ok", false);
            redis.put("error", e.getMessage());
        }
        redis.put("latencyMs", (System.nanoTime() - redisStart) / 1_000_000);
        result.put("redis", redis);

        Runtime runtime = Runtime.getRuntime();
        long mb = 1024 * 1024;
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("usedMb", (runtime.totalMemory() - runtime.freeMemory()) / mb);
        jvm.put("committedMb", runtime.totalMemory() / mb);
        jvm.put("maxMb", runtime.maxMemory() / mb);
        jvm.put("processors", runtime.availableProcessors());
        jvm.put("javaVersion", System.getProperty("java.version"));
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        jvm.put("uptimeSeconds", uptimeMs / 1000);
        jvm.put("uptimeText", formatUptime(uptimeMs));
        result.put("jvm", jvm);
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("serverTime", LocalDateTime.now().format(DATE_TIME));
        return result;
    }

    // ==================== 私有工具 ====================

    private static Map<String, Object> pageResult(long current, long size, long total, List<Map<String, Object>> records) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("current", current);
        page.put("size", size);
        page.put("total", total);
        page.put("pages", size == 0 ? 0 : (total + size - 1) / size);
        page.put("records", records);
        return page;
    }

    private static String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (days > 0) return days + " 天 " + hours + " 小时";
        if (hours > 0) return hours + " 小时 " + minutes + " 分";
        return minutes + " 分";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    private static Integer integer(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return 0;
        return Integer.valueOf(String.valueOf(value));
    }
}