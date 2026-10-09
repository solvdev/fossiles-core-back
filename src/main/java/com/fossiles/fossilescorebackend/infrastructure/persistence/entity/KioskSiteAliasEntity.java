package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Nombre de columna del Excel (normalizado) que apunta a un sitio. */
@Entity
@Table(name = "kiosk_site_alias")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSiteAliasEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alias_normalized", nullable = false, length = 120)
    private String aliasNormalized;

    @Column(name = "site_id", nullable = false)
    private Long siteId;
}
