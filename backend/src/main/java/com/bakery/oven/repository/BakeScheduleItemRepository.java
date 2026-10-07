package com.bakery.oven.repository;

import com.bakery.oven.entity.BakeScheduleItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BakeScheduleItemRepository extends JpaRepository<BakeScheduleItem, Long> {

    List<BakeScheduleItem> findByScheduleIdOrderBySeq(Long scheduleId);
}
