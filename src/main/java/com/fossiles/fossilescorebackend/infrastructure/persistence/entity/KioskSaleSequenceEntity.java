package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "kiosk_sale_sequence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSaleSequenceEntity {
    @Id
    @Column(name = "sale_date")
    private LocalDate saleDate;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber;
}
