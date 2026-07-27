package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskMovementsAccountingResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class KioskMovementsAccountingService {

    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final TaxInvoiceRepository taxInvoiceRepository;
    private final ProductShipmentRepository productShipmentRepository;

    @Transactional(readOnly = true)
    public List<KioskMovementsAccountingResponse> listMovements(
            Long locationId,
            Long stockId,
            Long productId,
            String productTerm,
            KioscoMovementType type,
            LocalDate from,
            LocalDate to,
            String referenceTerm,
            String reasonContains,
            String sizeKey,
            Boolean affectsStockOnly
    ) throws BusinessException {
        if (stockId == null && locationId == null) {
            throw new BusinessException("Indica locationId o stockId para consultar movimientos.");
        }

        List<KioscoMovementEntity> raw;
        if (stockId != null) {
            raw = kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtDescIdDesc(stockId);
        } else if (locationId != null && productId != null) {
            raw = kioscoMovementRepository.findByLocationAndFilters(locationId, productId, null);
        } else {
            raw = kioscoMovementRepository.findByLocationIdOrderByCreatedAtDesc(locationId);
        }

        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDtExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;
        String reasonTerm = normalizeTerm(reasonContains);
        String refTerm = normalizeTerm(referenceTerm);
        String prodTerm = normalizeTerm(productTerm);
        String sizeNorm = sizeKey != null ? ProductInventorySizesJson.normalizeKey(sizeKey) : null;

        List<KioskMovementsAccountingResponse> out = new ArrayList<>();
        for (KioscoMovementEntity m : raw) {
            if (productId != null) {
                KioscoStockEntity stock = m.getKioscoStock();
                if (stock == null && m.getKioscoStockId() != null) {
                    stock = kioscoStockRepository.findById(m.getKioscoStockId()).orElse(null);
                }
                if (stock == null || !Objects.equals(stock.getProductId(), productId)) {
                    continue;
                }
            }
            if (type != null && m.getMovementType() != type) {
                continue;
            }
            if (Boolean.TRUE.equals(affectsStockOnly) && !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            if (sizeNorm != null && !sizeNorm.isEmpty()) {
                String mk = ProductInventorySizesJson.normalizeKey(m.getSizeKey());
                if (!sizeNorm.equals(mk)) {
                    continue;
                }
            }
            if (fromDt != null && (m.getCreatedAt() == null || m.getCreatedAt().isBefore(fromDt))) {
                continue;
            }
            if (toDtExclusive != null && (m.getCreatedAt() == null || !m.getCreatedAt().isBefore(toDtExclusive))) {
                continue;
            }
            if (reasonTerm != null) {
                String reason = nullToEmpty(m.getReason()).toLowerCase(Locale.ROOT);
                if (!reason.contains(reasonTerm)) {
                    continue;
                }
            }

            KioskMovementsAccountingResponse dto = toAccountingResponse(m);

            if (prodTerm != null) {
                String haystack = (
                        nullToEmpty(dto.getCodigoProducto())
                        + " " + nullToEmpty(dto.getProducto())
                ).toLowerCase(Locale.ROOT);
                if (!haystack.contains(prodTerm)) {
                    continue;
                }
            }

            if (refTerm != null) {
                String haystack = (
                        nullToEmpty(dto.getReferencia())
                        + " " + nullToEmpty(dto.getResumenReferencia())
                        + " " + nullToEmpty(dto.getNumeroInternoFactura())
                        + " " + nullToEmpty(dto.getNumeroVenta())
                ).toLowerCase(Locale.ROOT);
                if (!haystack.contains(refTerm)) {
                    continue;
                }
            }
            out.add(dto);
        }
        return out;
    }

    private KioskMovementsAccountingResponse toAccountingResponse(KioscoMovementEntity entity) {
        KioscoMovementResponse base = kioscoInventoryService.toMovementResponse(entity);

        KioskMovementsAccountingResponse.KioskMovementsAccountingResponseBuilder builder =
                KioskMovementsAccountingResponse.builder()
                        .id(base.getId())
                        .fecha(base.getCreatedAt())
                        .kiosko(base.getLocationName())
                        .codigoProducto(base.getProductCode())
                        .producto(base.getProductName())
                        .color(base.getColorName())
                        .talla(base.getSizeKey())
                        .tipoMovimiento(base.getMovementType())
                        .cantidad(base.getQuantity())
                        .stockAntes(base.getStockBefore())
                        .stockDespues(base.getStockAfter())
                        .referencia(base.getReferenceNumber())
                        .tipoReferencia(base.getReferenceType())
                        .motivo(base.getReason())
                        .usuario(base.getUsername());

        enrichFromSaleAndInvoice(entity, base, builder);
        return builder.build();
    }

    private void enrichFromSaleAndInvoice(
            KioscoMovementEntity entity,
            KioscoMovementResponse base,
            KioskMovementsAccountingResponse.KioskMovementsAccountingResponseBuilder builder
    ) {
        if (entity.getReferenceId() == null) {
            String refSummary = base.getReferenceNumber();
            builder.resumenReferencia(refSummary);
            return;
        }

        boolean isSaleMovement = "INVOICE".equals(base.getReferenceType())
                || entity.getMovementType() == KioscoMovementType.VENTA
                || entity.getMovementType() == KioscoMovementType.ANULACION;

        if (isSaleMovement) {
            kioskSaleRepository.findById(entity.getReferenceId()).ifPresent(sale -> {
                builder.numeroVenta(sale.getSaleNumber())
                        .totalVenta(sale.getTotalAmount())
                        .formaPago(sale.getPaymentMethod())
                        .cliente(sale.getCustomerName())
                        .nit(sale.getCustomerTaxId());

                StringBuilder summary = new StringBuilder();
                summary.append(sale.getSaleNumber() != null ? sale.getSaleNumber() : "Venta #" + sale.getId());
                if (sale.getTotalAmount() != null) {
                    summary.append(" · Q").append(sale.getTotalAmount().toPlainString());
                }
                builder.resumenReferencia(summary.toString());

                if (sale.getInvoiceId() != null) {
                    taxInvoiceRepository.findById(sale.getInvoiceId()).ifPresent(invoice -> {
                        builder.numeroInternoFactura(invoice.getInternalNumber())
                                .felUuid(invoice.getFelUuid())
                                .felSerie(invoice.getFelSerie())
                                .felNumero(invoice.getFelNumero());
                    });
                }
            });
            return;
        }

        if ("SHIPMENT".equals(base.getReferenceType())) {
            productShipmentRepository.findById(entity.getReferenceId())
                    .ifPresent(s -> builder.resumenReferencia("Envío " + s.getShipmentNumber()));
            return;
        }

        builder.resumenReferencia(base.getReferenceNumber() != null
                ? base.getReferenceNumber()
                : "Ref #" + entity.getReferenceId());
    }

    private static String normalizeTerm(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
