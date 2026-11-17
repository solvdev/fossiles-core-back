package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Cuánto de un pago se aplica a una orden de compra. */
@Entity
@Table(name = "supplier_payment_allocation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPaymentAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "purchase_order_id", nullable = false)
    private Long purchaseOrderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
