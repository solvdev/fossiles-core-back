package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "permission")
@Data
// Igualdad por clave de negocio (codigo unico), igual que RoleEntity: ver nota alli.
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Include
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(length = 200)
    private String description;

    @Column(length = 50)
    private String module; // Módulo al que pertenece (INVENTARIOS, COMPRAS, etc.)

    @Column(name = "route_path", length = 200)
    private String routePath; // Ruta asociada en el frontend

    @Column(length = 20)
    private String action; // Acción (VER, CREAR, EDITAR, ELIMINAR, APROBAR)
}

