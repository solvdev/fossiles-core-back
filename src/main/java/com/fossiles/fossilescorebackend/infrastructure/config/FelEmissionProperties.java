package com.fossiles.fossilescorebackend.infrastructure.config;

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
    /** Ambiente INFILE implementación / sandbox (credenciales demo si faltan). */
    private boolean testMode = false;
    /** Si true, la venta POS falla cuando no se puede certificar el DTE. */
    private boolean required = true;

    private String signUrl = "https://signer-emisores.feel.com.gt/sign_solicitud_firmas/firma_xml";
    private String certifyUrl = "https://certificador.feel.com.gt/fel/certificacion/v2/dte/";
    /** Endpoint INFILE anulación v2 (alineado a certificación v2). */
    private String annulUrl = "https://certificador.feel.com.gt/fel/anulacion/v2/dte/";

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

    @Data
    public static class Frase {
        private int tipo = 1;
        private int escenario = 1;
    }
}
