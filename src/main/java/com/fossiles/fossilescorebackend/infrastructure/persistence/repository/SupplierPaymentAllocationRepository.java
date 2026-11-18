package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ChequeLayoutEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface SupplierPaymentAllocationRepository extends JpaRepository<SupplierPaymentAllocationEntity, Long> {
    List<SupplierPaymentAllocationEntity> findByPaymentIdIn(Collection<Long> paymentIds);

    List<SupplierPaymentAllocationEntity> findByPaymentId(Long paymentId);

    /** Cualquier fila cuenta, aunque el pago esté anulado: esa renta ya no se toca. */
    boolean existsByPayableDocumentId(Long payableDocumentId);

    /** Lo ya pagado por orden: {orderId, suma}; los pagos anulados no cuentan. */
    @Query("SELECT a.purchaseOrderId, SUM(a.amount) FROM SupplierPaymentAllocationEntity a, SupplierPaymentEntity p "
            + "WHERE a.paymentId = p.id AND p.status <> 'ANULADO' AND a.purchaseOrderId IS NOT NULL GROUP BY a.purchaseOrderId")
    List<Object[]> paidByOrder();

    /** Lo ya pagado por documento por pagar: {documentId, suma}; los pagos anulados no cuentan. */
    @Query("SELECT a.payableDocumentId, SUM(a.amount) FROM SupplierPaymentAllocationEntity a, SupplierPaymentEntity p "
            + "WHERE a.paymentId = p.id AND p.status <> 'ANULADO' AND a.payableDocumentId IS NOT NULL GROUP BY a.payableDocumentId")
    List<Object[]> paidByDocument();

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SupplierPaymentAllocationEntity a, SupplierPaymentEntity p "
            + "WHERE a.paymentId = p.id AND p.status <> 'ANULADO' AND a.payableDocumentId = :documentId")
    java.math.BigDecimal paidForDocument(@Param("documentId") Long documentId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SupplierPaymentAllocationEntity a, SupplierPaymentEntity p "
            + "WHERE a.paymentId = p.id AND p.status <> 'ANULADO' AND a.purchaseOrderId = :orderId")
    java.math.BigDecimal paidForOrder(@Param("orderId") Long orderId);
}
