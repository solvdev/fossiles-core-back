package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerAccountEntryRepository extends JpaRepository<CustomerAccountEntryEntity, Long> {

    List<CustomerAccountEntryEntity> findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(
            Long customerId, String status);

    List<CustomerAccountEntryEntity> findByCustomerIdOrderByEntryDateAscIdAsc(Long customerId);

    List<CustomerAccountEntryEntity> findByAppliedToEntryIdAndStatus(Long appliedToEntryId, String status);

    @Query("""
            SELECT e FROM CustomerAccountEntryEntity e
            WHERE e.customerId = :customerId
              AND e.status = 'ACTIVE'
              AND e.entryType = 'CHARGE'
              AND e.productionOrderId = :productionOrderId
              AND ((:partialReleaseId IS NULL AND e.partialReleaseId IS NULL)
                   OR e.partialReleaseId = :partialReleaseId)
              AND ((:productShipmentId IS NULL AND e.productShipmentId IS NULL)
                   OR e.productShipmentId = :productShipmentId)
            """)
    Optional<CustomerAccountEntryEntity> findActiveCharge(
            Long customerId,
            Long productionOrderId,
            Long partialReleaseId,
            Long productShipmentId);

    @Query("""
            SELECT DISTINCT po.customerId FROM ProductionOrderEntity po
            WHERE po.customerId IS NOT NULL
              AND UPPER(po.sellerName) LIKE '%LUIS FELIPE%'
              AND UPPER(po.orderType) NOT IN ('INTERNA', 'CLIENTE_KIOSKO')
            """)
    List<Long> findLuisFelipeReceivableCustomerIds();

    @Query("""
            SELECT DISTINCT e.customerId FROM CustomerAccountEntryEntity e
            WHERE e.status = 'ACTIVE'
              AND (
                LOWER(COALESCE(e.invoiceNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(e.vendorShipmentNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(e.documentNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(e.reference, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    List<Long> findCustomerIdsByDocumentReference(@Param("q") String q);
}
