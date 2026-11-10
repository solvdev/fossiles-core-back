package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeatherInventoryRepository extends JpaRepository<LeatherInventoryEntity, Long> {

    Optional<LeatherInventoryEntity> findByMaterialId(Long materialId);

    /** Bloquea el saldo del cuero mientras se descuenta o se devuelve. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM LeatherInventoryEntity i WHERE i.materialId = :materialId")
    Optional<LeatherInventoryEntity> findByMaterialIdForUpdate(@Param("materialId") Long materialId);

    List<LeatherInventoryEntity> findByMaterialIdIn(Collection<Long> materialIds);
}
