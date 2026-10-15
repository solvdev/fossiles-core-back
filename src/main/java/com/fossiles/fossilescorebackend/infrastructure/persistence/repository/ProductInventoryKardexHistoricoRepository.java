package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardexHistorico;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProductInventoryKardexHistoricoRepository extends JpaRepository<ProductInventoryKardexHistorico, Long> {

    @Query("""
        SELECT h FROM ProductInventoryKardexHistorico h
        WHERE (:locationId IS NULL OR h.locationId = :locationId)
          AND (:productId IS NULL OR h.productId = :productId)
          AND (:movementType IS NULL OR h.movementType = :movementType)
          AND (:archiveReference IS NULL OR h.archiveReference = :archiveReference)
          AND (CAST(:from AS timestamp) IS NULL OR h.movementDate >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR h.movementDate < :to)
          AND (:reference IS NULL OR LOWER(h.referenceNumber) LIKE LOWER(CONCAT('%', :reference, '%')))
        """)
    Page<ProductInventoryKardexHistorico> search(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("movementType") String movementType,
            @Param("archiveReference") String archiveReference,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("reference") String reference,
            Pageable pageable);

    /** Limpiezas archivadas: referencia, fecha y cantidad de movimientos. */
    @Query("""
        SELECT h.archiveReference, MAX(h.archivedAt), COUNT(h)
        FROM ProductInventoryKardexHistorico h
        GROUP BY h.archiveReference
        ORDER BY MAX(h.archivedAt) DESC
        """)
    List<Object[]> summarizeArchives();

    @Query("SELECT DISTINCT h.movementType FROM ProductInventoryKardexHistorico h ORDER BY h.movementType")
    List<String> findDistinctMovementTypes();
}
