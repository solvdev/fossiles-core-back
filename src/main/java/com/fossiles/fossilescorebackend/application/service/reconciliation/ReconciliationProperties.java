package com.fossiles.fossilescorebackend.application.service.reconciliation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Parámetros de la conciliación. Los valores por defecto son los confirmados por el negocio. */
@Data
@Component
@ConfigurationProperties(prefix = "reconciliation")
public class ReconciliationProperties {
    /** Comisión del vendedor sobre el neto sin envío (2%). */
    private BigDecimal commissionRate = new BigDecimal("0.02");
    /** Días sin fondo antes de que una venta cuente como problema "venta sin envío relacionado". */
    private int missingFundsAfterDays = 5;
    /** Tope de filas por archivo importado. */
    private int maxImportRows = 20000;
}
