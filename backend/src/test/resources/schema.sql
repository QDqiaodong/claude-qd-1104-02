-- H2(MySQL 兼容模式) 测试库结构：与生产 schema.sql 保持一致的表与种子数据
CREATE TABLE oven (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    code      VARCHAR(32) NOT NULL UNIQUE,
    capacity  INT         NOT NULL,
    status    VARCHAR(16),
    created_at DATETIME
);

CREATE TABLE bread_recipe (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE,
    need_trays  INT         NOT NULL,
    bake_minutes INT        NOT NULL,
    status      VARCHAR(16),
    created_at  DATETIME
);

CREATE TABLE bake_order (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    oven_id    BIGINT,
    recipe_id  BIGINT,
    trays      INT          NOT NULL,
    start_time DATETIME,
    due_time   DATETIME,
    status     VARCHAR(16),
    created_at DATETIME
);

CREATE TABLE shipment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    bake_order_id BIGINT,
    qty          INT          NOT NULL,
    channel      VARCHAR(16),
    status       VARCHAR(16),
    created_at   DATETIME
);

CREATE TABLE schedule_version (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_no        INT NOT NULL UNIQUE,
    base_version_no   INT,
    operator_name     VARCHAR(32),
    input_fingerprint VARCHAR(64)  NOT NULL,
    input_snapshot    TEXT,
    scheduled_count   INT          NOT NULL,
    unscheduled_count INT          NOT NULL,
    created_at        DATETIME
);

CREATE TABLE schedule_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id      BIGINT      NOT NULL,
    order_id        BIGINT      NOT NULL,
    seq_no          INT,
    placed          TINYINT(1)  NOT NULL,
    oven_id         BIGINT,
    oven_code       VARCHAR(32),
    est_start_time  DATETIME,
    est_finish_time DATETIME,
    due_time        DATETIME,
    deadline_risk   VARCHAR(16),
    reason_type     VARCHAR(16),
    capacity_note   VARCHAR(128),
    reason          VARCHAR(255),
    created_at      DATETIME
);

CREATE TABLE schedule_state (
    id                INT PRIMARY KEY,
    latest_version_no INT NOT NULL
);

INSERT INTO schedule_state (id, latest_version_no) VALUES (1, 0);

INSERT INTO oven (id, code, capacity, status, created_at) VALUES
(1, 'OV-01', 4, '空闲', NULL),
(2, 'OV-02', 6, '占用', NULL),
(3, 'OV-03', 3, '故障', NULL),
(4, 'OV-04', 5, '空闲', NULL),
(5, 'OV-05', 2, '空闲', NULL);

INSERT INTO bread_recipe (id, name, need_trays, bake_minutes, status, created_at) VALUES
(1, '法棍', 2, 30, '上架', NULL),
(2, '牛角包', 3, 25, '上架', NULL),
(3, '乡村面包', 4, 45, '上架', NULL);

-- 故障炉 OV-03 上：1 张待烤(2盘) + 1 张烘焙中(3盘，带开始时间)
INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, due_time, status, created_at) VALUES
(1, 3, 1, 2, NULL, NULL, '待烤', NULL),
(2, 3, 2, 3, '2026-10-06 09:00:00', NULL, '烘焙中', NULL),
(3, 2, 1, 2, '2026-10-06 08:00:00', NULL, '烘焙中', NULL);
