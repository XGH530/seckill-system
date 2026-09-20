-- =====================================================================
-- 压测数据准备与重置脚本
-- 用法：
--   1) 首次准备：mysql -uroot -p seckill < sql/reset.sql
--   2) 每轮压测前重新执行本脚本，清空上一轮数据
-- =====================================================================

USE `seckill`;

-- ---------------------------------------------------------------------
-- 第一步：批量生成压测用户
-- 【为什么要 3000 个用户？】
-- 因为"一人一单"限制，每个用户只能成功秒杀一次。
-- 要做 2000 并发的有效压测，必须有 ≥2000 个不同账号。
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `gen_test_users`;
DELIMITER $$
CREATE PROCEDURE `gen_test_users`()
BEGIN
    DECLARE i INT DEFAULT 1;
    WHILE i <= 3000 DO
        INSERT IGNORE INTO `t_user` (`username`, `password`, `phone`, `nickname`)
        VALUES (CONCAT('test', i),
                -- BCrypt("123456")，与 AuthController 的校验逻辑对应
                '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
                CONCAT('139', LPAD(i, 8, '0')),
                CONCAT('压测用户', i));
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `gen_test_users`();
DROP PROCEDURE IF EXISTS `gen_test_users`;

SELECT CONCAT('压测用户总数：', COUNT(*)) AS msg FROM `t_user` WHERE `username` LIKE 'test%';

-- ---------------------------------------------------------------------
-- 第二步：重置业务数据（每轮压测前执行）
-- ---------------------------------------------------------------------

-- 清空订单
DELETE FROM `t_seckill_order`;
ALTER TABLE `t_seckill_order` AUTO_INCREMENT = 1;

-- 清空支付流水
DELETE FROM `t_pay_flow`;
ALTER TABLE `t_pay_flow` AUTO_INCREMENT = 1;

-- 清空本地消息表
DELETE FROM `t_local_message`;

-- 清空幂等记录
DELETE FROM `t_idempotent_record`;

-- 重置活动库存为初始值，版本号归零，状态置为进行中
UPDATE `t_seckill_activity`
SET `seckill_stock` = `initial_stock`,
    `version` = 0,
    `status` = 1,
    `start_time` = DATE_SUB(NOW(), INTERVAL 1 HOUR),
    `end_time` = DATE_ADD(NOW(), INTERVAL 24 HOUR);

-- ---------------------------------------------------------------------
-- 第三步：确认重置结果
-- ---------------------------------------------------------------------
SELECT '=== 重置完成，活动状态 ===' AS msg;
SELECT id, goods_id, initial_stock, seckill_stock, version, status,
       start_time, end_time
FROM `t_seckill_activity`;

SELECT CONCAT('订单数（应为 0）：', COUNT(*)) AS msg FROM `t_seckill_order`;

-- =====================================================================
-- 压测后校验 SQL（单独执行）
-- =====================================================================
-- 【校验 1】库存不能为负 —— 这是超卖的直接证据
-- SELECT id, goods_id, seckill_stock FROM t_seckill_activity WHERE seckill_stock < 0;
--
-- 【校验 2】订单数不能超过初始库存
-- SELECT a.id, a.initial_stock,
--        (SELECT COUNT(*) FROM t_seckill_order o
--          WHERE o.activity_id = a.id AND o.status IN (0,1)) AS cnt
-- FROM t_seckill_activity a
-- HAVING cnt > a.initial_stock;
--
-- 【校验 3】库存扣减数应等于有效订单数（不超卖也不少卖）
-- SELECT a.id, a.initial_stock, a.seckill_stock,
--        a.initial_stock - a.seckill_stock AS deducted,
--        (SELECT COUNT(*) FROM t_seckill_order o
--          WHERE o.activity_id = a.id AND o.status IN (0,1)) AS order_count
-- FROM t_seckill_activity a;
--
-- 【校验 4】一人一单是否生效（必须 0 行）
-- SELECT user_id, activity_id, COUNT(*) AS cnt
-- FROM t_seckill_order GROUP BY user_id, activity_id HAVING cnt > 1;
