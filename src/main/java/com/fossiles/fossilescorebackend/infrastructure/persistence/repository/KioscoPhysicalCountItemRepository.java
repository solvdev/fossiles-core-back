package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoPhysicalCountItemRepository extends JpaRepository<KioscoPhysicalCountItemEntity, Long> {

    List<KioscoPhysicalCountItemEntity> findByCountId(Long countId);

    Optional<KioscoPhysicalCountItemEntity> findByCountIdAndProductIdAndColorId(
            Long countId, Long productId, Long colorId);
}
