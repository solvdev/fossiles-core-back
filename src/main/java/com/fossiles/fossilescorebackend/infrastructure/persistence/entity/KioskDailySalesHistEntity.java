package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Venta diaria historica (Excel), IVA incluido. Celda vacia = sin fila. */
@Entity
@Table(name = "kiosk_daily_sales_hist")
@IdClass(KioskDailySalesHistEntity.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskDailySalesHistEntity {

    @Id
    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Id
    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long siteId;
        private LocalDate saleDate;
    }
}
