package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public interface ProductionOrderGroupMemberRepository extends JpaRepository<ProductionOrderGroupMemberEntity, Long> {

    List<ProductionOrderGroupMemberEntity> findByGroupIdOrderByIdAsc(Long groupId);

    List<ProductionOrderGroupMemberEntity> findByGroupIdIn(Collection<Long> groupIds);

    List<ProductionOrderGroupMemberEntity> findByProductionOrderIdIn(Collection<Long> orderIds);

    @Modifying
    @Query("DELETE FROM ProductionOrderGroupMemberEntity m WHERE m.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") Long groupId);

    /** OP → id de su consolidada activa (las que no están en ninguna no aparecen). */
    default Map<Long, Long> groupIdByOrderId(Collection<Long> orderIds) {
        Map<Long, Long> out = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return out;
        }
        findByProductionOrderIdIn(orderIds).forEach(m -> out.put(m.getProductionOrderId(), m.getGroupId()));
        return out;
    }
}
