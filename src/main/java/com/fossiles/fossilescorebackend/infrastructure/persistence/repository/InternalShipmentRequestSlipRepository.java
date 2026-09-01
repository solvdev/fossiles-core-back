package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestSlipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InternalShipmentRequestSlipRepository extends JpaRepository<InternalShipmentRequestSlipEntity, Long> {

    Optional<InternalShipmentRequestSlipEntity> findBySlipNumber(String slipNumber);

    boolean existsBySlipNumber(String slipNumber);

    @Query("SELECT s.slipNumber FROM InternalShipmentRequestSlipEntity s WHERE s.slipNumber IS NOT NULL")
    List<String> findAllSlipNumbers();

    long countByStatus(String status);

    Optional<InternalShipmentRequestSlipEntity> findTopByOrderByIdDesc();
}
