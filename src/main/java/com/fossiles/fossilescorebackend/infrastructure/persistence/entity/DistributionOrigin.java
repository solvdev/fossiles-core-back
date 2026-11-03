package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import java.util.Locale;

/**
 * Origen de una distribución de producto ({@code product_distribution.origin}).
 * <ul>
 *   <li>{@link #MANUAL}: creada a mano en Distribuciones, o la que el sistema arma para los envíos de una OPK.</li>
 *   <li>{@link #REPOSICION}: creada desde la sugerencia de reposición de kioscos.</li>
 * </ul>
 */
public final class DistributionOrigin {

    public static final String MANUAL = "MANUAL";
    public static final String REPOSICION = "REPOSICION";

    private DistributionOrigin() {
    }

    /** Normaliza el valor recibido; cualquier cosa distinta de REPOSICION es MANUAL. */
    public static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return REPOSICION.equals(v) ? REPOSICION : MANUAL;
    }

    public static boolean isValid(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return MANUAL.equals(v) || REPOSICION.equals(v);
    }
}
