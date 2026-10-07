package com.bakery.oven.repository;

import com.bakery.oven.entity.BakeSchedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BakeScheduleRepository extends JpaRepository<BakeSchedule, Long> {

    /** 最新一个排产版本 */
    Optional<BakeSchedule> findFirstByOrderByIdDesc();
}
