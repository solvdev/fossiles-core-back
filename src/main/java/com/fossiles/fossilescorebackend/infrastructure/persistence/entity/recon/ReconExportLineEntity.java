package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

@Entity
@Table(name = "recon_export_line")
@IdClass(ReconExportLineEntity.Key.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconExportLineEntity {
    @Id
    @Column(name = "export_id", nullable = false)
    private Long exportId;
    @Id
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long exportId;
        private Long caseId;
    }
}
