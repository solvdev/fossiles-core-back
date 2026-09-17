package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipGivenItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KioskExchangeSlipGivenItemRepository extends JpaRepository<KioskExchangeSlipGivenItemEntity, Long> {
    List<KioskExchangeSlipGivenItemEntity> findByExchangeSlipIdOrderByLineNoAsc(Long exchangeSlipId);

    List<KioskExchangeSlipGivenItemEntity> findByExchangeSlipIdIn(Collection<Long> exchangeSlipIds);

    void deleteByExchangeSlipId(Long exchangeSlipId);
}
