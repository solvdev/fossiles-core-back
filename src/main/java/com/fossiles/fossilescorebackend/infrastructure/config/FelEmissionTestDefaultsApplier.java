package com.fossiles.fossilescorebackend.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Credenciales demo INFILE (manual consumo web service) para el bloque {@code fel.emission.sandbox.*}
 * cuando no se configuran explícitamente. En implementación la serie del DTE suele ser
 * {@code ** PRUEBAS **} — sin validez fiscal. Se aplican siempre (no solo con test-mode global),
 * ya que un kiosko individual puede seguir en modo piloto aunque el resto ya esté en producción.
 */
@Component
@RequiredArgsConstructor
public class FelEmissionTestDefaultsApplier {

    private final FelEmissionProperties properties;

    @PostConstruct
    void applySandboxDefaults() {
        FelEmissionProperties.Sandbox sandbox = properties.getSandbox();
        if (isBlank(sandbox.getSignKey())) {
            sandbox.setSignKey("6456d06325f89acb30fbb2e7e7bec3c9");
        }
        if (isBlank(sandbox.getSignAlias())) {
            sandbox.setSignAlias("DEMO_FEL");
        }
        if (isBlank(sandbox.getCertUsuario())) {
            sandbox.setCertUsuario("DEMO_FEL");
        }
        if (isBlank(sandbox.getCertLlave())) {
            sandbox.setCertLlave("E5DC9FFBA5F3653E27DF2FC1DCAC824D");
        }
        if (isBlank(sandbox.getNitEmisor())) {
            sandbox.setNitEmisor("123456789");
        }
        if (isBlank(sandbox.getNombreEmisor())) {
            sandbox.setNombreEmisor("PRUEBA, SOCIEDAD ANONIMA");
        }
        if (isBlank(sandbox.getNombreComercial())) {
            sandbox.setNombreComercial("PRUEBA");
        }
        if (isBlank(sandbox.getDireccion())) {
            sandbox.setDireccion("DIAGONAL 29 00-22 17 CALZADA LA PAZ Guatemala, GUATEMALA");
        }
        if (sandbox.getFrases() == null || sandbox.getFrases().isEmpty()) {
            List<FelEmissionProperties.Frase> frases = new ArrayList<>();
            FelEmissionProperties.Frase f1 = new FelEmissionProperties.Frase();
            f1.setTipo(1);
            f1.setEscenario(1);
            frases.add(f1);
            sandbox.setFrases(frases);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
