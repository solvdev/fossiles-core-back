package com.fossiles.fossilescorebackend.infrastructure.config;

import java.util.List;

/**
 * Set de credenciales FEL (firma + certificación + datos del emisor) resuelto para una
 * operación puntual: producción o sandbox, según el kiosko/venta que se esté facturando.
 */
public record FelCredentials(
        String signKey,
        String signAlias,
        String certUsuario,
        String certLlave,
        String nitEmisor,
        String nombreEmisor,
        String nombreComercial,
        String correoEmisor,
        String direccion,
        String municipio,
        String departamento,
        String afiliacionIva,
        List<FelEmissionProperties.Frase> frases
) {
}
