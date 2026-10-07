package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 排产状态单行表（id=1）：保存排产版本时以 PESSIMISTIC_WRITE 锁定该行，
 * 串行化并发保存——两名操作员同时保存同一排产时只有一人能基于最新版本号落库，
 * 较慢的旧计算（baseVersionNo 已落后）被拒绝，不能覆盖新版本。
 */
@Entity
@Table(name = "schedule_state")
public class ScheduleState {

    @Id
    public Integer id;

    /** 当前最新排产版本号（0=还没有任何版本） */
    @Column(name = "latest_version_no", nullable = false)
    public Integer latestVersionNo;
}
