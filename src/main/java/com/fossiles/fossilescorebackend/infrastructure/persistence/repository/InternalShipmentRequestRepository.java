package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InternalShipmentRequestRepository
        extends JpaRepository<InternalShipmentRequestEntity, Long>, InternalShipmentRequestRepositoryCustom {

    @Query("""
            SELECT r FROM InternalShipmentRequestEntity r
            LEFT JOIN FETCH r.lines
            WHERE r.id = :id
            """)
    java.util.Optional<InternalShipmentRequestEntity> findByIdWithLines(@Param("id") Long id);

    @Query("""
            SELECT r FROM InternalShipmentRequestEntity r
            WHERE r.employeeId = :employeeId
              AND r.requestType = 'PLANILLA'
              AND UPPER(r.status) IN ('PENDIENTE', 'APROBADA')
              AND r.requestedAt >= :fromInclusive
              AND r.requestedAt < :toExclusive
            ORDER BY r.requestedAt DESC
            """)
    java.util.List<InternalShipmentRequestEntity> findActivePlanillaRequestsForEmployeeInMonth(
            @Param("employeeId") Long employeeId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive
    );
}
