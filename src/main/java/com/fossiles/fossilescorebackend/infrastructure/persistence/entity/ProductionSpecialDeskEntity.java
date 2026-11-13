package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Mesa especial (p. ej. maquila): tiene número fijo fuera del rango de planta 1..N y no entra
 * al reparto automático ni al cupo de la planta. Solo recibe tareas que se le mandan a mano.
 */
@Entity
@Table(
        name = "production_special_desk",
        uniqueConstraints = @UniqueConstraint(name = "uq_production_special_desk_desk", columnNames = {"desk"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionSpecialDeskEntity {

    public static final String KIND_MAQUILA = "MAQUILA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer desk;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (kind == null) kind = KIND_MAQUILA;
        if (active == null) active = Boolean.TRUE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
