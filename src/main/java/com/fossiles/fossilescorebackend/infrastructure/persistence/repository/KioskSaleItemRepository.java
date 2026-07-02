package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskSaleItemRepository extends JpaRepository<KioskSaleItemEntity, Long> {
    List<KioskSaleItemEntity> findByKioskSaleIdOrderByIdAsc(Long kioskSaleId);

    java.util.Optional<KioskSaleItemEntity> findByIdAndKioskSale_Id(Long id, Long kioskSaleId);
}
