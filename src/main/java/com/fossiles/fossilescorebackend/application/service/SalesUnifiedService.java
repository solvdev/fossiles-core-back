package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesDashboardResponse.UnifiedSaleRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService.VendorOrderBreakdown;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Listado unificado de ventas (kioscos, online, vendedor LF). Ordena y recorta primero sobre las cabeceras
 * y luego resuelve etiquetas y totales solo de las filas que sobreviven, en consultas por lote.
 */
@Service
@RequiredArgsConstructor
public class SalesUnifiedService {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 2000;

    private final SalesSourceLoader sourceLoader;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final LocationRepository locationRepository;
    private final CustomerAccountService customerAccountService;

    @Transactional(readOnly = true)
    public List<UnifiedSaleRow> getUnifiedSales(
            LocalDate startDate,
            LocalDate endDate,
            String channel,
            Long kioskLocationId,
            Integer limit
    ) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String normalizedChannel = normalizeChannel(channel);
        int rowLimit = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        Map<String, KioskSaleHeaderRow> kioskById = new HashMap<>();
        Map<String, OnlineSaleEntity> onlineById = new HashMap<>();
        Map<String, ProductionOrderEntity> vendorById = new HashMap<>();
        List<UnifiedSaleRow> rows = new ArrayList<>();

        if (normalizedChannel == null || CHANNEL_KIOSKO.equals(normalizedChannel)) {
            for (KioskSaleHeaderRow sale : sourceLoader.loadKioskHeaders(range.from(), range.to())) {
                if (kioskLocationId != null && !kioskLocationId.equals(sale.kioskLocationId())) {
                    continue;
                }
                String id = "K-" + sale.id();
                kioskById.put(id, sale);
                rows.add(UnifiedSaleRow.builder()
                        .id(id)
                        .saleDate(sale.saleDate())
                        .channel(CHANNEL_KIOSKO)
                        .channelLabel("Kiosko")
                        .reference(sale.saleNumber())
                        .quantity(sale.totalItems())
                        .totalAmount(nz(sale.totalAmount()))
                        .build());
            }
        }
        if (normalizedChannel == null || CHANNEL_ONLINE.equals(normalizedChannel)) {
            for (OnlineSaleEntity sale : sourceLoader.loadOnlineSales(range.from(), range.to())) {
                String id = "O-" + sale.getId();
                onlineById.put(id, sale);
                rows.add(UnifiedSaleRow.builder()
                        .id(id)
                        .saleDate(sale.getSaleDate())
                        .channel(CHANNEL_ONLINE)
                        .channelLabel("Online")
                        .reference(sale.getSaleNumber())
                        .quantity(sale.getQuantity() != null ? BigDecimal.valueOf(sale.getQuantity()) : BigDecimal.ZERO)
                        .totalAmount(nz(sale.getTotalAmount()))
                        .sellerName(sale.getSalesperson())
                        .build());
            }
        }
        if (normalizedChannel == null || CHANNEL_VENDOR.equals(normalizedChannel)) {
            for (ProductionOrderEntity order : sourceLoader.loadVendorOrders(range.from(), range.to())) {
                String id = "V-" + order.getId();
                vendorById.put(id, order);
                rows.add(UnifiedSaleRow.builder()
                        .id(id)
                        .saleDate(SalesSourceLoader.resolveVendorOrderDate(order))
                        .channel(CHANNEL_VENDOR)
                        .channelLabel("Vendedor LF")
                        .reference(order.getCode())
                        .productName("Orden " + order.getCode())
                        .quantity(BigDecimal.ONE)
                        .sellerName(order.getSellerName())
                        .build());
            }
        }

        List<UnifiedSaleRow> page = rows.stream()
                .sorted(Comparator
                        .comparing(UnifiedSaleRow::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UnifiedSaleRow::getId, Comparator.reverseOrder()))
                .limit(rowLimit)
                .collect(Collectors.toList());

        fillKioskRows(page, kioskById);
        fillOnlineRows(page, onlineById);
        fillVendorRows(page, vendorById);
        return page;
    }

    private void fillKioskRows(List<UnifiedSaleRow> page, Map<String, KioskSaleHeaderRow> kioskById) {
        List<UnifiedSaleRow> kioskRows = page.stream().filter(r -> kioskById.containsKey(r.getId())).toList();
        if (kioskRows.isEmpty()) {
            return;
        }
        Set<Long> saleIds = kioskRows.stream().map(r -> kioskById.get(r.getId()).id()).collect(Collectors.toSet());
        Map<Long, List<KioskSaleItemRow>> itemsBySale = kioskSaleItemRepository.findRowsByKioskSaleIdIn(saleIds).stream()
                .collect(Collectors.groupingBy(KioskSaleItemRow::kioskSaleId));
        Set<Long> kioskIds = kioskRows.stream()
                .map(r -> kioskById.get(r.getId()).kioskLocationId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> kiosks = kioskIds.isEmpty()
                ? Map.of()
                : locationRepository.findAllById(kioskIds).stream()
                        .collect(Collectors.toMap(LocationEntity::getId, row -> row, (a, b) -> a));
        for (UnifiedSaleRow row : kioskRows) {
            KioskSaleHeaderRow sale = kioskById.get(row.getId());
            List<String> names = itemsBySale.getOrDefault(sale.id(), List.of()).stream()
                    .map(KioskSaleItemRow::productName).toList();
            row.setProductName(legacyProductLabel(names, "Venta kiosko"));
            LocationEntity kiosk = kiosks.get(sale.kioskLocationId());
            row.setKioskName(kiosk != null ? kiosk.getName() : null);
        }
    }

    private void fillOnlineRows(List<UnifiedSaleRow> page, Map<String, OnlineSaleEntity> onlineById) {
        List<UnifiedSaleRow> onlineRows = page.stream().filter(r -> onlineById.containsKey(r.getId())).toList();
        if (onlineRows.isEmpty()) {
            return;
        }
        Set<Long> saleIds = onlineRows.stream().map(r -> onlineById.get(r.getId()).getId()).collect(Collectors.toSet());
        Map<Long, List<OnlineSaleItemEntity>> itemsBySale =
                onlineSaleItemRepository.findByOnlineSaleIdInOrderByIdAsc(saleIds).stream()
                        .collect(Collectors.groupingBy(OnlineSaleItemEntity::getOnlineSaleId));
        for (UnifiedSaleRow row : onlineRows) {
            OnlineSaleEntity sale = onlineById.get(row.getId());
            List<OnlineSaleItemEntity> items = itemsBySale.getOrDefault(sale.getId(), List.of());
            String fallback = sale.getProductName() != null ? sale.getProductName() : "Venta online";
            row.setProductName(legacyProductLabel(
                    items.stream().map(OnlineSaleItemEntity::getProductName).toList(), fallback));
        }
    }

    private void fillVendorRows(List<UnifiedSaleRow> page, Map<String, ProductionOrderEntity> vendorById) {
        List<UnifiedSaleRow> vendorRows = page.stream().filter(r -> vendorById.containsKey(r.getId())).toList();
        if (vendorRows.isEmpty()) {
            return;
        }
        List<ProductionOrderEntity> orders = vendorRows.stream().map(r -> vendorById.get(r.getId())).toList();
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderIdIn(
                orders.stream().map(ProductionOrderEntity::getId).toList());
        Map<Long, ProductEntity> products = sourceLoader.loadProductsById(
                items.stream().map(ProductionOrderItemEntity::getProductId).toList());
        Map<Long, VendorOrderBreakdown> breakdowns =
                customerAccountService.estimateVendorOrderBreakdowns(orders, items, products);
        for (UnifiedSaleRow row : vendorRows) {
            VendorOrderBreakdown breakdown = breakdowns.get(vendorById.get(row.getId()).getId());
            row.setTotalAmount(breakdown != null ? breakdown.total() : BigDecimal.ZERO);
        }
    }

    /** Etiqueta del listado unificado: cuenta todos los ítems (el listado no distingue empaque). */
    private static String legacyProductLabel(List<String> names, String emptyFallback) {
        if (names.isEmpty()) {
            return emptyFallback;
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return names.get(0) + " +" + (names.size() - 1) + " más";
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank() || "all".equalsIgnoreCase(channel.trim())) {
            return null;
        }
        return switch (channel.trim().toLowerCase(Locale.ROOT)) {
            case "kiosko", "kiosk", "kioscos" -> CHANNEL_KIOSKO;
            case "online", "enlinea" -> CHANNEL_ONLINE;
            case "vendedor", "vendor", "lf" -> CHANNEL_VENDOR;
            default -> channel.trim().toUpperCase(Locale.ROOT);
        };
    }
}
