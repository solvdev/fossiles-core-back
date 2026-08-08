package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrderEntity, Long> {
    Optional<ProductionOrderEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<ProductionOrderEntity> findByOrderType(String orderType);
    List<ProductionOrderEntity> findByStatus(String status);
    List<ProductionOrderEntity> findByCustomerId(Long customerId);

    @Query("""
            SELECT po FROM ProductionOrderEntity po
            WHERE UPPER(COALESCE(po.sellerName, '')) LIKE '%LUIS FELIPE%'
              AND UPPER(COALESCE(po.orderType, '')) IN ('MARCAS', 'OPV')
              AND UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
            ORDER BY po.deliveryDate DESC NULLS LAST, po.createdAt DESC
            """)
    List<ProductionOrderEntity> findOpvCatalogOrders();

    @Query("""
            SELECT po FROM ProductionOrderEntity po
            WHERE po.customerId IS NOT NULL
              AND UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
            ORDER BY po.deliveryDate DESC NULLS LAST, po.createdAt DESC
            """)
    List<ProductionOrderEntity> findOrdersWithCustomer();
    Optional<ProductionOrderEntity> findByDistributionId(Long distributionId);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status IN :statuses ORDER BY po.deliveryDate ASC, po.createdAt ASC")
    List<ProductionOrderEntity> findByStatusIn(@Param("statuses") List<String> statuses);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status NOT IN ('CANCELLED') ORDER BY po.createdAt DESC")
    List<ProductionOrderEntity> findActiveOrders();

    @Query("SELECT po.vendorShipmentNumber FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber IS NOT NULL")
    List<String> findAllVendorShipmentNumbers();

    boolean existsByVendorShipmentNumber(String vendorShipmentNumber);

    @Query("SELECT COUNT(po) > 0 FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber = :num AND po.id <> :excludeId")
    boolean existsByVendorShipmentNumberAndIdNot(@Param("num") String num, @Param("excludeId") Long excludeId);

    @Query("""
            SELECT DISTINCT po.customerId FROM ProductionOrderEntity po
            WHERE po.customerId IS NOT NULL
              AND (
                LOWER(COALESCE(po.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.vendorShipmentNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    List<Long> findCustomerIdsByCodeOrVendorShipment(@Param("q") String q);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.id = :id")
    Optional<ProductionOrderEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Búsqueda liviana para filtros de Preparar envíos (sin ítems ni joins pesados).
     * kind: OPV | OPI | OPC | OPCK | OPK
     * Columnas: id, code, customer_name, seller_name, status, order_type, vendor_shipment_number
     */
    @Query(value = """
            SELECT po.id,
                   po.code,
                   po.customer_name,
                   po.seller_name,
                   po.status,
                   po.order_type,
                   po.vendor_shipment_number
            FROM production_order po
            WHERE UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
              AND (
                (:kind = 'OPI' AND UPPER(TRIM(COALESCE(po.order_type, ''))) = 'INTERNA')
                OR (
                  :kind = 'OPCK'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) = 'CLIENTE_KIOSKO'
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPCK%'
                  )
                )
                OR (
                  :kind = 'OPC'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
                    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
                  )
                )
                OR (
                  :kind = 'OPV'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('MARCAS', 'OPV')
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPV-%'
                    OR (
                      UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
                      AND UPPER(TRIM(COALESCE(po.order_type, ''))) NOT IN (
                        'CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS', 'INTERNA', 'CLIENTE_KIOSKO'
                      )
                    )
                  )
                )
                OR (
                  :kind = 'OPK'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) = 'NORMAL'
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPK-%'
                  )
                  AND UPPER(TRIM(COALESCE(po.order_type, ''))) NOT IN ('MARCAS', 'OPV')
                  AND UPPER(COALESCE(po.code, '')) NOT LIKE 'OPV-%'
                  AND UPPER(COALESCE(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%'
                )
              )
              AND (
                :q = ''
                OR LOWER(COALESCE(po.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.customer_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.seller_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.vendor_shipment_number, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.status, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            ORDER BY po.updated_at DESC NULLS LAST, po.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchForPrepare(
            @Param("kind") String kind,
            @Param("q") String q,
            @Param("limit") int limit
    );
}

