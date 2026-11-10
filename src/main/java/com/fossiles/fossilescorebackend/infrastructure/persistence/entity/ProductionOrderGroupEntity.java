package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * OP consolidada: agrupa varias OPV (o varias OPC) de Luis Felipe para producirlas como una sola.
 * Las órdenes originales siguen vivas con su envío, su cobro y su empaque; esto solo las junta
 * para planificar, imprimir y trabajar en mesa.
 */
@Entity
@Table(name = "production_order_group")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderGroupEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISSOLVED = "DISSOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    /** OPV | OPC */
    @Column(nullable = false, length = 10)
    private String family;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String observations;

    /** Códigos de las OP al crearla; queda como historial al desagrupar. */
    @Column(name = "member_codes", length = 1000)
    private String memberCodes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "dissolved_by")
    private Long dissolvedBy;

    @Column(name = "dissolved_at")
    private LocalDateTime dissolvedAt;
}
