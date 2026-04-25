package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialReceiptItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialReceiptItemRepository extends JpaRepository<MaterialReceiptItemEntity, Long> {
    List<MaterialReceiptItemEntity> findByMaterialReceiptId(Long materialReceiptId);
}

