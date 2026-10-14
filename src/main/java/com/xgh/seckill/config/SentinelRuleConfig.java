package com.xgh.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 限流规则(代码方式加载)
 *
 * 【为什么要有这个类】
 * 只引依赖不配规则,Sentinel 是不生效的 —— 请求照单全收。
 * 规则必须显式加载(控制台推送或代码加载)才会真正拦截,
 * 因此这里在启动时把秒杀入口的 QPS 规则装进 FlowRuleManager。
 *
 * 【为什么用代码而不是控制台配置】
 *   1. 规则随代码走,可评审、可版本管理,不会出现"线上规则和仓库不一致";
 *   2. 集群部署时每个实例启动即生效,不依赖控制台连接;
 *   3. 演示环境没有 Sentinel 控制台也能工作。
 * 生产上更常见的做法是:控制台(或 Nacos)动态推送 + 本地兜底规则。
 *
 * 【为什么是 QPS 而不是并发线程数】
 * 秒杀入口的请求处理极短(校验 + Redis Lua + 发消息),瓶颈在 Redis 与下游 MQ,
 * 用 QPS 限流更贴近真实容量;超过阈值直接快速失败(不排队),
 * 让用户立刻拿到"人数过多,稍后重试",而不是把 Tomcat 线程堆满。
 *
 * @author 向国豪
 */
@Slf4j
@Configuration
public class SentinelRuleConfig {

    /** 秒杀入口资源名,与 SeckillServiceImpl#doSeckill 上的 @SentinelResource 保持一致 */
    public static final String RESOURCE_SECKILL_DO = "seckill:do";

    /** 单机 QPS 阈值:演示默认放宽,压测时把它调小才能观察到限流 */
    @Value("${seckill.limit.qps:2000}")
    private double qps;

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource(RESOURCE_SECKILL_DO);
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(qps);
        // 快速失败:超阈值直接拒绝,不做排队等待(排队会拖长 RT 并占用线程)
        seckillRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        seckillRule.setLimitApp("default");
        rules.add(seckillRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 限流规则已加载: {} → {} QPS(快速失败)", RESOURCE_SECKILL_DO, qps);
    }
}