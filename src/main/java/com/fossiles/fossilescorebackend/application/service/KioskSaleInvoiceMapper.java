package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.application.util.EntrecuerosPriceLists;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KioskSaleInvoiceMapper {

    private final ProductRepository productRepository;

    public TaxInvoiceDocument fromSale(KioskSaleEntity sale) {
        BigDecimal subtotal = nz(sale.getSubtotal());
        BigDecimal totalAmount = nz(sale.getTotalAmount());

        List<TaxInvoiceDocument.Line> lines = new ArrayList<>();
        List<KioskSaleItemEntity> saleLines = sale.getItems() == null ? List.of() : sale.getItems();
        Set<Long> productIds = saleLines.stream()
                .map(KioskSaleItemEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity(), (a, b) -> a));

        for (KioskSaleItemEntity line : saleLines) {
            BigDecimal qty = nz(line.getQuantity()).setScale(3, RoundingMode.HALF_UP);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal lineTotal = nz(line.getLineTotal()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unitPrice = nz(line.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            if (unitPrice.compareTo(BigDecimal.ZERO) <= 0 && lineTotal.compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = lineTotal.divide(qty, 2, RoundingMode.HALF_UP);
            }
            String productCode = resolveProductCode(line, productsById);
            ProductEntity product = line.getProductId() != null ? productsById.get(line.getProductId()) : null;
            lines.add(TaxInvoiceDocument.Line.builder()
                    .productCode(productCode)
                    .description(buildLineDescription(line))
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .bundlePriced(bundlePricedLine(productCode, qty, unitPrice, lineTotal))
                    .editedUnitPrice(editedBelowCatalog(
                            product, line, unitPrice, qty, lineTotal, sale.getDiscountAmount()))
                    .build());
        }

        return TaxInvoiceDocument.builder()
                .transactionId(buildTransactionId(sale))
                .issuedAt(sale.getSoldAt())
                .customerTaxId(sale.getCustomerTaxId())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .email(sale.getEmail())
                .subtotal(subtotal)
                .discountAmount(nz(sale.getDiscountAmount()))
                .totalAmount(totalAmount)
                .lines(lines)
                .build();
    }

    /** Toda venta POS debe generar factura electrónica (CF o NIT). */
    public static boolean shouldEmitForPos(String taxId, Boolean requestInvoice) {
        return true;
    }

    private static String buildTransactionId(KioskSaleEntity sale) {
        if (sale.getSaleNumber() != null && !sale.getSaleNumber().isBlank()) {
            return sale.getSaleNumber().trim();
        }
        return "POS-" + sale.getId();
    }

    private static String buildLineDescription(KioskSaleItemEntity line) {
        List<String> parts = new ArrayList<>();
        if (line.getProductName() != null && !line.getProductName().isBlank()) {
            parts.add(line.getProductName().trim());
        }
        if (line.getColorName() != null && !line.getColorName().isBlank()) {
            parts.add(line.getColorName().trim());
        }
        String text = String.join(" ", parts).trim();
        if (line.getExtraRequestId() != null
                && line.getExtraDescription() != null && !line.getExtraDescription().isBlank()) {
            text = (text + " + complemento: " + line.getExtraDescription().trim()).trim();
        }
        if (text.length() > 450) {
            return text.substring(0, 450);
        }
        return text.isBlank() ? "Producto" : text;
    }

    private String resolveProductCode(KioskSaleItemEntity line, Map<Long, ProductEntity> productsById) {
        String fromLine = trimToNull(line.getProductCode());
        if (fromLine != null) {
            return fromLine;
        }
        if (line.getProductId() == null) {
            return null;
        }
        ProductEntity product = productsById.get(line.getProductId());
        if (product != null) {
            return trimToNull(product.getCode());
        }
        return productRepository.findById(line.getProductId())
                .map(productEntity -> trimToNull(productEntity.getCode()))
                .orElse(null);
    }

    /**
     * El paquete B-1 no es unitario × cantidad (43.33 × 3 = 129.99, total 130).
     * Una línea editada sí se guarda así, y no debe llevar el ajuste de paquete
     * aunque el total coincida con el del paquete.
     */
    static boolean bundlePricedLine(
            String productCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
        if (!EntrecuerosPriceLists.isChargedB1BundleLine(productCode, quantity, lineTotal)) {
            return false;
        }
        return !straightUnitLine(unitPrice, quantity, lineTotal);
    }

    /**
     * Precio editado por debajo de la lista POS y cobrado como unitario × cantidad,
     * en una venta que sí tiene descuento. Sin descuento el XML de lista ya cuadra
     * y no hace falta marcarlo. Una línea de catálogo (o un cincho con recargo) no entra.
     */
    static boolean editedBelowCatalog(
            ProductEntity product,
            KioskSaleItemEntity line,
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal lineTotal,
            BigDecimal saleDiscount
    ) {
        if (saleDiscount == null || saleDiscount.signum() <= 0) {
            return false;
        }
        if (!straightUnitLine(unitPrice, quantity, lineTotal)) {
            return false;
        }
        BigDecimal base = unitPrice;
        if (line != null && line.getExtraAmount() != null && line.getExtraAmount().signum() > 0) {
            base = unitPrice.subtract(line.getExtraAmount());
        }
        BigDecimal catalog = catalogListUnitPrice(product);
        return catalog != null && base.compareTo(catalog) < 0;
    }

    private static boolean straightUnitLine(BigDecimal unitPrice, BigDecimal quantity, BigDecimal lineTotal) {
        if (unitPrice == null || quantity == null || lineTotal == null) {
            return false;
        }
        BigDecimal straight = unitPrice.setScale(2, RoundingMode.HALF_UP)
                .multiply(quantity)
                .setScale(2, RoundingMode.HALF_UP);
        return straight.compareTo(lineTotal.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    /** Misma prioridad que el precio de lista POS, sin recargo de talla. */
    private static BigDecimal catalogListUnitPrice(ProductEntity product) {
        if (product == null) {
            return null;
        }
        if (product.getSalePrice() != null && product.getSalePrice().signum() > 0) {
            return product.getSalePrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getDiscountedPrice() != null && product.getDiscountedPrice().signum() > 0) {
            return product.getDiscountedPrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getSellerPrice() != null && product.getSellerPrice().signum() > 0) {
            return product.getSellerPrice().setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    static String normalizeTaxId(String taxId) {
        String raw = taxId == null ? "" : taxId.trim().toUpperCase(Locale.ROOT);
        if (raw.isBlank() || "CF".equals(raw) || "C/F".equals(raw)) {
            return "CF";
        }
        return raw.replace(" ", "").replace("-", "");
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
