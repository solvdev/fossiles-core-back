package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kiosk_import_batch")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskImportBatchEntity {

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REVERTED = "REVERTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "year", nullable = false)
    private Integer periodYear;

    @Column(name = "month", nullable = false)
    private Integer periodMonth;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_APPLIED;

    @Column(name = "sales_rows", nullable = false)
    @Builder.Default
    private Integer salesRows = 0;

    @Column(name = "config_rows", nullable = false)
    @Builder.Default
    private Integer configRows = 0;

    @Column(name = "cost_rows", nullable = false)
    @Builder.Default
    private Integer costRows = 0;

    @Column(name = "warnings_json", columnDefinition = "TEXT")
    private String warningsJson;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "reverted_by")
    private Long revertedBy;

    @Column(name = "reverted_at")
    private LocalDateTime revertedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
