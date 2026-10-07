package com.bakery.oven.repository;

import com.bakery.oven.entity.BakeOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface BakeOrderRepository extends JpaRepository<BakeOrder, Long> {

    List<BakeOrder> findByStatus(String status);

    List<BakeOrder> findByOvenId(Long ovenId);

    List<BakeOrder> findByOvenIdAndStatus(Long ovenId, String status);

    /** 行锁：接管事务内锁定被迁移的工单，防止与开始/出炉等流转交叉产生半迁移数据 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT o FROM BakeOrder o WHERE o.id = :id")
    Optional<BakeOrder> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
