package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleAccountingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OnlineSaleAccountingRepository extends JpaRepository<OnlineSaleAccountingEntity, Long> {

    Optional<OnlineSaleAccountingEntity> findByOnlineSaleId(Long onlineSaleId);

    List<OnlineSaleAccountingEntity> findByOnlineSaleIdIn(Collection<Long> onlineSaleIds);

    List<OnlineSaleAccountingEntity> findByLockedPeriodId(Long lockedPeriodId);
}
