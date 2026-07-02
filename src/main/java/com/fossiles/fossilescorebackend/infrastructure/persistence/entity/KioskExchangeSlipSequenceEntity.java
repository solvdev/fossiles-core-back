package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "kiosk_exchange_slip_sequence")
@IdClass(KioskExchangeSlipSequenceEntity.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangeSlipSequenceEntity {

    @Id
    @Column(name = "kiosk_location_id")
    private Long kioskLocationId;

    @Id
    @Column(name = "sequence_year")
    private Integer sequenceYear;

    @Column(name = "last_number", nullable = false)
    @Builder.Default
    private Integer lastNumber = 0;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long kioskLocationId;
        private Integer sequenceYear;
    }
}
