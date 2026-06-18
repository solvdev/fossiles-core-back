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
}
