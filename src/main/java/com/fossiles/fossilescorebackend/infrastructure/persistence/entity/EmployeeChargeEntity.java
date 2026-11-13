package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Deuda de un empleado por una compra de colaborador. Con DESCUENTO la planilla la rebaja; TRANSFERENCIA o
 * EFECTIVO se cobran fuera de planilla. Sin forma definida queda a la espera de Contabilidad.
 */
@Entity
@Table(name = "employee_charge")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeChargeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "settlement_method", length = 20)
    private String settlementMethod;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDIENTE";

    @Column(name = "charged_on", nullable = false)
    private LocalDate chargedOn;

    /** Solo en cargos manuales: DESCUADRE_INVENTARIO, FALTANTE_CAJA, DANO u OTRO. */
    @Column(length = 30)
    private String concept;

    @Column(length = 500)
    private String reason;

    @Column(length = 100)
    private String reference;

    /** El colaborador aceptó el descuento; Contabilidad guarda la constancia aparte. */
    @Column(name = "employee_authorized", nullable = false)
    @Builder.Default
    private Boolean employeeAuthorized = false;

    @Column(name = "authorization_note", length = 200)
    private String authorizationNote;

    /** Tope que la planilla descuenta de este cargo en cada pago; vacío: lo que alcance. */
    @Column(name = "installment_amount", precision = 12, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = GuatemalaDateTime.now();
    }
}
