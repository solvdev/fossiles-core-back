package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCostCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskCostCategoryRepository extends JpaRepository<KioskCostCategoryEntity, String> {

    List<KioskCostCategoryEntity> findByActiveTrueOrderBySortOrderAscCodeAsc();
}
