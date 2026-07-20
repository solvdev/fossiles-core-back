package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoInternalCountItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoInternalCountItemRepository extends JpaRepository<KioscoInternalCountItemEntity, Long> {

    List<KioscoInternalCountItemEntity> findByInternalCountId(Long internalCountId);

    Optional<KioscoInternalCountItemEntity> findByInternalCountIdAndProductIdAndColorId(
            Long internalCountId, Long productId, Long colorId);
}
