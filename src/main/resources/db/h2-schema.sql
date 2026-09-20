-- =====================================================================
-- H2 演示模式建表脚本
-- 语法与 schema.sql 一致（H2 以 MODE=MySQL 运行）
-- 差异点：H2 不支持 ENGINE / CHARSET / COMMENT 这些 MySQL 专有子句
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    phone       VARCHAR(20),
    nickname    VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT   DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_goods
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_name   VARCHAR(128)   NOT NULL,
    goods_title  VARCHAR(255),
    goods_img    VARCHAR(255),
    goods_detail CLOB,
    goods_price  DECIMAL(10, 2) DEFAULT 0.00,
    goods_stock  INT            DEFAULT 0,
    create_time  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted      TINYINT        DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_seckill_activity
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_id      BIGINT         NOT NULL,
    seckill_price DECIMAL(10, 2) NOT NULL,
    seckill_stock INT            NOT NULL,
    initial_stock INT            NOT NULL,
    start_time    TIMESTAMP      NOT NULL,
    end_time      TIMESTAMP      NOT NULL,
    version       INT   DEFAULT 0,
    status        TINYINT DEFAULT 0,
    create_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_seckill_order
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(64)    NOT NULL UNIQUE,
    user_id       BIGINT         NOT NULL,
    activity_id   BIGINT         NOT NULL,
    goods_id      BIGINT         NOT NULL,
    seckill_price DECIMAL(10, 2) NOT NULL,
    status        TINYINT DEFAULT 0,
    pay_time      TIMESTAMP,
    expire_time   TIMESTAMP      NOT NULL,
    create_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    -- 一人一单的数据库级兜底
    CONSTRAINT uk_user_activity UNIQUE (user_id, activity_id)
);

CREATE TABLE IF NOT EXISTS t_pay_flow
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(64)    NOT NULL UNIQUE,
    user_id     BIGINT         NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL,
    pay_status  TINYINT DEFAULT 0,
    create_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_local_message
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL UNIQUE,
    topic           VARCHAR(128) NOT NULL,
    biz_key         VARCHAR(128) NOT NULL,
    payload         CLOB         NOT NULL,
    status          TINYINT DEFAULT 0,
    retry_count     INT     DEFAULT 0,
    next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_idempotent_record
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    idem_key    VARCHAR(128) NOT NULL UNIQUE,
    biz_type    VARCHAR(64)  NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
-- 初始化数据
-- =====================================================================
INSERT INTO t_user (username, password, phone, nickname)
SELECT 'zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
       '13800000001', '张三'
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'zhangsan');

INSERT INTO t_user (username, password, phone, nickname)
SELECT 'lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
       '13800000002', '李四'
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'lisi');

INSERT INTO t_user (username, password, phone, nickname)
SELECT 'wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
       '13800000003', '王五'
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'wangwu');

INSERT INTO t_goods (goods_name, goods_title, goods_img, goods_price, goods_stock)
SELECT 'iPhone 16 Pro', 'Apple iPhone 16 Pro 256G 原色钛金属', '/img/iphone16.jpg', 8999.00, 1000
WHERE NOT EXISTS (SELECT 1 FROM t_goods WHERE goods_name = 'iPhone 16 Pro');

INSERT INTO t_goods (goods_name, goods_title, goods_img, goods_price, goods_stock)
SELECT 'MacBook Pro 14', 'Apple MacBook Pro 14英寸 M4 Pro', '/img/macbook.jpg', 14999.00, 500
WHERE NOT EXISTS (SELECT 1 FROM t_goods WHERE goods_name = 'MacBook Pro 14');

INSERT INTO t_goods (goods_name, goods_title, goods_img, goods_price, goods_stock)
SELECT 'AirPods Pro 3', 'Apple AirPods Pro 3 主动降噪', '/img/airpods.jpg', 1899.00, 2000
WHERE NOT EXISTS (SELECT 1 FROM t_goods WHERE goods_name = 'AirPods Pro 3');

INSERT INTO t_goods (goods_name, goods_title, goods_img, goods_price, goods_stock)
SELECT '小米 15 Ultra', '小米15 Ultra 16GB+512GB', '/img/mi15.jpg', 6499.00, 800
WHERE NOT EXISTS (SELECT 1 FROM t_goods WHERE goods_name = '小米 15 Ultra');

-- 秒杀活动：库存 100，方便压测观察超卖
INSERT INTO t_seckill_activity
    (goods_id, seckill_price, seckill_stock, initial_stock, start_time, end_time, status)
SELECT 1, 4999.00, 100, 100,
       DATEADD('HOUR', -1, CURRENT_TIMESTAMP),
       DATEADD('HOUR', 24, CURRENT_TIMESTAMP), 1
WHERE NOT EXISTS (SELECT 1 FROM t_seckill_activity WHERE goods_id = 1);

INSERT INTO t_seckill_activity
    (goods_id, seckill_price, seckill_stock, initial_stock, start_time, end_time, status)
SELECT 2, 9999.00, 50, 50,
       DATEADD('HOUR', -1, CURRENT_TIMESTAMP),
       DATEADD('HOUR', 24, CURRENT_TIMESTAMP), 1
WHERE NOT EXISTS (SELECT 1 FROM t_seckill_activity WHERE goods_id = 2);

INSERT INTO t_seckill_activity
    (goods_id, seckill_price, seckill_stock, initial_stock, start_time, end_time, status)
SELECT 3, 999.00, 200, 200,
       DATEADD('HOUR', -1, CURRENT_TIMESTAMP),
       DATEADD('HOUR', 24, CURRENT_TIMESTAMP), 1
WHERE NOT EXISTS (SELECT 1 FROM t_seckill_activity WHERE goods_id = 3);

INSERT INTO t_seckill_activity
    (goods_id, seckill_price, seckill_stock, initial_stock, start_time, end_time, status)
SELECT 4, 3999.00, 100, 100,
       DATEADD('HOUR', -1, CURRENT_TIMESTAMP),
       DATEADD('HOUR', 24, CURRENT_TIMESTAMP), 1
WHERE NOT EXISTS (SELECT 1 FROM t_seckill_activity WHERE goods_id = 4);
