-- =====================================================================
-- 分布式高并发秒杀系统 - 数据库初始化脚本
-- 数据库：MySQL 8.0（兼容 5.7，但推荐 8.0 以支持窗口函数等特性）
-- 作者：向国豪
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `seckill`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `seckill`;

-- ---------------------------------------------------------------------
-- 1. 用户表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）',
    `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `nickname`    VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 2. 商品表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_goods`;
CREATE TABLE `t_goods`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `goods_name`    VARCHAR(128)   NOT NULL COMMENT '商品名称',
    `goods_title`   VARCHAR(255)            DEFAULT NULL COMMENT '商品标题',
    `goods_img`     VARCHAR(255)            DEFAULT NULL COMMENT '商品图片',
    `goods_detail`  TEXT                    DEFAULT NULL COMMENT '商品详情',
    `goods_price`   DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '原价',
    `goods_stock`   INT            NOT NULL DEFAULT 0 COMMENT '库存',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品表';

-- ---------------------------------------------------------------------
-- 3. 秒杀活动表
--    注意：秒杀库存单独一张表，与商品库存解耦
--    【考点】为什么单独建表？—— 秒杀库存变更极其频繁，
--           独立后可单独做分表/加缓存，避免影响商品主表的常规读写
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_seckill_activity`;
CREATE TABLE `t_seckill_activity`
(
    `id`               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    `goods_id`         BIGINT         NOT NULL COMMENT '关联商品ID',
    `seckill_price`    DECIMAL(10, 2) NOT NULL COMMENT '秒杀价',
    `seckill_stock`    INT            NOT NULL COMMENT '秒杀库存（剩余）',
    `initial_stock`    INT            NOT NULL COMMENT '初始库存（用于对账/恢复）',
    `start_time`       DATETIME       NOT NULL COMMENT '开始时间',
    `end_time`         DATETIME       NOT NULL COMMENT '结束时间',
    -- 【考点】version 字段实现乐观锁，防止并发超卖
    `version`          INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `status`           TINYINT        NOT NULL DEFAULT 0 COMMENT '状态：0-未开始 1-进行中 2-已结束',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_goods_id` (`goods_id`),
    KEY `idx_time` (`start_time`, `end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀活动表';

-- ---------------------------------------------------------------------
-- 4. 秒杀订单表
--    【考点1】uk_user_activity 唯一索引 → 数据库层兜底的"一人一单"幂等
--    【考点2】order_no 全局唯一 → 雪花算法生成
--    【考点3】分表键选择 user_id：查询场景是"查我的订单"，按 user_id 分片可避免跨库
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_seckill_order`;
CREATE TABLE `t_seckill_order`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`     VARCHAR(64)    NOT NULL COMMENT '订单号（雪花算法生成）',
    `user_id`      BIGINT         NOT NULL COMMENT '用户ID',
    `activity_id`  BIGINT         NOT NULL COMMENT '秒杀活动ID',
    `goods_id`     BIGINT         NOT NULL COMMENT '商品ID',
    `seckill_price` DECIMAL(10, 2) NOT NULL COMMENT '成交价',
    `status`       TINYINT        NOT NULL DEFAULT 0 COMMENT '订单状态：0-待支付 1-已支付 2-已取消 3-已退款',
    `pay_time`     DATETIME                DEFAULT NULL COMMENT '支付时间',
    `expire_time`  DATETIME       NOT NULL COMMENT '支付超时时间（用于延迟消息回滚）',
    `create_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 【核心】一人一单的数据库级兜底，即使 Redis 层被绕过也无法重复下单
    UNIQUE KEY `uk_user_activity` (`user_id`, `activity_id`),
    KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀订单表';

-- ---------------------------------------------------------------------
-- 5. 订单支付流水表（用于对账与最终一致性校验）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_flow`;
CREATE TABLE `t_pay_flow`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`    VARCHAR(64)    NOT NULL COMMENT '订单号',
    `user_id`     BIGINT         NOT NULL COMMENT '用户ID',
    `amount`      DECIMAL(10, 2) NOT NULL COMMENT '支付金额',
    `pay_status`  TINYINT        NOT NULL DEFAULT 0 COMMENT '支付状态：0-待支付 1-成功 2-失败',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='支付流水表';

-- ---------------------------------------------------------------------
-- 6. 本地消息表
--    【考点】分布式事务最终一致性方案之一，比 RocketMQ 事务消息更通用
--           流程：业务操作 + 写本地消息表（同一本地事务）→ 定时任务扫描投递 → 消费成功后标记
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_local_message`;
CREATE TABLE `t_local_message`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `message_id`    VARCHAR(64)  NOT NULL COMMENT '消息唯一ID（幂等键）',
    `topic`         VARCHAR(128) NOT NULL COMMENT '消息主题',
    `biz_key`       VARCHAR(128) NOT NULL COMMENT '业务键（如 orderNo）',
    `payload`       TEXT         NOT NULL COMMENT '消息体 JSON',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-待发送 1-已发送 2-消费成功 3-消费失败',
    `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    `next_retry_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='本地消息表';

-- ---------------------------------------------------------------------
-- 7. 幂等记录表（Token 机制）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_idempotent_record`;
CREATE TABLE `t_idempotent_record`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `idem_key`    VARCHAR(128) NOT NULL COMMENT '幂等键',
    `biz_type`    VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idem_key` (`idem_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='幂等记录表';

-- =====================================================================
-- 初始化数据
-- =====================================================================

-- 测试用户（密码明文 123456，BCrypt 加密后存储）
INSERT INTO `t_user` (`username`, `password`, `phone`, `nickname`)
VALUES ('zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '13800000001', '张三'),
       ('lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '13800000002', '李四'),
       ('wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '13800000003', '王五');

-- 商品
INSERT INTO `t_goods` (`goods_name`, `goods_title`, `goods_img`, `goods_price`, `goods_stock`)
VALUES ('iPhone 16 Pro', 'Apple iPhone 16 Pro 256G 原色钛金属', '/img/iphone16.jpg', 8999.00, 1000),
       ('MacBook Pro 14', 'Apple MacBook Pro 14英寸 M4 Pro', '/img/macbook.jpg', 14999.00, 500),
       ('AirPods Pro 3', 'Apple AirPods Pro 3 主动降噪', '/img/airpods.jpg', 1899.00, 2000),
       ('小米 15 Ultra', '小米15 Ultra 16GB+512GB', '/img/mi15.jpg', 6499.00, 800);

-- 秒杀活动（当前时间起，持续 24 小时；库存 100，方便压测观察超卖）
-- 注意：这里用 NOW() 动态生成时间，避免写死时间导致活动过期
INSERT INTO `t_seckill_activity` (`goods_id`, `seckill_price`, `seckill_stock`, `initial_stock`, `start_time`, `end_time`, `status`)
VALUES (1, 4999.00, 100, 100, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 24 HOUR), 1),
       (2, 9999.00, 50, 50, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 24 HOUR), 1),
       (3, 999.00, 200, 200, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 24 HOUR), 1),
       (4, 3999.00, 100, 100, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 24 HOUR), 1);

-- =====================================================================
-- 【分库分表 DDL】ShardingSphere 水平分表版本（可选，第 5 周使用）
-- 按 user_id 取模分成 4 张表
-- =====================================================================
-- DROP TABLE IF EXISTS t_seckill_order_0;
-- DROP TABLE IF EXISTS t_seckill_order_1;
-- DROP TABLE IF EXISTS t_seckill_order_2;
-- DROP TABLE IF EXISTS t_seckill_order_3;
-- CREATE TABLE t_seckill_order_0 LIKE t_seckill_order;
-- CREATE TABLE t_seckill_order_1 LIKE t_seckill_order;
-- CREATE TABLE t_seckill_order_2 LIKE t_seckill_order;
-- CREATE TABLE t_seckill_order_3 LIKE t_seckill_order;
-- 注意：分表后 uk_user_activity 唯一索引会失效（跨表无法保证），
--       必须改用分布式锁 + Redis 保证一人一单，这是面试重点讨论话题。

-- =====================================================================
-- 对账 SQL（用于验证是否超卖 / 少卖）
-- =====================================================================
-- 1) 检查是否超卖：剩余库存不能为负
-- SELECT id, goods_id, seckill_stock FROM t_seckill_activity WHERE seckill_stock < 0;
--
-- 2) 检查是否超卖（业务口径）：成功订单数不能超过初始库存
-- SELECT a.id,
--        a.initial_stock,
--        (SELECT COUNT(*) FROM t_seckill_order o
--          WHERE o.activity_id = a.id AND o.status IN (0, 1)) AS order_count
-- FROM t_seckill_activity a
-- HAVING order_count > a.initial_stock;
--
-- 3) 检查是否少卖：初始库存 - 剩余库存 是否等于 有效订单数
-- SELECT a.id, a.initial_stock, a.seckill_stock,
--        a.initial_stock - a.seckill_stock AS deducted,
--        (SELECT COUNT(*) FROM t_seckill_order o
--          WHERE o.activity_id = a.id AND o.status IN (0, 1)) AS order_count
-- FROM t_seckill_activity a;
