package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Registro de cada descarga (cierre o nueva descarga del mismo contenido). */
@Entity
@Table(name = "recon_export")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconExportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_id", nullable = false)
    private Long batchId;
    /** Huella SHA-256 del contenido (filas del detalle), no de los bytes del xlsx. */
    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;
    @Column(name = "row_count", nullable = false)
    private int rowCount;
    @Column(name = "kind", nullable = false, length = 30)
    @Builder.Default
    private String kind = "CIERRE";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
