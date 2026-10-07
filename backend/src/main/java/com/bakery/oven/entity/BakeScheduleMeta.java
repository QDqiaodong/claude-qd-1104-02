package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 排产元数据（单行，id 固定为 1）：
 * 保存排产版本时对该行加行锁，串行化并发保存，
 * 保证两名操作员同时保存时只保留一个最新版本。
 */
@Entity
@Table(name = "bake_schedule_meta")
public class BakeScheduleMeta {

    @Id
    public Long id;

    /** 当前最新排产版本 id */
    @Column(name = "latest_version_id")
    public Long latestVersionId;
}
