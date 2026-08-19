package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.OplDispatchSummaryResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OplDispatchSummaryService {

    private static final Set<String> EXCLUDED_STATUSES = Set.of(
            "ANULADA", "CANCELADO", "CANCELADA", "DEVOLUCION"
    );

    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final ProductionOrderRepository productionOrderRepository;

    /**
     * Todas las ventas en línea del día anterior a {@code dispatchDate},
     * con o sin OPL. Lo pedido ayer debe despacharse hoy.
     */
    @Transactional(readOnly = true)
    public OplDispatchSummaryResponse summaryForDispatchDate(LocalDate dispatchDate) {
        LocalDate dispatch = dispatchDate != null ? dispatchDate : GuatemalaDateTime.today();
        LocalDate saleDate = dispatch.minusDays(1);
        List<OnlineSaleEntity> raw = onlineSaleRepository.findBySaleDateOrderByIdAsc(saleDate);

        Set<Long> saleIds = raw.stream().map(OnlineSaleEntity::getId).collect(Collectors.toSet());
        Map<Long, List<OnlineSaleItemEntity>> itemsBySale = saleIds.isEmpty()
                ? Map.of()
                : onlineSaleItemRepository.findByOnlineSaleIdInOrderByIdAsc(saleIds).stream()
                .collect(Collectors.groupingBy(OnlineSaleItemEntity::getOnlineSaleId));

        Set<Long> poIds = raw.stream()
                .map(OnlineSaleEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, ProductionOrderEntity> ordersById = poIds.isEmpty()
                ? Map.of()
                : productionOrderRepository.findAllById(poIds).stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, po -> po, (a, b) -> a));

        List<OplDispatchSummaryResponse.Sale> sales = new ArrayList<>();
        int lineCount = 0;
        int unitCount = 0;
        int oplSaleCount = 0;
        int stockSaleCount = 0;
        int excludedCount = 0;
        for (OnlineSaleEntity sale : raw) {
            List<OplDispatchSummaryResponse.Line> lines = toLines(sale, itemsBySale.getOrDefault(sale.getId(), List.of()));
            boolean cancelled = isExcluded(sale);
            String kind = resolveKind(sale, lines, cancelled);
            boolean generatesOpl = "OPL".equals(kind) || "MIXTA".equals(kind);
            if (cancelled) {
                excludedCount++;
            } else if (generatesOpl) {
                oplSaleCount++;
            } else {
                stockSaleCount++;
            }
            lineCount += lines.size();
            unitCount += lines.stream().mapToInt(OplDispatchSummaryResponse.Line::getQuantity).sum();
            ProductionOrderEntity po = sale.getProductionOrderId() != null
                    ? ordersById.get(sale.getProductionOrderId())
                    : null;
            sales.add(OplDispatchSummaryResponse.Sale.builder()
                    .onlineSaleId(sale.getId())
                    .saleNumber(sale.getSaleNumber())
                    .customerName(sale.getCustomerName())
                    .phone(sale.getPhone())
                    .address(sale.getAddress())
                    .status(sale.getStatus())
                    .paymentMethod(sale.getPaymentMethod())
                    .shippingCarrier(sale.getShippingCarrier())
                    .productionOrderId(sale.getProductionOrderId())
                    .productionOrderCode(po != null ? po.getCode() : null)
                    .generatesOpl(generatesOpl)
                    .dispatchKind(kind)
                    .lines(lines)
                    .build());
        }

        return OplDispatchSummaryResponse.builder()
                .saleDate(saleDate)
                .dispatchDate(dispatch)
                .saleCount(sales.size())
                .lineCount(lineCount)
                .unitCount(unitCount)
                .oplSaleCount(oplSaleCount)
                .stockSaleCount(stockSaleCount)
                .excludedCount(excludedCount)
                .sales(sales)
                .build();
    }

    private static boolean isExcluded(OnlineSaleEntity sale) {
        String st = String.valueOf(sale.getStatus() == null ? "" : sale.getStatus()).trim().toUpperCase(Locale.ROOT);
        return EXCLUDED_STATUSES.contains(st);
    }

    private static String resolveKind(
            OnlineSaleEntity sale,
            List<OplDispatchSummaryResponse.Line> lines,
            boolean cancelled) {
        if (cancelled) {
            return "ANULADA";
        }
        boolean hasPo = sale.getProductionOrderId() != null || Boolean.TRUE.equals(sale.getInProductionOrder());
        boolean produce = lines.stream()
                .anyMatch(l -> "PRODUCE".equalsIgnoreCase(String.valueOf(l.getFulfillmentRoute())));
        boolean dispatch = lines.stream()
                .anyMatch(l -> "DISPATCH".equalsIgnoreCase(String.valueOf(l.getFulfillmentRoute())));
        if ((hasPo || produce) && dispatch) {
            return "MIXTA";
        }
        if (hasPo || produce) {
            return "OPL";
        }
        if (dispatch) {
            return "STOCK";
        }
        return "PENDIENTE";
    }

    private static List<OplDispatchSummaryResponse.Line> toLines(
            OnlineSaleEntity sale, List<OnlineSaleItemEntity> items) {
        if (items != null && !items.isEmpty()) {
            return items.stream()
                    .map(it -> OplDispatchSummaryResponse.Line.builder()
                            .productCode(it.getProductCode())
                            .productName(it.getProductName())
                            .colorName(it.getColorName())
                            .size(it.getSize())
                            .quantity(it.getQuantity() != null ? it.getQuantity() : 0)
                            .fulfillmentRoute(it.getFulfillmentRoute())
                            .build())
                    .toList();
        }
        if (sale.getProductCode() != null || sale.getProductName() != null || sale.getQuantity() != null) {
            return List.of(OplDispatchSummaryResponse.Line.builder()
                    .productCode(sale.getProductCode())
                    .productName(sale.getProductName())
                    .colorName(sale.getColorName())
                    .size(sale.getSize())
                    .quantity(sale.getQuantity() != null ? sale.getQuantity() : 0)
                    .build());
        }
        return List.of();
    }
}
