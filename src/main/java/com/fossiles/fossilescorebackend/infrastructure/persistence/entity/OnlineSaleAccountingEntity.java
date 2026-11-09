package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lo que Contabilidad anota sobre una venta en línea en el libro de ventas: envío real, comisión del transporte,
 * liquidación (boleta, depósito) y faltantes. Una fila por venta, creada al primer cambio. La venta
 * ({@link OnlineSaleEntity}) no se modifica desde el libro.
 */
@Entity
@Table(name = "online_sale_accounting")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSaleAccountingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "online_sale_id", nullable = false, unique = true)
    private Long onlineSaleId;

    /** Envío real: lo que cobró el transporte. */
    @Column(name = "real_shipping_cost", precision = 12, scale = 2)
    private BigDecimal realShippingCost;

    /** Comisión real del transporte por el cobro. */
    @Column(name = "carrier_commission", precision = 12, scale = 2)
    private BigDecimal carrierCommission;

    @Column(name = "accounting_note", length = 1000)
    private String accountingNote;

    /** Fecha en que se envió. Vacío = fecha de despacho del sistema. */
    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    /** Forma de pago real anotada por Contabilidad. Vacío = la de la venta. */
    @Column(name = "payment_form", length = 100)
    private String paymentForm;

    @Column(name = "liquidation_date")
    private LocalDate liquidationDate;

    /** No. de boleta o referencia de la liquidación. */
    @Column(name = "deposit_slip_number", length = 100)
    private String depositSlipNumber;

    /** Observación de la entrega (ENTREGADO, etc.). */
    @Column(name = "delivery_note", length = 500)
    private String deliveryNote;

    @Column(name = "real_deposit_amount", precision = 12, scale = 2)
    private BigDecimal realDepositAmount;

    @Column(name = "deposit_note", length = 500)
    private String depositNote;

    /** Positivo = faltante (se descuenta de la comisión del vendedor). Negativo = sobrante. */
    @Column(name = "shortage_amount", precision = 12, scale = 2)
    private BigDecimal shortageAmount;

    @Column(name = "shortage_note", length = 500)
    private String shortageNote;

    @Column(name = "reviewed", nullable = false)
    @Builder.Default
    private Boolean reviewed = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        updatedAt = createdAt;
        if (reviewed == null) reviewed = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
        if (reviewed == null) reviewed = false;
    }
}
