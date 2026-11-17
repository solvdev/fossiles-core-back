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

/** Forma de pago configurable: decide qué datos pide el pago y si imprime cheque. */
@Entity
@Table(name = "payment_method")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "requires_bank", nullable = false)
    @Builder.Default
    private Boolean requiresBank = true;

    /** Pide número de cheque, de boleta o referencia. */
    @Column(name = "requires_reference", nullable = false)
    @Builder.Default
    private Boolean requiresReference = false;

    @Column(name = "prints_cheque", nullable = false)
    @Builder.Default
    private Boolean printsCheque = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
