package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipGivenItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KioskExchangeSlipGivenItemRepository extends JpaRepository<KioskExchangeSlipGivenItemEntity, Long> {
    List<KioskExchangeSlipGivenItemEntity> findByExchangeSlipIdOrderByLineNoAsc(Long exchangeSlipId);

    void deleteByExchangeSlipId(Long exchangeSlipId);
}
