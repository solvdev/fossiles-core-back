package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class OnlineSaleInvoiceMapper {

    private final OnlineSaleItemRepository itemRepository;

    public TaxInvoiceDocument fromSale(OnlineSaleEntity sale) {
        List<OnlineSaleItemEntity> items = itemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        List<TaxInvoiceDocument.Line> lines = new ArrayList<>();

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                BigDecimal lineTotal = resolveItemSubtotal(item);
                if (lineTotal.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal qty = BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 1);
                BigDecimal unitPrice = item.getUnitPrice() != null
                        ? item.getUnitPrice()
                        : lineTotal.divide(qty, 2, RoundingMode.HALF_UP);
                lines.add(TaxInvoiceDocument.Line.builder()
                        .description(buildLineDescription(item))
                        .quantity(qty.setScale(3, RoundingMode.HALF_UP))
                        .unitPrice(unitPrice)
                        .lineTotal(lineTotal)
                        .build());
            }
        } else if (sale.getProductId() != null) {
            BigDecimal qty = BigDecimal.valueOf(sale.getQuantity() != null ? sale.getQuantity() : 1);
            BigDecimal unitPrice = sale.getUnitPrice() != null ? sale.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            lines.add(TaxInvoiceDocument.Line.builder()
                    .description(buildLegacyDescription(sale))
                    .quantity(qty.setScale(3, RoundingMode.HALF_UP))
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build());
        }

        BigDecimal shipping = nz(sale.getShippingCost());
        if (shipping.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(TaxInvoiceDocument.Line.builder()
                    .description("Costo de envío")
                    .quantity(BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP))
                    .unitPrice(shipping)
                    .lineTotal(shipping)
                    .build());
        }

        BigDecimal totalAmount = sumLineTotals(lines);
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal netAmount = nz(sale.getNetAmount());
            BigDecimal saleTotal = nz(sale.getTotalAmount());
            totalAmount = saleTotal.compareTo(BigDecimal.ZERO) > 0
                    ? saleTotal
                    : netAmount.add(shipping);
        }

        return TaxInvoiceDocument.builder()
                .transactionId(buildTransactionId(sale))
                .issuedAt(sale.getSaleDate() != null
                        ? sale.getSaleDate().atStartOfDay()
                        : LocalDateTime.now())
                .customerTaxId(sale.getInvoiceTaxId())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .email(sale.getEmail())
                .subtotal(totalAmount)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(totalAmount)
                .lines(lines)
                .build();
    }

    private static BigDecimal resolveItemSubtotal(OnlineSaleItemEntity item) {
        if (item.getSubtotal() != null) {
            return item.getSubtotal().setScale(2, RoundingMode.HALF_UP);
        }
        if (item.getUnitPrice() != null && item.getQuantity() != null) {
            return item.getUnitPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private static String buildLineDescription(OnlineSaleItemEntity item) {
        List<String> parts = new ArrayList<>();
        if (item.getProductCode() != null && !item.getProductCode().isBlank()) {
            parts.add(item.getProductCode().trim());
        }
        if (item.getProductName() != null && !item.getProductName().isBlank()) {
            parts.add(item.getProductName().trim());
        }
        if (item.getColorName() != null && !item.getColorName().isBlank()) {
            parts.add(item.getColorName().trim());
        }
        if (item.getSize() != null && !item.getSize().isBlank()) {
            parts.add(item.getSize().trim());
        }
        String text = String.join(" ", parts).trim();
        return text.isBlank() ? "Producto" : text;
    }

    private static String buildLegacyDescription(OnlineSaleEntity sale) {
        List<String> parts = new ArrayList<>();
        if (sale.getProductCode() != null) {
            parts.add(sale.getProductCode().trim());
        }
        if (sale.getProductName() != null) {
            parts.add(sale.getProductName().trim());
        }
        String text = String.join(" ", parts).trim();
        return text.isBlank() ? "Producto" : text;
    }

    private static String buildTransactionId(OnlineSaleEntity sale) {
        if (sale.getSaleNumber() != null && !sale.getSaleNumber().isBlank()) {
            return sale.getSaleNumber().trim();
        }
        return "OPL-" + sale.getId();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal sumLineTotals(List<TaxInvoiceDocument.Line> lines) {
        return lines.stream()
                .map(line -> nz(line.getLineTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
