package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "shipment_number_counter")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentNumberCounterEntity {
    @Id
    @Column(name = "type", length = 32, nullable = false)
    private String type;

    @Column(name = "last_value", nullable = false)
    private Long lastValue;
}
