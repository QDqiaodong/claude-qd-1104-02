SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 烘焙坊 · 烤箱设备与面包批次
-- 库名 bakery_oven；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，时间戳一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR（北京时间）。

CREATE DATABASE IF NOT EXISTS bakery_oven
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE bakery_oven;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS schedule_item;
DROP TABLE IF EXISTS schedule_version;
DROP TABLE IF EXISTS schedule_state;
DROP TABLE IF EXISTS shipment;
DROP TABLE IF EXISTS bake_order;
DROP TABLE IF EXISTS bread_recipe;
DROP TABLE IF EXISTS oven;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 烤箱 ----------------
CREATE TABLE oven (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    code      VARCHAR(32) NOT NULL UNIQUE COMMENT '炉号',
    capacity  INT         NOT NULL COMMENT '容量=同时可烤盘数',
    status    VARCHAR(16) COMMENT '空闲 / 占用 / 故障',
    created_at DATETIME   COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 面包配方 ----------------
CREATE TABLE bread_recipe (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE COMMENT '面包名',
    need_trays  INT         NOT NULL COMMENT '需用烤盘数',
    bake_minutes INT        NOT NULL COMMENT '烘焙时长(分钟)',
    status      VARCHAR(16) COMMENT '上架 / 隐藏',
    created_at  DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 烘焙工单（看板核心） ----------------
CREATE TABLE bake_order (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    oven_id    BIGINT       COMMENT '烤箱 id',
    recipe_id  BIGINT       COMMENT '配方 id',
    trays      INT          NOT NULL COMMENT '实际烤盘数',
    start_time DATETIME     COMMENT '开始时间（可空）',
    due_time   DATETIME     COMMENT '交付截止时间（可空，生产排产输入）',
    status     VARCHAR(16)  COMMENT '待烤 / 烘焙中 / 已出炉 / 已质检',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出货 ----------------
CREATE TABLE shipment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    bake_order_id BIGINT      COMMENT '对应工单 id',
    qty          INT          NOT NULL COMMENT '数量',
    channel      VARCHAR(16)  COMMENT '门店 / 外卖',
    status       VARCHAR(16)  COMMENT '待发 / 已发',
    created_at   DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 排产版本（建议顺序快照，只影响建议，不改写工单/烤箱） ----------------
CREATE TABLE schedule_version (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_no        INT NOT NULL UNIQUE COMMENT '排产版本号，单调递增',
    base_version_no   INT          COMMENT '保存时基于的版本号（乐观并发校验）；首个版本为 NULL',
    operator_name     VARCHAR(32)  COMMENT '操作员',
    input_fingerprint VARCHAR(64)  NOT NULL COMMENT '排产输入指纹（SHA-256），输入数据变化即过期',
    input_snapshot    TEXT         COMMENT '排产输入快照（JSON），用于过期时列出变化明细',
    scheduled_count   INT          NOT NULL COMMENT '排入工单数',
    unscheduled_count INT          NOT NULL COMMENT '未排入工单数',
    created_at        DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 排产明细 ----------------
CREATE TABLE schedule_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id      BIGINT      NOT NULL COMMENT '排产版本 id',
    order_id        BIGINT      NOT NULL COMMENT '工单 id',
    seq_no          INT         COMMENT '建议顺序（仅已排入）',
    placed          TINYINT(1)  NOT NULL COMMENT '1=已排入 0=未排入',
    oven_id         BIGINT      COMMENT '建议占用烤箱（未排入为空）',
    oven_code       VARCHAR(32) COMMENT '建议烤箱炉号（冗余）',
    est_start_time  DATETIME    COMMENT '预计开始时间',
    est_finish_time DATETIME    COMMENT '预计完成时间',
    due_time        DATETIME    COMMENT '交付截止（保存时快照）',
    deadline_risk   VARCHAR(16) COMMENT '截止风险：无 / 紧张',
    reason_type     VARCHAR(16) COMMENT '未排入类型：NO_OVEN / CAPACITY / OVERDUE / RECIPE_MISSING',
    capacity_note   VARCHAR(128) COMMENT '容量说明 / 容量冲突提示',
    reason          VARCHAR(255) COMMENT '可解释原因（排入依据或未排入原因）',
    created_at      DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 排产状态（单行，保存串行化行锁） ----------------
CREATE TABLE schedule_state (
    id                INT PRIMARY KEY,
    latest_version_no INT NOT NULL COMMENT '当前最新排产版本号（0=还没有版本）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO schedule_state (id, latest_version_no) VALUES (1, 0);

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO oven (code, capacity, status, created_at) VALUES
('OV-01', 4,  '空闲', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-02', 6,  '占用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-03', 3,  '故障', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-04', 5,  '空闲', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-05', 2,  '空闲', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY);

INSERT INTO bread_recipe (name, need_trays, bake_minutes, status, created_at) VALUES
('法棍',     2, 30, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('牛角包',   3, 25, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('乡村面包', 4, 45, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('全麦吐司', 4, 40, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 45 DAY),
('可颂',     2, 28, '隐藏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

-- 工单：覆盖 4 个状态，且含异常态（待烤 + 烘焙中都有工单落在故障烤箱 OV-03 上，供故障接管演示）
-- OV-02 为占用（工单3烘焙中呼应）；OV-03 为故障：工单1待烤无法 start、工单2烘焙中需接管续烤；
-- OV-04 空闲(容量5)、OV-01 空闲(容量4)、OV-05 空闲(容量2) 可作为替代烤箱
INSERT INTO bake_order (oven_id, recipe_id, trays, start_time, due_time, status, created_at) VALUES
(3, 1, 2, NULL,                                                          NULL,                                                          '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(3, 2, 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 MINUTE,        NULL,                                                          '烘焙中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 2, 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 MINUTE,        NULL,                                                          '烘焙中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(1, 3, 4, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,            NULL,                                                          '已出炉', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(4, 1, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,            NULL,                                                          '已质检', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
-- 以下待烤工单供「生产排产」演示：含交付截止；工单10 只有 OV-02(容量6) 装得下，
-- 但 OV-02 要等工单3出炉（约5分钟后）才释放，45分钟烤完会晚于40分钟后的交付截止 → 落在未排入区（预计逾期）；
-- 若把 OV-02 置故障，工单10 还会演示「容量不足」（其余可用烤箱最大容量 5 < 6）
(1, 1, 2, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 100 MINUTE, '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4, 3, 4, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 70 MINUTE,  '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(1, 2, 3, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 HOUR,     '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 4, 4, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 6 HOUR,     '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(2, 3, 6, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 40 MINUTE,  '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR),
(5, 1, 2, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 5 HOUR,     '待烤',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- 出货：覆盖 待发 / 已发 两态，均挂在已质检工单(id=5)上
INSERT INTO shipment (bake_order_id, qty, channel, status, created_at) VALUES
(5, 20, '门店', '待发', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(5, 15, '外卖', '已发', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY);
