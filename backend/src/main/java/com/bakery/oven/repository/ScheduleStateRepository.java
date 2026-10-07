package com.bakery.oven.repository;

import com.bakery.oven.entity.ScheduleState;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface ScheduleStateRepository extends JpaRepository<ScheduleState, Integer> {

    /** 行锁：保存排产版本时锁定状态行，串行化并发保存，保证只保留一个最新版本 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM ScheduleState s WHERE s.id = :id")
    Optional<ScheduleState> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Integer id);
}
