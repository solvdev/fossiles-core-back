package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Bitácora del libro de ventas en línea: un evento de un periodo de revisión (abrir, cerrar, reabrir, borrar), un cambio
 * de celda (valor anterior y nuevo) o un bloqueo manual de línea.
 */
@Entity
@Table(name = "online_ledger_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineLedgerLogEntity {

    public static final String OPEN = "OPEN";
    public static final String CLOSE = "CLOSE";
    public static final String REOPEN = "REOPEN";
    public static final String DELETE = "DELETE";
    public static final String EDIT = "EDIT";
    public static final String LOCK = "LOCK";
    public static final String UNLOCK = "UNLOCK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "period_label", length = 100)
    private String periodLabel;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "sale_number", length = 30)
    private String saleNumber;

    @Column(name = "event_type", nullable = false, length = 10)
    private String eventType;

    @Column(name = "field_key", length = 60)
    private String fieldKey;

    @Column(name = "field_label", length = 80)
    private String fieldLabel;

    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = GuatemalaDateTime.now();
    }
}
