package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 排产版本头：一次“保存排产”产生一个版本。
 * 版本只是建议快照，不回写工单/烤箱的任何实际状态；
 * fingerprint 与当前数据不一致时，该版本在视图中标为「过期」。
 */
@Entity
@Table(name = "bake_schedule")
public class BakeSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 版本名 */
    @Column(name = "name", nullable = false, length = 64)
    public String name;

    /** 排产输入数据指纹（烤箱/工单/配方快照哈希） */
    @Column(name = "fingerprint", nullable = false, length = 64)
    public String fingerprint;

    /** 基于哪个版本调整而来（并发保存裁决用） */
    @Column(name = "base_version_id")
    public Long baseVersionId;

    /** 排入工单数 */
    @Column(name = "scheduled_count", nullable = false)
    public Integer scheduledCount;

    /** 未排入工单数 */
    @Column(name = "unscheduled_count", nullable = false)
    public Integer unscheduledCount;

    /** 保存人（操作员） */
    @Column(name = "created_by", length = 32)
    public String createdBy;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
