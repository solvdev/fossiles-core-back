package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Resultado de una regla de validación sobre un caso (última corrida). */
@Entity
@Table(name = "recon_check_result")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconCheckResultEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Column(name = "run_id", nullable = false)
    private Long runId;
    @Column(name = "rule_code", nullable = false, length = 10)
    private String ruleCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Recon.Severity severity;
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 5)
    private Recon.Outcome outcome;
    @Column(name = "expected_value", length = 200)
    private String expectedValue;
    @Column(name = "found_value", length = 200)
    private String foundValue;
    @Column(name = "message", length = 500)
    private String message;
    @Column(name = "recommended_action", length = 300)
    private String recommendedAction;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
