package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "production_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "order_type", length = 20, nullable = false)
    private String orderType; // CINCHOS, MARCAS, NORMAL

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", length = 200)
    private String customerName; // Para casos donde no hay cliente registrado

    /** Número de envío OPV vendedor (ENVP-00001), correlativo aparte del código de orden. */
    @Column(name = "vendor_shipment_number", length = 30)
    private String vendorShipmentNumber;

    /** Documento OPV/OPI impreso anulado sin fila en product_shipment. */
    @Column(name = "vendor_shipment_voided_at")
    private LocalDateTime vendorShipmentVoidedAt;

    @Column(name = "vendor_shipment_voided_by")
    private Long vendorShipmentVoidedBy;

    @Column(name = "seller_name", length = 150)
    private String sellerName; // Vendedor

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "observations", length = 1000)
    private String observations;

    @Column(name = "distribution_id")
    private Long distributionId;

    @Column(name = "materials_consumed")
    @Builder.Default
    private Boolean materialsConsumed = false;

    @Column(name = "materials_consumed_at")
    private LocalDateTime materialsConsumedAt;

    @Column(length = 20)
    private String status; // PENDING, IN_PROGRESS, IN_QA, COMPLETED, CANCELLED

    @Column(name = "warehouse_receipt_closed_at")
    private LocalDateTime warehouseReceiptClosedAt;

    /** Prioridad al distribuir día (menor número = antes en cola entre OP). */
    @Column(name = "scheduling_priority")
    private Integer schedulingPriority;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

