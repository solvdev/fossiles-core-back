package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "locations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 15)
    private String code;

    @Column(length = 255)
    private String name;

    @Column(length = 255)
    private String departamento;

    @Column(length = 255)
    private String municipio;

    @Column(length = 10)
    private String zona;

    @Column(length = 100)
    private String categoria;

    @Column(name = "encargado_id")
    private Long encargadoId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encargado_id", insertable = false, updatable = false)
    private UserEntity encargado;
}

