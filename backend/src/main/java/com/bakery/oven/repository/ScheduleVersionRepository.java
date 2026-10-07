package com.bakery.oven.repository;

import com.bakery.oven.entity.ScheduleVersion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleVersionRepository extends JpaRepository<ScheduleVersion, Long> {

    Optional<ScheduleVersion> findTopByOrderByVersionNoDesc();

    List<ScheduleVersion> findAllByOrderByVersionNoDesc();
}
