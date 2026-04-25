package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "online_sale")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSaleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Número correlativo de venta del día */
    @Column(name = "sale_number", length = 30)
    private String saleNumber;

    /** Nombre del cliente */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    /** Dirección de envío */
    @Column(name = "address", length = 500)
    private String address;

    /** Teléfono del cliente */
    @Column(name = "phone", length = 50)
    private String phone;

    /** Segundo teléfono de contacto */
    @Column(name = "phone2", length = 50)
    private String phone2;

    /** Empaque: true = Sí, false = No */
    @Column(name = "packaging")
    private Boolean packaging;

    /**
     * Forma de pago:
     * TRANSFERENCIA_PENDIENTE, TRANSFERENCIA_LISTA,
     * VISALINK_PAGADO, VISALINK_PENDIENTE,
     * DEPOSITO_LISTO, DEPOSITO_PENDIENTE,
     * CONTRA_ENTREGA
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /** Factura: NIT o "CF" */
    @Column(name = "invoice_tax_id", length = 50)
    private String invoiceTaxId;

    /** FK al producto del sistema */
    @Column(name = "product_id")
    private Long productId;

    /** Código del producto (desnormalizado para referencia rápida) */
    @Column(name = "product_code", length = 30)
    private String productCode;

    /** Nombre del producto (desnormalizado) */
    @Column(name = "product_name", length = 150)
    private String productName;

    /** FK al color */
    @Column(name = "color_id")
    private Long colorId;

    /** Nombre del color (desnormalizado) */
    @Column(name = "color_name", length = 100)
    private String colorName;

    /** Talla del producto */
    @Column(name = "size", length = 50)
    private String size;

    /** Cantidad de productos */
    @Column(name = "quantity")
    private Integer quantity;

    /** Precio unitario del producto */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** Total con envío = netAmount + shippingCost */
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** Costo de envío (Q30 contra entrega, Q15 otros) */
    @Column(name = "shipping_cost", precision = 12, scale = 2)
    private BigDecimal shippingCost;

    /** Precio neto = suma de productos (sin envío) */
    @Column(name = "net_amount", precision = 12, scale = 2)
    private BigDecimal netAmount;

    /** Transporte: FORZA_DELIVERY, GUATEX */
    @Column(name = "shipping_carrier", length = 50)
    private String shippingCarrier;

    /** Fecha de la venta */
    @Column(name = "sale_date")
    private LocalDate saleDate;

    /** Red social de origen: WHATSAPP, FACEBOOK, INSTAGRAM */
    @Column(name = "social_network", length = 30)
    private String socialNetwork;

    /** Correo electrónico del cliente */
    @Column(name = "email", length = 200)
    private String email;

    /** Número de envío interno auto-generado (ENVL-00001) */
    @Column(name = "shipment_number", length = 30)
    private String shipmentNumber;

    /** Número de guía de envío (paquetería externa) */
    @Column(name = "guide_number", length = 100)
    private String guideNumber;

    /** Autorización de pago */
    @Column(name = "payment_authorization", length = 200)
    private String paymentAuthorization;

    /**
     * Estado del pedido:
     * PENDIENTE, EN_PRODUCCION, PRODUCIDO, ENVIADO, ENTREGADO, CANCELADO,
     * DEVOLUCION, ANULADA
     */
    @Column(name = "status", length = 30)
    private String status;

    /** Observaciones generales */
    @Column(name = "observations", length = 2000)
    private String observations;

    /** Vendedor asignado: "Anthony Ixcajo" o "Eduardo Ramirez" */
    @Column(name = "salesperson", length = 100)
    private String salesperson;

    /** Indica si ya fue vinculado a una orden de producción */
    @Column(name = "in_production_order")
    private Boolean inProductionOrder;

    /** FK a la orden de producción cuando se vincule */
    @Column(name = "production_order_id")
    private Long productionOrderId;

    /**
     * Flag transitorio: si es true, @PrePersist/@PreUpdate NO recalculan envío ni total.
     * Se usa al importar CSV donde los montos ya vienen pre-calculados.
     */
    @Transient
    private boolean skipAmountCalculation = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDIENTE";
        if (packaging == null) packaging = false;
        if (inProductionOrder == null) inProductionOrder = false;
        if (!skipAmountCalculation) calculateShippingAndNet();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (!skipAmountCalculation) calculateShippingAndNet();
    }

    /**
     * Calcula automáticamente el costo de envío y el monto total.
     * Los precios ingresados son NETOS (sin envío).
     * netAmount = suma de productos (lo que el cliente paga por producto)
     * totalAmount = netAmount + shippingCost
     * Contra entrega = Q30, cualquier otro = Q15
     */
    public void calculateShippingAndNet() {
        if (paymentMethod != null) {
            if ("CONTRA_ENTREGA".equals(paymentMethod) || "CONTRA_ENTREGA_DEPOSITO".equals(paymentMethod)) {
                this.shippingCost = new BigDecimal("30.00");
            } else {
                this.shippingCost = new BigDecimal("15.00");
            }
        }
        if (netAmount != null && shippingCost != null) {
            this.totalAmount = netAmount.add(shippingCost);
        }
    }

    /**
     * Devuelve true si la forma de pago indica que ya está pagado
     * (contra entrega se considera "listo" porque se cobra al entregar).
     */
    public boolean isPaid() {
        if (paymentMethod == null) return false;
        return paymentMethod.equals("CONTRA_ENTREGA")
                || paymentMethod.equals("CONTRA_ENTREGA_DEPOSITO")
                || paymentMethod.equals("TRANSFERENCIA_LISTA")
                || paymentMethod.equals("VISALINK_PAGADO")
                || paymentMethod.equals("DEPOSITO_LISTO");
    }
}

