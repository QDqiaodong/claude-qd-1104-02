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
DROP TABLE IF EXISTS bake_schedule_item;
DROP TABLE IF EXISTS bake_schedule;
DROP TABLE IF EXISTS bake_schedule_meta;
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
    deadline   DATETIME     COMMENT '交付截止时间（可空，排产逾期评估用）',
    status     VARCHAR(16)  COMMENT '待烤 / 烘焙中 / 已出炉 / 已质检',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 生产排产（建议顺序 + 版本） ----------------
-- 排产只是“建议”：结果落在下面两张表，绝不回写 bake_order / oven 的状态与归属。
CREATE TABLE bake_schedule (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(64) NOT NULL COMMENT '版本名',
    fingerprint       VARCHAR(64) NOT NULL COMMENT '排产输入数据指纹（烤箱/工单/配方快照哈希），不一致即过期',
    base_version_id   BIGINT       COMMENT '基于哪个版本调整而来（并发保存裁决用）',
    scheduled_count   INT NOT NULL DEFAULT 0 COMMENT '排入工单数',
    unscheduled_count INT NOT NULL DEFAULT 0 COMMENT '未排入工单数',
    created_by        VARCHAR(32)  COMMENT '保存人（操作员）',
    created_at        DATETIME     COMMENT '保存时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bake_schedule_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT      NOT NULL COMMENT '排产版本 id',
    order_id    BIGINT      NOT NULL COMMENT '工单 id',
    seq         INT         NOT NULL COMMENT '优先级序号（0 起，越小越先排）',
    scheduled   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=排入 0=未排入',
    oven_id     BIGINT      COMMENT '建议占用烤箱 id',
    oven_code   VARCHAR(32) COMMENT '建议占用烤箱炉号（快照）',
    est_start   DATETIME    COMMENT '预计开始时间',
    est_finish  DATETIME    COMMENT '预计完成时间',
    risk        VARCHAR(16) COMMENT '截止时间风险：正常 / 紧张 / 无截止时间',
    reason      VARCHAR(255) COMMENT '排入解释或未排入原因（容量预警 / 预计逾期等）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 单行元数据：保存排产版本时对该行加行锁，串行化并发保存，保证只保留一个最新版本
CREATE TABLE bake_schedule_meta (
    id                BIGINT PRIMARY KEY,
    latest_version_id BIGINT COMMENT '当前最新排产版本 id'
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

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO oven (code, capacity, status, created_at) VALUES
('OV-01', 4,  '空闲', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-02', 6,  '占用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-03', 3,  '故障', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('OV-04', 5,  '空闲', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY);

INSERT INTO bread_recipe (name, need_trays, bake_minutes, status, created_at) VALUES
('法棍',     2, 30, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('牛角包',   3, 25, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('乡村面包', 4, 45, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('全麦吐司', 4, 40, '上架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 45 DAY),
('可颂',     2, 28, '隐藏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

-- 工单：覆盖 4 个状态，且含异常态（待烤落在故障烤箱 OV-03 上，审计可点）
-- OV-02 为占用（与其中一张烘焙中工单呼应）；OV-03 为故障（待烤工单将无法 start）
-- deadline 为交付截止时间（相对建库时间），排产视图据此评估逾期风险
INSERT INTO bake_order (oven_id, recipe_id, trays, start_time, deadline, status, created_at) VALUES
(3, 1, 2, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 8 HOUR,  '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 2, 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR,     UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 26 HOUR, '烘焙中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(1, 3, 4, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 20 HOUR, '已出炉', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(4, 1, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '已质检', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
-- 排产演示用待烤工单：不同配方/烤盘数/截止时间，分布在不同烤箱
(1, 4, 4, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 8 HOUR,  '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4, 2, 3, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 12 HOUR, '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(1, 1, 2, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 26 HOUR, '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 3, 4, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 30 HOUR, '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(2, 1, 6, NULL,                                   UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 10 HOUR, '待烤', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- 出货：覆盖 待发 / 已发 两态，均挂在已质检工单(id=4)上
INSERT INTO shipment (bake_order_id, qty, channel, status, created_at) VALUES
(4, 20, '门店', '待发', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 15, '外卖', '已发', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY);

-- 排产元数据单行（id 固定为 1）：并发保存排产版本时对该行加行锁裁决
INSERT INTO bake_schedule_meta (id, latest_version_id) VALUES (1, NULL);
