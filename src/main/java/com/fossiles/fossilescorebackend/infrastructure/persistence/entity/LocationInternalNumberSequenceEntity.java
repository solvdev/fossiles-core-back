package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Correlativo del número de control interno (tax_invoice.internal_number) por código de
 * serie de ubicación (locations.internal_series_code). Solo avanza cuando se emite una factura.
 */
@Entity
@Table(name = "location_internal_number_sequence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationInternalNumberSequenceEntity {
    @Id
    @Column(name = "series_code", length = 10)
    private String seriesCode;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber;
}
