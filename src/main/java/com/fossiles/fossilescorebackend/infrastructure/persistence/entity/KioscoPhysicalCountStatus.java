package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

public enum KioscoPhysicalCountStatus {
    DRAFT,
    /** Conteo capturado en vitrinas; ya no admite edicion hasta nueva sesion. */
    CONTADO,
    REVISADO,
    CERRADO
}
