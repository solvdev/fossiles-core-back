package com.fossiles.fossilescorebackend.application.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Regiones para agrupar las visitas de conteo físico: la supervisora recorre una región seguida en vez de ir
 * a la capital, luego a un departamento y regresar. Se deduce del departamento (y municipio) del kiosco.
 */
public enum KioskCountRegion {
    METROPOLITANA("Área metropolitana"),
    CENTRAL("Chimaltenango y Sacatepéquez"),
    OCCIDENTE("Occidente"),
    SUR("Costa sur"),
    ORIENTE("Oriente"),
    NORTE("Norte");

    private final String label;

    KioskCountRegion(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private static final Set<String> CENTRAL_DEPS = Set.of("CHIMALTENANGO", "SACATEPEQUEZ");
    private static final Set<String> OCCIDENTE_DEPS = Set.of("QUETZALTENANGO", "RETALHULEU", "SUCHITEPEQUEZ",
            "SOLOLA", "QUICHE", "HUEHUETENANGO", "SAN MARCOS", "TOTONICAPAN");
    private static final Set<String> SUR_DEPS = Set.of("ESCUINTLA");
    private static final Set<String> ORIENTE_DEPS = Set.of("JALAPA", "JUTIAPA", "CHIQUIMULA", "ZACAPA",
            "EL PROGRESO", "SANTA ROSA", "IZABAL");
    private static final Set<String> NORTE_DEPS = Set.of("ALTA VERAPAZ", "BAJA VERAPAZ", "PETEN");

    /** Municipios de Escuintla que en el catálogo de ubicaciones aparecen bajo "Guatemala". */
    private static final Set<String> SUR_MUNICIPIOS = Set.of("SANTA LUCIA COTZUMALGUAPA");

    public static KioskCountRegion of(String departamento, String municipio) {
        String muni = normalize(municipio);
        if (SUR_MUNICIPIOS.contains(muni)) {
            return SUR;
        }
        String dep = normalize(departamento);
        if (CENTRAL_DEPS.contains(dep)) return CENTRAL;
        if (OCCIDENTE_DEPS.contains(dep)) return OCCIDENTE;
        if (SUR_DEPS.contains(dep)) return SUR;
        if (ORIENTE_DEPS.contains(dep)) return ORIENTE;
        if (NORTE_DEPS.contains(dep)) return NORTE;
        // Guatemala (incluye Mixco, Villa Nueva, Santa Catarina Pinula) y cualquier dato vacío.
        return METROPOLITANA;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }
}
