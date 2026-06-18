package com.fossiles.fossilescorebackend.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Credenciales demo INFILE (manual consumo web service) cuando {@code fel.emission.test-mode=true}.
 * En implementación la serie del DTE suele ser {@code ** PRUEBAS **} — sin validez fiscal.
 */
@Component
@RequiredArgsConstructor
public class FelEmissionTestDefaultsApplier {

    private final FelEmissionProperties properties;

    @PostConstruct
    void applyDemoDefaultsWhenTestMode() {
        if (!properties.isTestMode()) {
            return;
        }
        if (isBlank(properties.getSignKey())) {
            properties.setSignKey("6456d06325f89acb30fbb2e7e7bec3c9");
        }
        if (isBlank(properties.getSignAlias())) {
            properties.setSignAlias("DEMO_FEL");
        }
        if (isBlank(properties.getCertUsuario())) {
            properties.setCertUsuario("DEMO_FEL");
        }
        if (isBlank(properties.getCertLlave())) {
            properties.setCertLlave("E5DC9FFBA5F3653E27DF2FC1DCAC824D");
        }
        if (isBlank(properties.getNitEmisor())) {
            properties.setNitEmisor("123456789");
        }
        if (isBlank(properties.getNombreEmisor())) {
            properties.setNombreEmisor("PRUEBA, SOCIEDAD ANONIMA");
        }
        if (isBlank(properties.getNombreComercial())) {
            properties.setNombreComercial("PRUEBA");
        }
        if (isBlank(properties.getDireccion())) {
            properties.setDireccion("DIAGONAL 29 00-22 17 CALZADA LA PAZ Guatemala, GUATEMALA");
        }
        if (properties.getFrases() == null || properties.getFrases().isEmpty()) {
            List<FelEmissionProperties.Frase> frases = new ArrayList<>();
            FelEmissionProperties.Frase f1 = new FelEmissionProperties.Frase();
            f1.setTipo(1);
            f1.setEscenario(1);
            frases.add(f1);
            properties.setFrases(frases);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
