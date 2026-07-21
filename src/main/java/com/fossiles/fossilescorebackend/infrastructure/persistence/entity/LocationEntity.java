package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "locations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 15)
    private String code;

    @Column(length = 255)
    private String name;

    @Column(length = 255)
    private String departamento;

    @Column(length = 255)
    private String municipio;

    @Column(length = 10)
    private String zona;

    @Column(length = 100)
    private String categoria;

    @Column(name = "encargado_id")
    private Long encargadoId;

    @Column(name = "fel_establishment_code", length = 10)
    private String felEstablishmentCode;

    @Column(name = "fel_establishment_name", length = 255)
    private String felEstablishmentName;

    @Column(name = "fel_address_line", length = 500)
    private String felAddressLine;

    @Column(name = "fel_municipio", length = 255)
    private String felMunicipio;

    @Column(name = "fel_departamento", length = 255)
    private String felDepartamento;

    /** Código de serie de control interno (ej. "A1", "B"), independiente de la serie FEL. */
    @Column(name = "internal_series_code", length = 10)
    private String internalSeriesCode;

    /** true = ventas POS no cuentan en métricas de producción (piloto por kiosko). */
    @Column(name = "pos_test_mode", nullable = false)
    @Builder.Default
    private Boolean posTestMode = false;

    /** Fondo inicial de caja POS al abrir turno (por kiosko). */
    @Column(name = "pos_opening_cash_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal posOpeningCashAmount = new BigDecimal("300");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encargado_id", insertable = false, updatable = false)
    private UserEntity encargado;
}

