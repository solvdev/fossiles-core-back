package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiosk_cash_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskCashSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "opened_by_user_id", nullable = false)
    private Long openedByUserId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "opening_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingAmount;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "counted_cash", precision = 12, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "expected_cash", precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "variance", precision = 12, scale = 2)
    private BigDecimal variance;

    @Column(name = "close_notes", length = 1500)
    private String closeNotes;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @PrePersist
    protected void onCreate() {
        if (openedAt == null) {
            openedAt = GuatemalaDateTime.now();
        }
        if (openingAmount == null) {
            openingAmount = new BigDecimal("300");
        }
        if (status == null || status.isBlank()) {
            status = "OPEN";
        }
    }
}
