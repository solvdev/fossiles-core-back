package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "print_format")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrintFormatEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType; // INVOICE, PURCHASE_ORDER, PRODUCTION_ORDER, QUOTE, etc.

    @Column(name = "format_name", nullable = false, length = 100)
    private String formatName;

    @Column(name = "template_path", length = 500)
    private String templatePath;

    @Column(name = "paper_size", length = 20)
    private String paperSize; // A4, LETTER, etc.

    @Column(name = "margins", length = 50)
    private String margins; // JSON: {"top": 10, "bottom": 10, "left": 10, "right": 10}

    @Column(name = "header", columnDefinition = "TEXT")
    private String header; // HTML or template content

    @Column(name = "footer", columnDefinition = "TEXT")
    private String footer; // HTML or template content

    @Column(name = "logo_path", length = 500)
    private String logoPath;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isDefault == null) {
            isDefault = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

