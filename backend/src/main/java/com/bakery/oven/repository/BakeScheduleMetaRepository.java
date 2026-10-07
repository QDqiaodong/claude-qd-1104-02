package com.bakery.oven.repository;

import com.bakery.oven.entity.BakeScheduleMeta;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface BakeScheduleMetaRepository extends JpaRepository<BakeScheduleMeta, Long> {

    /**
     * 行锁：保存排产版本时锁定单行元数据（id=1），
     * 串行化并发保存，保证只有一方能把新版本写成最新版本。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT m FROM BakeScheduleMeta m WHERE m.id = :id")
    Optional<BakeScheduleMeta> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
