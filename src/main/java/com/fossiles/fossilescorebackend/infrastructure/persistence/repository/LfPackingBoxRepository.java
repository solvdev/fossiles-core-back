package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LfPackingBoxRepository extends JpaRepository<LfPackingBoxEntity, Long> {

    List<LfPackingBoxEntity> findByProductionOrderIdOrderByBoxNoAsc(Long productionOrderId);

    List<LfPackingBoxEntity> findByProductionOrderIdInOrderByProductionOrderIdAscBoxNoAsc(Collection<Long> productionOrderIds);

    Optional<LfPackingBoxEntity> findByIdAndProductionOrderId(Long id, Long productionOrderId);

    Optional<LfPackingBoxEntity> findTopByProductionOrderIdOrderByBoxNoDesc(Long productionOrderId);

    List<LfPackingBoxEntity> findByIdInAndProductionOrderId(Collection<Long> ids, Long productionOrderId);
}
