package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipReturnedItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KioskExchangeSlipReturnedItemRepository
        extends JpaRepository<KioskExchangeSlipReturnedItemEntity, Long> {
    List<KioskExchangeSlipReturnedItemEntity> findByExchangeSlipIdOrderByLineNoAsc(Long exchangeSlipId);

    void deleteByExchangeSlipId(Long exchangeSlipId);
}
