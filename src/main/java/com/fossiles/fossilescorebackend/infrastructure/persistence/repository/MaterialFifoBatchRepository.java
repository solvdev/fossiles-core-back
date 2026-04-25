package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialFifoBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialFifoBatchRepository extends JpaRepository<MaterialFifoBatch, Long> {
    
    /**
     * Obtiene todos los lotes FIFO de un material ordenados por fecha de entrada (más antiguos primero)
     * y que tengan cantidad disponible > 0
     */
    @Query("SELECT b FROM MaterialFifoBatch b WHERE b.materialId = :materialId " +
           "AND b.quantityAvailable > 0 ORDER BY b.entryDate ASC, b.id ASC")
    List<MaterialFifoBatch> findAvailableBatchesByMaterialIdOrderByEntryDate(@Param("materialId") Long materialId);
    
    /**
     * Obtiene todos los lotes FIFO de un material (incluyendo los agotados)
     */
    List<MaterialFifoBatch> findByMaterialIdOrderByEntryDateAscIdAsc(Long materialId);
    
    /**
     * Obtiene lotes por referencia
     */
    List<MaterialFifoBatch> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}

