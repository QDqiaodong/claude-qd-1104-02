package com.bakery.oven.repository;

import com.bakery.oven.entity.ScheduleItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {

    List<ScheduleItem> findByVersionId(Long versionId);
}
