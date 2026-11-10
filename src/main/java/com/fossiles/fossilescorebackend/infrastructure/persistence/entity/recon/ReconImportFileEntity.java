package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "recon_import_file")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconImportFileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private Recon.SourceType sourceType;
    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;
    @Column(name = "rows_matched", nullable = false)
    private int rowsMatched;
    @Column(name = "imported_by")
    private Long importedBy;
    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @PrePersist
    void onCreate() {
        if (importedAt == null) importedAt = LocalDateTime.now();
    }
}
