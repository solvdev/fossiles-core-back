package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.infrastructure.util.FelTextEncodingHelper;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "fel.emission")
public class FelEmissionProperties {

    private boolean enabled = false;
    /**
     * Apagador de emergencia: si es true, TODAS las ventas certifican con las credenciales
     * sandbox ({@link #sandbox}) sin importar el flag por kiosko (locations.pos_test_mode).
     */
    private boolean testMode = false;
    /** Si true, la venta POS falla cuando no se puede certificar el DTE. */
    private boolean required = true;

    /**
     * Emisión FEL de NCRE/NDEB. Apagado por defecto: aún no hay flujo de producto;
     * las guardias SAT (receptor ≠ CF) quedan listas para cuando se habilite.
     * Nota: CxC CREDIT_NOTE contable ≠ NCRE FEL.
     */
    private boolean creditDebitNotesEnabled = false;

    private String signUrl = "https://signer-emisores.feel.com.gt/sign_solicitud_firmas/firma_xml";
    private String certifyUrl = "https://certificador.feel.com.gt/fel/certificacion/v2/dte/";
    /** Endpoint INFILE anulación v2 (alineado a certificación v2). */
    private String annulUrl = "https://certificador.feel.com.gt/fel/anulacion/v2/dte/";

    /** Credenciales de PRODUCCIÓN (usadas salvo que el kiosko esté en modo piloto). */
    private String signKey = "";
    private String signAlias = "";

    private String certUsuario = "";
    private String certLlave = "";

    private String nitEmisor = "";
    private String nombreEmisor = "";
    private String nombreComercial = "";
    private String correoEmisor = "";
    private String afiliacionIva = "GEN";
    private String codigoEstablecimiento = "1";
    private String direccion = "";
    private String codigoPostal = "0";
    private String municipio = "Guatemala";
    private String departamento = "GUATEMALA";
    private String pais = "GT";
    private String documentType = "FACT";
    private String moneda = "GTQ";

    /** Frases FEL (tipo / escenario). Vacío si no aplica al emisor. */
    private List<Frase> frases = new ArrayList<>();

    /**
     * Credenciales SANDBOX (implementación INFILE), usadas para kioskos con
     * {@code pos_test_mode=true} o cuando {@link #testMode} fuerza sandbox global.
     */
    private Sandbox sandbox = new Sandbox();

    @Data
    public static class Frase {
        private int tipo = 1;
        private int escenario = 1;
    }

    @Data
    public static class Sandbox {
        private String signKey = "";
        private String signAlias = "";
        private String certUsuario = "";
        private String certLlave = "";
        private String nitEmisor = "";
        private String nombreEmisor = "";
        private String nombreComercial = "";
        private String correoEmisor = "";
        private String afiliacionIva = "GEN";
        private String direccion = "";
        private String municipio = "Guatemala";
        private String departamento = "GUATEMALA";
        private List<Frase> frases = new ArrayList<>();
    }

    /**
     * Resuelve el set de credenciales a usar para una operación puntual.
     *
     * @param useSandbox true para credenciales de implementación/pruebas, false para producción.
     */
    public FelCredentials resolveCredentials(boolean useSandbox) {
        if (useSandbox) {
            return new FelCredentials(
                    sandbox.signKey, sandbox.signAlias, sandbox.certUsuario, sandbox.certLlave,
                    sandbox.nitEmisor, repair(sandbox.nombreEmisor), repair(sandbox.nombreComercial),
                    repair(sandbox.correoEmisor),
                    repair(sandbox.direccion), repair(sandbox.municipio), repair(sandbox.departamento),
                    sandbox.afiliacionIva,
                    sandbox.frases);
        }
        return new FelCredentials(
                signKey, signAlias, certUsuario, certLlave,
                nitEmisor, repair(nombreEmisor), repair(nombreComercial), repair(correoEmisor),
                repair(direccion), repair(municipio), repair(departamento), afiliacionIva,
                frases);
    }

    private static String repair(String value) {
        return FelTextEncodingHelper.repairFelText(value);
    }
}
