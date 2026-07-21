package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class KioskSaleInvoiceMapper {

    private final ProductRepository productRepository;

    public TaxInvoiceDocument fromSale(KioskSaleEntity sale) {
        BigDecimal subtotal = nz(sale.getSubtotal());
        BigDecimal totalAmount = nz(sale.getTotalAmount());

        List<TaxInvoiceDocument.Line> lines = new ArrayList<>();
        List<KioskSaleItemEntity> saleLines = sale.getItems() == null ? List.of() : sale.getItems();
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
            lines.add(TaxInvoiceDocument.Line.builder()
                    .productCode(resolveProductCode(line))
                    .description(buildLineDescription(line))
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
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
        if (text.length() > 450) {
            return text.substring(0, 450);
        }
        return text.isBlank() ? "Producto" : text;
    }

    private String resolveProductCode(KioskSaleItemEntity line) {
        String fromLine = trimToNull(line.getProductCode());
        if (fromLine != null) {
            return fromLine;
        }
        if (line.getProductId() == null) {
            return null;
        }
        return productRepository.findById(line.getProductId())
                .map(product -> trimToNull(product.getCode()))
                .orElse(null);
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
