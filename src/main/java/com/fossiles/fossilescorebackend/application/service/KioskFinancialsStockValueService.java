package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsStockValueResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.CinchoSizePricing;
import com.fossiles.fossilescorebackend.application.util.EntrecuerosPriceLists;
import com.fossiles.fossilescorebackend.application.util.KioskPnlCalculator;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPeriodConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cuánto dinero representa el stock actual de cada kiosco (pestaña "Valor del inventario" de Finanzas).
 * <ul>
 *   <li>Valor a precio de venta: existencias de {@code kiosco_stock} × precio del POS (mismo orden que el POS:
 *       precio de venta, descuento, vendedor; recargo por talla de cincho; lista de Entre Cueros en ese kiosco).</li>
 *   <li>Costo estimado: valor × % de costo de producto del último mes configurado del sitio (igual que el P&amp;L,
 *       que aplica ese % sobre las ventas con IVA). El producto no tiene costo unitario propio.</li>
 * </ul>
 * Incluye los empaques (códigos {@code SUM...}: bolsas, cajas) a su precio del POS y los sitios marcados fuera de
 * los demás reportes (p. ej. Entre Cueros). Sólo aplica a sitios con kiosco real ({@code location_id}); los kioscos
 * sin valor en producto no aparecen.
 */
@Service
@RequiredArgsConstructor
public class KioskFinancialsStockValueService {

    static final String PACKAGING_PRODUCT_CODE_PREFIX = "SUM";

    private final KioskFinancialsAccessGuard guard;
    private final KioskSiteRepository siteRepository;
    private final KioscoStockRepository stockRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final ColorRepository colorRepository;
    private final LocationRepository locationRepository;
    private final KioskPeriodConfigRepository configRepository;

    /** Unidades y valor de una fila de stock. */
    record Valuation(long units, BigDecimal value, long unitsWithoutPrice, String sizes) {
    }

    @Transactional(readOnly = true)
    public KioskFinancialsStockValueResponse getStockValue(String siteIdsCsv) throws BusinessException {
        guard.assertCanView();
        Set<Long> requested = KioskFinancialsReportService.parseSiteIds(siteIdsCsv);
        List<KioskSiteEntity> sites = siteRepository.findAllByOrderBySortOrderAscNameAsc()
                .stream()
                .filter(s -> s.getLocationId() != null)
                .filter(s -> requested.isEmpty() || requested.contains(s.getId()))
                .toList();

        List<Long> locationIds = sites.stream().map(KioskSiteEntity::getLocationId).distinct().toList();
        List<KioscoStockEntity> stock = locationIds.isEmpty() ? List.of() : stockRepository.findByLocationIdIn(locationIds);
        Map<Long, List<KioscoStockEntity>> stockByLocation = stock.stream()
                .collect(Collectors.groupingBy(KioscoStockEntity::getLocationId));

        Set<Long> productIds = stock.stream().map(KioscoStockEntity::getProductId).collect(Collectors.toSet());
        Map<Long, ProductEntity> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        Map<Long, String> categoryNames = categoryRepository.findAll().stream()
                .filter(c -> c.getName() != null)
                .collect(Collectors.toMap(ProductCategoryEntity::getId, ProductCategoryEntity::getName, (a, b) -> a));
        Set<Long> colorIds = stock.stream().map(KioscoStockEntity::getColorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> colorNames = colorRepository.findAllById(colorIds).stream()
                .filter(c -> c.getName() != null)
                .collect(Collectors.toMap(ColorEntity::getId, ColorEntity::getName, (a, b) -> a));
        Map<Long, LocationEntity> locations = locationRepository.findAllById(locationIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, Function.identity()));
        Map<Long, CostPct> costPcts = latestProductCostPct(GuatemalaDateTime.today());

        List<KioskFinancialsStockValueResponse.SiteStock> siteDtos = new ArrayList<>();
        long totalUnits = 0;
        long totalWithoutPrice = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (KioskSiteEntity site : sites) {
            boolean entrecueros = KioskPosMode.isEntrecueros(locations.get(site.getLocationId()));
            List<KioskFinancialsStockValueResponse.Line> lines = new ArrayList<>();
            long units = 0;
            long withoutPrice = 0;
            BigDecimal value = BigDecimal.ZERO;
            LocalDateTime lastUpdated = null;
            for (KioscoStockEntity row : stockByLocation.getOrDefault(site.getLocationId(), List.of())) {
                ProductEntity product = products.get(row.getProductId());
                if (product == null) {
                    continue;
                }
                BigDecimal unitPrice = entrecueros
                        ? nz(EntrecuerosPriceLists.resolveUnitPrice(product, row.getHardwareCondition(), BigDecimal.ONE))
                        : catalogUnitPrice(product);
                // Entre Cueros no lleva recargo por talla en su lista de precios
                Valuation v = value(row.getCurrentStock(), row.getSizesData(), unitPrice, !entrecueros && !isPackaging(product));
                if (v.units() <= 0) {
                    continue;
                }
                units += v.units();
                withoutPrice += v.unitsWithoutPrice();
                value = value.add(v.value());
                if (row.getLastUpdatedAt() != null && (lastUpdated == null || row.getLastUpdatedAt().isAfter(lastUpdated))) {
                    lastUpdated = row.getLastUpdatedAt();
                }
                lines.add(KioskFinancialsStockValueResponse.Line.builder()
                        .productId(product.getId())
                        .productCode(product.getCode())
                        .productName(product.getName())
                        .categoryName(categoryNames.get(product.getCategoryId()))
                        .colorName(row.getColorId() == null ? null : colorNames.get(row.getColorId()))
                        .hardwareCondition(row.getHardwareCondition())
                        .units(v.units())
                        .unitPrice(unitPrice.setScale(2, RoundingMode.HALF_UP))
                        .saleValue(v.value().setScale(2, RoundingMode.HALF_UP))
                        .sizes(v.sizes())
                        .build());
            }
            lines.sort(Comparator.comparing(KioskFinancialsStockValueResponse.Line::getSaleValue).reversed()
                    .thenComparing(l -> l.getProductCode() == null ? "" : l.getProductCode()));

            // Kiosco sin valor en producto (sin existencias o todo sin precio): no aparece
            if (value.signum() <= 0) {
                continue;
            }
            CostPct pct = costPcts.get(site.getId());
            BigDecimal cost = pct == null ? null : value.multiply(pct.pct());
            siteDtos.add(KioskFinancialsStockValueResponse.SiteStock.builder()
                    .siteId(site.getId())
                    .siteName(site.getName())
                    .locationId(site.getLocationId())
                    .salesCategory(site.getSalesCategory())
                    .units(units)
                    .skus(lines.size())
                    .saleValue(KioskPnlCalculator.round2(value))
                    .productCostPct(pct == null ? null : pct.pct())
                    .productCostPeriod(pct == null ? null : String.format("%d-%02d", pct.year(), pct.month()))
                    .estimatedCost(cost == null ? null : KioskPnlCalculator.round2(cost))
                    .unitsWithoutPrice(withoutPrice)
                    .lastUpdatedAt(lastUpdated)
                    .lines(lines)
                    .build());
            totalUnits += units;
            totalWithoutPrice += withoutPrice;
            totalValue = totalValue.add(value);
            if (cost != null) {
                totalCost = totalCost.add(cost);
            }
        }
        for (KioskFinancialsStockValueResponse.SiteStock s : siteDtos) {
            s.setShare(totalValue.signum() == 0 ? null
                    : s.getSaleValue().divide(totalValue, 4, RoundingMode.HALF_UP));
        }
        siteDtos.sort(Comparator.comparing(KioskFinancialsStockValueResponse.SiteStock::getSaleValue).reversed());

        return KioskFinancialsStockValueResponse.builder()
                .generatedAt(GuatemalaDateTime.now())
                .totals(KioskFinancialsStockValueResponse.Totals.builder()
                        .units(totalUnits)
                        .saleValue(KioskPnlCalculator.round2(totalValue))
                        .estimatedCost(KioskPnlCalculator.round2(totalCost))
                        .unitsWithoutPrice(totalWithoutPrice)
                        .sites(siteDtos.size())
                        .build())
                .sites(siteDtos)
                .build();
    }

    private record CostPct(BigDecimal pct, int year, int month) {
    }

    /** Último % de costo de producto configurado por sitio, hasta el mes en curso (este año o el anterior). */
    private Map<Long, CostPct> latestProductCostPct(LocalDate today) {
        int ym = today.getYear() * 12 + today.getMonthValue();
        Map<Long, CostPct> out = new HashMap<>();
        List<KioskPeriodConfigEntity> configs = new ArrayList<>(configRepository.findByPeriodYear(today.getYear()));
        configs.addAll(configRepository.findByPeriodYear(today.getYear() - 1));
        for (KioskPeriodConfigEntity c : configs) {
            if (c.getProductCostPct() == null || c.getProductCostPct().signum() <= 0) {
                continue;
            }
            int key = c.getPeriodYear() * 12 + c.getPeriodMonth();
            if (key > ym) {
                continue;
            }
            CostPct prev = out.get(c.getSiteId());
            if (prev == null || key > prev.year() * 12 + prev.month()) {
                out.put(c.getSiteId(), new CostPct(c.getProductCostPct(), c.getPeriodYear(), c.getPeriodMonth()));
            }
        }
        return out;
    }

    static boolean isPackaging(ProductEntity product) {
        return product.getCode() != null
                && product.getCode().trim().toUpperCase(Locale.ROOT).startsWith(PACKAGING_PRODUCT_CODE_PREFIX);
    }

    /** Precio de catálogo del POS (kioscos normales), sin recargo de talla; empaques: venta o vendedor, como el POS. */
    static BigDecimal catalogUnitPrice(ProductEntity product) {
        BigDecimal[] candidates = isPackaging(product)
                ? new BigDecimal[]{product.getSalePrice(), product.getSellerPrice()}
                : new BigDecimal[]{product.getSalePrice(), product.getDiscountedPrice(), product.getSellerPrice()};
        for (BigDecimal p : candidates) {
            if (p != null && p.signum() > 0) {
                return p.setScale(2, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Valora una fila de stock. Las unidades son {@code current_stock}; si hay desglose por talla, esas unidades
     * llevan el recargo de su talla (hasta completar current_stock) y el resto va a precio base.
     */
    static Valuation value(Integer currentStock, String sizesJson, BigDecimal unitPrice, boolean sizeSurcharge) {
        long units = currentStock == null ? 0 : Math.max(0, currentStock);
        BigDecimal base = nz(unitPrice);
        if (units == 0) {
            return new Valuation(0, BigDecimal.ZERO, 0, null);
        }
        if (base.signum() <= 0) {
            return new Valuation(units, BigDecimal.ZERO, units, sizesLabel(sizesJson));
        }
        BigDecimal value = BigDecimal.ZERO;
        long pending = units;
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(sizesJson);
        for (Map.Entry<String, BigDecimal> e : sizes.entrySet()) {
            if (pending <= 0) {
                break;
            }
            long qty = Math.min(pending, e.getValue() == null ? 0 : e.getValue().longValue());
            if (qty <= 0) {
                continue;
            }
            BigDecimal price = sizeSurcharge ? CinchoSizePricing.applySurcharge(base, e.getKey()) : base;
            value = value.add(price.multiply(BigDecimal.valueOf(qty)));
            pending -= qty;
        }
        value = value.add(base.multiply(BigDecimal.valueOf(pending)));
        return new Valuation(units, value, 0, sizesLabel(sizesJson));
    }

    private static String sizesLabel(String sizesJson) {
        String label = ProductInventorySizesJson.parse(sizesJson).entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().signum() > 0)
                .map(e -> e.getKey() + ": " + e.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(", "));
        return label.isEmpty() ? null : label;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
