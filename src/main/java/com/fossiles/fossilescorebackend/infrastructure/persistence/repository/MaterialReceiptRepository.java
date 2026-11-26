package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialReceiptRepository extends JpaRepository<MaterialReceiptEntity, Long> {
    @org.springframework.data.jpa.repository.Query("select r.purchaseOrderId from MaterialReceiptEntity r where r.id = :id")
    Optional<Long> findOrderIdByReceiptId(@org.springframework.data.repository.query.Param("id") Long id);
    List<MaterialReceiptEntity> findByPurchaseOrderId(Long purchaseOrderId);
    Optional<MaterialReceiptEntity> findById(Long id);

    /** Última fecha de recepción activa por orden: {orderId, fecha}. */
    @org.springframework.data.jpa.repository.Query("select r.purchaseOrderId, max(r.receiptDate) from MaterialReceiptEntity r "
            + "where r.status = 'ACTIVE' and r.purchaseOrderId in :orderIds group by r.purchaseOrderId")
    List<Object[]> findLastReceiptDates(@org.springframework.data.repository.query.Param("orderIds") java.util.Collection<Long> orderIds);

    /** Facturas del proveedor en recepciones activas: {orderId, serie, número, fecha}, de la más vieja a la más nueva. */
    @org.springframework.data.jpa.repository.Query("select r.purchaseOrderId, r.invoiceSeries, r.invoiceNumber, r.invoiceDate from MaterialReceiptEntity r "
            + "where r.status = 'ACTIVE' and r.invoiceNumber is not null and r.purchaseOrderId in :orderIds order by r.invoiceDate, r.id")
    List<Object[]> findInvoices(@org.springframework.data.repository.query.Param("orderIds") java.util.Collection<Long> orderIds);
}
