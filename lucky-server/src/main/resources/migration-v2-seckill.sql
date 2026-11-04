-- ============================================================
-- v2 迁移：把「管理员随机抽人」升级为「用户限时抢奖（秒杀）」
--
-- 适用场景：数据库里已有 v1 的数据，不想 down -v 重建时执行本脚本。
-- 全新环境不需要执行，schema.sql 已经是 v2 结构。
--
-- 执行方式：
--   docker exec -i lucky-mysql mysql -uroot -p123456 lucky < migration-v2-seckill.sql
-- 或进入容器后 source。
-- ============================================================

SET NAMES utf8mb4;
USE lucky;

-- ---------- 1. 参与者：status 拆成 lottery_status ----------
-- 原 status 混用了「已参与(1) / 已中奖(0) / 已移除(3)」，中奖与否应由 winner 表表达
ALTER TABLE participant
    CHANGE COLUMN status lottery_status TINYINT DEFAULT 1 COMMENT '抽奖资格 1可参与 3已移除';

-- 历史上的 0（已中奖）统一恢复成 1（可参与），中奖事实仍能从 winner 表查到
UPDATE participant SET lottery_status = 1 WHERE lottery_status NOT IN (1, 3);

-- ---------- 2. 奖品：拆出总库存 ----------
ALTER TABLE prize
    ADD COLUMN total_stock INT DEFAULT 0 COMMENT '总库存（配置值，不可变）' AFTER description;

UPDATE prize SET total_stock = stock WHERE total_stock = 0;

-- ---------- 3. 轮次：绑定奖品 + 抢奖时间窗 ----------
ALTER TABLE lottery_round
    ADD COLUMN prize_id BIGINT NULL COMMENT '绑定的奖品ID' AFTER activity_id;

ALTER TABLE lottery_round
    ADD COLUMN start_time DATETIME NULL COMMENT '抢奖开始时间' AFTER status;

ALTER TABLE lottery_round
    ADD COLUMN end_time DATETIME NULL COMMENT '抢奖结束时间' AFTER start_time;

ALTER TABLE lottery_round
    MODIFY COLUMN status TINYINT DEFAULT 0 COMMENT '0待开始 1抢奖中 2已结束 3已取消';

-- ---------- 4. 中奖记录：升级为抢奖订单 ----------
ALTER TABLE winner
    ADD COLUMN activity_id BIGINT NULL COMMENT '冗余活动ID，用于跨轮次唯一约束' AFTER id;

ALTER TABLE winner
    ADD COLUMN prize_id BIGINT NULL COMMENT '奖品ID' AFTER participant_id;

ALTER TABLE winner
    ADD COLUMN status TINYINT DEFAULT 0 COMMENT '0待领取 1已领取 2已放弃' AFTER prize_id;

ALTER TABLE winner
    ADD COLUMN claim_code VARCHAR(32) NULL COMMENT '领奖码' AFTER status;

-- 回填历史数据
UPDATE winner w
    JOIN lottery_round r ON w.round_id = r.id
SET w.activity_id = r.activity_id,
    w.prize_id = r.prize_id
WHERE w.activity_id IS NULL;

-- 加唯一索引前先清掉「同一活动同一人中奖多次」的历史脏数据，只保留最早一条
DELETE w1 FROM winner w1
    JOIN winner w2
        ON w1.activity_id = w2.activity_id
        AND w1.participant_id = w2.participant_id
        AND w1.id > w2.id;

ALTER TABLE winner DROP COLUMN notified;

ALTER TABLE winner
    ADD UNIQUE INDEX uk_activity_participant (activity_id, participant_id);

-- ---------- 5. 历史轮次补齐 prize_id（可选） ----------
-- 如果历史轮次没有绑定奖品，抢奖接口会因为没有 prize_id 而无法预热。
-- 建议给这些轮次手工补一个 prize_id，或直接删除历史轮次数据：
--   DELETE FROM winner WHERE round_id IN (SELECT id FROM lottery_round WHERE prize_id IS NULL);
--   DELETE FROM lottery_round WHERE prize_id IS NULL;

SELECT '迁移完成' AS result;
