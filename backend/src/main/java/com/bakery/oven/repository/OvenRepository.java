package com.bakery.oven.repository;

import com.bakery.oven.entity.Oven;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface OvenRepository extends JpaRepository<Oven, Long> {

    Optional<Oven> findByCode(String code);

    List<Oven> findByStatus(String status);

    /** 行锁：故障接管 / 开始烘焙等跨炉状态变更时使用，串行化同一炉的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT o FROM Oven o WHERE o.id = :id")
    Optional<Oven> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
