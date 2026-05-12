package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryOutflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryOutflowRepository extends JpaRepository<InventoryOutflowEntity, Long> {

    List<InventoryOutflowEntity> findByReferenceTypeAndReferenceIdOrderByIdAsc(String referenceType, Long referenceId);
}
