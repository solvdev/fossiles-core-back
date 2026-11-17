package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Formato de impresión de un cheque: papel en milímetros y posición de cada dato ({@code fieldsJson}). */
@Entity
@Table(name = "cheque_layout")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChequeLayoutEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "width_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal widthMm;

    @Column(name = "height_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal heightMm;

    /** TIMES, ARIAL o COURIER. */
    @Column(name = "font_family", nullable = false, length = 30)
    private String fontFamily;

    @Column(name = "fields_json", nullable = false, columnDefinition = "TEXT")
    private String fieldsJson;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
