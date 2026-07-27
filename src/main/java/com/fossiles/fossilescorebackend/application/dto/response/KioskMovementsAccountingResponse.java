package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMovementsAccountingResponse {

    // — Identificación del movimiento —
    private Long id;
    private LocalDateTime fecha;

    // — Kiosko —
    private String kiosko;
    private String codigoKiosko;

    // — Producto —
    private String codigoProducto;
    private String producto;
    private String color;
    private String talla;

    // — Movimiento —
    private KioscoMovementType tipoMovimiento;
    private Integer cantidad;
    private Integer stockAntes;
    private Integer stockDespues;

    // — Referencia —
    private String referencia;
    private String tipoReferencia;
    private String resumenReferencia;

    // — Venta / Factura —
    /** Número interno ESTABLECIMIENTO-CORRELATIVO (ej. "A1-241") */
    private String numeroInternoFactura;
    /** Número de venta POS interno (ej. "VENTA-00123") */
    private String numeroVenta;
    private BigDecimal totalVenta;
    private String formaPago;
    private String cliente;
    private String nit;
    /** UUID FEL de SAT */
    private String felUuid;
    /** Serie FEL de SAT */
    private String felSerie;
    /** Número FEL de SAT */
    private String felNumero;

    // — Metadata —
    private String motivo;
    private String usuario;
}
