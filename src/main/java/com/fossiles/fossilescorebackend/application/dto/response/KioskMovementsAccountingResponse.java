package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMovementsAccountingResponse {

    // — Identificación del movimiento —
    private Long id;
    private Long kioscoStockId;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
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
    /** Saldo del color (producto+color) antes del movimiento. */
    private Integer stockAntes;
    /** Saldo del color (producto+color) después del movimiento. */
    private Integer stockDespues;
    /** Saldo de la talla antes (solo si el movimiento tiene talla). */
    private Integer stockAntesTalla;
    /** Saldo de la talla después (solo si el movimiento tiene talla). */
    private Integer stockDespuesTalla;

    // — Referencia —
    private String referencia;
    private String tipoReferencia;
    private String resumenReferencia;
    /** Número de boleta física de devolución/cambio. */
    private String boletaFisica;

    // — Venta / Factura —
    /** Número interno ESTABLECIMIENTO-CORRELATIVO (ej. "A1-241") */
    private String numeroInternoFactura;
    /** Número de venta POS interno (ej. "VENTA-00123") */
    private String numeroVenta;
    private BigDecimal totalVenta;
    private String formaPago;
    // Detalle de pago con tarjeta
    private String cardAuthNumber;
    private String cardLast4;
    private String cardBrand;
    private BigDecimal cardAmount;
    private BigDecimal cardVoucherAmount;
    private BigDecimal cardVoucherDifference;
    private String card2AuthNumber;
    private String card2Last4;
    private String card2Brand;
    private BigDecimal card2Amount;
    private BigDecimal card2VoucherAmount;
    private BigDecimal card2VoucherDifference;

    // — Metadata —
    private String motivo;
    private String usuario;
}
