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
INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, status, created_at) VALUES
(1, 3, 1, 2, NULL, '待烤', NULL),
(2, 3, 2, 3, '2026-10-06 09:00:00', '烘焙中', NULL),
(3, 2, 1, 2, '2026-10-06 08:00:00', '烘焙中', NULL);
