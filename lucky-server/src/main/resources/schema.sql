-- 抽奖程序数据库初始化脚本
-- 关键：容器内 mysql CLI 客户端默认字符集为 latin1，会导致下方中文字面量被二次编码。
-- 显式声明连接字符集为 utf8mb4，保证初始数据（如默认敏感词）正确落库。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS lucky DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE lucky;

-- 活动表
CREATE TABLE IF NOT EXISTS activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '活动名称',
    status TINYINT DEFAULT 0 COMMENT '0未开始 1进行中 2已结束',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动表';

-- 参与者表
-- 说明：lottery_status 只表达「抽奖资格」，中奖事实由 winner 表表达，两者不再混用一个字段
CREATE TABLE IF NOT EXISTS participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL COMMENT '姓名',
    phone VARCHAR(20) COMMENT '手机号',
    student_id VARCHAR(30) NOT NULL COMMENT '学号',
    lottery_status TINYINT DEFAULT 1 COMMENT '抽奖资格 1可参与 3已移除',
    is_muted TINYINT DEFAULT 0 COMMENT '是否禁言 0否 1是',
    is_banned TINYINT DEFAULT 0 COMMENT '是否被移除直播间 0否 1是',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity (activity_id),
    UNIQUE INDEX uk_activity_student (activity_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参与者表';

-- 抽奖轮次表
-- prize_id 是核心：每个轮次绑定一个奖品，名额由奖品库存约束，不再允许「凭空抽人」
CREATE TABLE IF NOT EXISTS lottery_round (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    prize_id BIGINT NOT NULL COMMENT '绑定的奖品ID',
    round_name VARCHAR(100) COMMENT '轮次名称，如：一等奖、二等奖',
    winner_count INT DEFAULT 1 COMMENT '本轮名额（创建时不得超过奖品库存）',
    status TINYINT DEFAULT 0 COMMENT '0待开始 1抢奖中 2已结束 3已取消',
    start_time DATETIME COMMENT '抢奖开始时间',
    end_time DATETIME COMMENT '抢奖结束时间',
    version INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity (activity_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抽奖轮次表';

-- 中奖记录表（本质是「抢奖订单」）
-- uk_activity_participant 在数据库层强制「一人一活动只能中一次」，是 Redis 去重之外的最后防线
CREATE TABLE IF NOT EXISTS winner (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '冗余活动ID，用于跨轮次唯一约束',
    round_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    prize_id BIGINT NOT NULL,
    status TINYINT DEFAULT 0 COMMENT '0待领取 1已领取 2已放弃',
    claim_code VARCHAR(32) COMMENT '领奖码',
    claimed_at DATETIME COMMENT '领奖时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_round (round_id),
    INDEX idx_participant (participant_id),
    UNIQUE INDEX uk_round_participant (round_id, participant_id),
    UNIQUE INDEX uk_activity_participant (activity_id, participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中奖记录表';

-- 弹幕表
CREATE TABLE IF NOT EXISTS danmaku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    participant_id BIGINT COMMENT '发送者',
    content VARCHAR(200) NOT NULL COMMENT '弹幕内容',
    status TINYINT DEFAULT 0 COMMENT '0待审核 1已通过 2已拒绝',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity_status (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='弹幕表';

-- 敏感词表
CREATE TABLE IF NOT EXISTS sensitive_word (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    word VARCHAR(50) NOT NULL COMMENT '敏感词',
    UNIQUE INDEX uk_word (word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表';

-- 插入一些默认敏感词
INSERT IGNORE INTO sensitive_word (word) VALUES
('傻逼'), ('操你'), ('狗日'), ('混蛋'), ('王八蛋'), ('去死'), ('垃圾');

-- 屏幕配置表（单活动场景下为全局单例，固定 id=1）
CREATE TABLE IF NOT EXISTS screen_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    background_type VARCHAR(20) COMMENT '背景类型: image/video',
    background_url VARCHAR(500) COMMENT '背景文件URL',
    danmaku_area VARCHAR(20) DEFAULT 'full' COMMENT '弹幕显示区域: full/top/bottom',
    danmaku_opacity INT DEFAULT 80 COMMENT '弹幕不透明度(0-100)',
    danmaku_font_size INT DEFAULT 28 COMMENT '弹幕字号',
    danmaku_speed INT DEFAULT 10 COMMENT '弹幕滚动速度(秒)',
    mobile_background_type VARCHAR(20) COMMENT '手机端背景类型',
    mobile_background_url VARCHAR(500) COMMENT '手机端背景URL',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='屏幕配置表';

-- 插入默认配置
INSERT IGNORE INTO screen_config (id, background_type, background_url, danmaku_area, danmaku_opacity, danmaku_font_size, danmaku_speed) VALUES (1, NULL, NULL, 'full', 80, 28, 10);

-- 管理员用户表
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    role VARCHAR(20) DEFAULT 'ADMIN' COMMENT '角色：ADMIN/SUPER_ADMIN',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员用户表';

-- 奖品表
-- total_stock 是配置总量，stock 是剩余库存；两者分离才能做对账与超卖追溯
CREATE TABLE IF NOT EXISTS prize (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    name VARCHAR(100) NOT NULL COMMENT '奖品名称',
    image VARCHAR(500) COMMENT '奖品图片URL',
    description VARCHAR(500) COMMENT '奖品描述',
    total_stock INT DEFAULT 0 COMMENT '总库存（配置值，不可变）',
    stock INT DEFAULT 0 COMMENT '剩余库存（抢奖实时扣减）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奖品表';

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT COMMENT '操作人ID',
    operator_name VARCHAR(50) COMMENT '操作人姓名',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型',
    target_type VARCHAR(50) COMMENT '目标类型',
    target_id BIGINT COMMENT '目标ID',
    detail VARCHAR(500) COMMENT '操作详情',
    ip VARCHAR(50) COMMENT '操作IP',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_operator (operator_id),
    INDEX idx_type (operation_type),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';
