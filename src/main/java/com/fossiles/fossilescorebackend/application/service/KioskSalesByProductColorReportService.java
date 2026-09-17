package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.ColorCell;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.ColorRef;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.KioskCell;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.KioskRef;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.ProductRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse.Totals;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskSalesByProductColorReportService {

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final KioscoMovementRepository kioscoMovementRepository;

    public KioskSalesByProductColorReportResponse getReport(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId,
            boolean includeZeroSales
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new BusinessException(
                    "Solo administradores, logística o contabilidad pueden ver este reporte.");
        }
        return buildReport(startDate, endDate, kioskLocationId, includeZeroSales);
    }

    KioskSalesByProductColorReportResponse buildReport(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId,
            boolean includeZeroSales
    ) throws BusinessException {
        LocalDate[] range = normalizeRange(startDate, endDate);
        LocalDate from = range[0];
        LocalDate to = range[1];

        List<LocationEntity> targetKiosks = resolveTargetKiosks(kioskLocationId);
        if (targetKiosks.isEmpty()) {
            throw new BusinessException("No hay kioskos para generar el reporte.");
        }
        List<Long> kioskIds = targetKiosks.stream().map(LocationEntity::getId).toList();

        Map<SkuKioskKey, MutableCell> cells = new HashMap<>();
        Set<Long> productIds = new HashSet<>();
        Set<Long> colorIds = new HashSet<>();

        List<Object[]> stockRows = kioscoStockRepository.aggregateStockByProductColor(kioskIds);
        List<Object[]> saleRows = kioskSaleItemRepository.aggregateCompletedSalesByProductColor(from, to, kioskIds);
        LocalDateTime fromAt = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();
        List<Object[]> entryRows = kioscoMovementRepository.aggregateEntriesByProductColor(kioskIds, fromAt, toExclusive);

        for (Object[] row : stockRows) {
            Long colorId = asLong(row[2]);
            if (colorId != null) {
                colorIds.add(colorId);
            }
        }
        for (Object[] row : saleRows) {
            Long colorId = asLong(row[1]);
            if (colorId != null) {
                colorIds.add(colorId);
            }
        }
        for (Object[] row : entryRows) {
            Long colorId = asLong(row[2]);
            if (colorId != null) {
                colorIds.add(colorId);
            }
        }

        Map<Long, ColorEntity> colorsById = colorIds.isEmpty()
                ? Map.of()
                : colorRepository.findAllById(new ArrayList<>(colorIds)).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(ColorEntity::getId, item -> item, (a, b) -> a));

        for (Object[] row : stockRows) {
            Long productId = asLong(row[1]);
            if (productId == null) {
                continue;
            }
            Long colorId = asLong(row[2]);
            Long kioskId = asLong(row[0]);
            String colorNorm = colorKey(catalogColorName(colorId, colorsById), colorId);
            MutableCell cell = cells.computeIfAbsent(new SkuKioskKey(productId, colorNorm, kioskId), k -> new MutableCell());
            cell.stock += asInt(row[3]);
            mergeColorIdentity(cell, colorId, catalogColorName(colorId, colorsById));
            productIds.add(productId);
        }

        for (Object[] row : saleRows) {
            Long productId = asLong(row[0]);
            if (productId == null) {
                continue;
            }
            Long colorId = asLong(row[1]);
            String saleColorName = asString(row[2]);
            Long kioskId = asLong(row[3]);
            String displayName = !saleColorName.isEmpty() ? saleColorName : catalogColorName(colorId, colorsById);
            String colorNorm = colorKey(displayName, colorId);
            MutableCell cell = cells.computeIfAbsent(new SkuKioskKey(productId, colorNorm, kioskId), k -> new MutableCell());
            cell.quantity = cell.quantity.add(asDecimal(row[4]));
            cell.amount = cell.amount.add(asDecimal(row[5]));
            cell.tickets += asInt(row[6]);
            mergeColorIdentity(cell, colorId, displayName);
            productIds.add(productId);
        }

        for (Object[] row : entryRows) {
            Long productId = asLong(row[1]);
            if (productId == null) {
                continue;
            }
            Long colorId = asLong(row[2]);
            Long kioskId = asLong(row[0]);
            String colorNorm = colorKey(catalogColorName(colorId, colorsById), colorId);
            MutableCell cell = cells.computeIfAbsent(new SkuKioskKey(productId, colorNorm, kioskId), k -> new MutableCell());
            cell.entries += asInt(row[3]);
            mergeColorIdentity(cell, colorId, catalogColorName(colorId, colorsById));
            productIds.add(productId);
        }

        Map<Long, ProductEntity> productsById = productRepository.findAllById(new ArrayList<>(productIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ProductEntity::getId, item -> item, (a, b) -> a));
        Set<Long> categoryIds = productsById.values().stream()
                .map(ProductEntity::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductCategoryEntity> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : productCategoryRepository.findAllById(new ArrayList<>(categoryIds)).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(ProductCategoryEntity::getId, item -> item, (a, b) -> a));

        Map<SkuKey, Map<Long, MutableCell>> bySku = new HashMap<>();
        for (Map.Entry<SkuKioskKey, MutableCell> entry : cells.entrySet()) {
            SkuKioskKey key = entry.getKey();
            bySku.computeIfAbsent(new SkuKey(key.productId(), key.colorNorm()), k -> new HashMap<>())
                    .put(key.kioskId(), entry.getValue());
        }

        Map<Long, ProductAccumulator> products = new LinkedHashMap<>();
        Map<String, ColorRef> colorRefsByNorm = new LinkedHashMap<>();

        for (Map.Entry<SkuKey, Map<Long, MutableCell>> skuEntry : bySku.entrySet()) {
            SkuKey sku = skuEntry.getKey();
            Map<Long, MutableCell> byKiosk = skuEntry.getValue();
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal amount = BigDecimal.ZERO;
            int tickets = 0;
            int stock = 0;
            int entries = 0;
            Long colorId = null;
            String colorName = "Sin color";
            List<KioskCell> kioskCells = new ArrayList<>();
            for (LocationEntity kiosk : targetKiosks) {
                MutableCell cell = byKiosk.get(kiosk.getId());
                if (cell == null) {
                    continue;
                }
                qty = qty.add(cell.quantity);
                amount = amount.add(cell.amount);
                tickets += cell.tickets;
                stock += cell.stock;
                entries += cell.entries;
                if (colorId == null && cell.colorId != null) {
                    colorId = cell.colorId;
                }
                if (cell.colorName != null && !cell.colorName.isBlank()) {
                    colorName = cell.colorName.trim();
                }
                if (cell.quantity.signum() > 0 || cell.stock > 0 || cell.tickets > 0 || cell.entries > 0) {
                    kioskCells.add(toKioskCell(kiosk.getId(), cell));
                }
            }
            if (!includeZeroSales && qty.signum() <= 0 && stock <= 0 && entries <= 0) {
                continue;
            }

            ProductAccumulator acc = products.computeIfAbsent(sku.productId(), id -> {
                ProductEntity product = productsById.get(id);
                ProductCategoryEntity category = product != null && product.getCategoryId() != null
                        ? categoriesById.get(product.getCategoryId())
                        : null;
                ProductAccumulator created = new ProductAccumulator();
                created.productId = id;
                created.productCode = product != null ? safe(product.getCode()) : "";
                created.productName = product != null ? safe(product.getName()) : "Producto " + id;
                created.categoryId = product != null ? product.getCategoryId() : null;
                created.categoryName = category != null ? safe(category.getName()) : "";
                created.audienceCategory = ProductAudienceCategory.normalizeProductAudience(
                        product != null ? product.getAudienceCategory() : null);
                return created;
            });

            ColorCell colorCell = ColorCell.builder()
                    .colorId(colorId)
                    .colorName(colorName)
                    .quantity(qty.setScale(3, RoundingMode.HALF_UP))
                    .amount(amount.setScale(2, RoundingMode.HALF_UP))
                    .tickets(tickets)
                    .currentStock(stock)
                    .quantityIn(entries)
                    .byKiosk(kioskCells)
                    .build();
            acc.colors.add(colorCell);
            acc.totalQuantity = acc.totalQuantity.add(qty);
            acc.totalAmount = acc.totalAmount.add(amount);
            acc.totalTickets += tickets;
            acc.currentStock += stock;
            acc.totalQuantityIn += entries;
            if (qty.signum() > 0) {
                acc.colorsWithSales += 1;
            } else {
                acc.colorsWithoutSales += 1;
            }
            colorRefsByNorm.putIfAbsent(sku.colorNorm(), ColorRef.builder()
                    .id(colorId)
                    .name(colorName)
                    .build());
        }

        List<ProductRow> productRows = products.values().stream()
                .map(acc -> {
                    acc.colors.sort(Comparator
                            .comparing((ColorCell c) -> c.getColorName() == null ? "" : c.getColorName(),
                                    String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(c -> c.getColorId() == null ? 0L : c.getColorId()));
                    Map<Long, MutableCell> kioskTotals = new HashMap<>();
                    for (ColorCell color : acc.colors) {
                        for (KioskCell kioskCell : color.getByKiosk()) {
                            MutableCell total = kioskTotals.computeIfAbsent(
                                    kioskCell.getKioskLocationId(), id -> new MutableCell());
                            total.quantity = total.quantity.add(nz(kioskCell.getQuantity()));
                            total.amount = total.amount.add(nz(kioskCell.getAmount()));
                            total.tickets += kioskCell.getTickets() == null ? 0 : kioskCell.getTickets();
                            total.stock += kioskCell.getCurrentStock() == null ? 0 : kioskCell.getCurrentStock();
                            total.entries += kioskCell.getQuantityIn() == null ? 0 : kioskCell.getQuantityIn();
                        }
                    }
                    List<KioskCell> kioskRows = targetKiosks.stream()
                            .map(kiosk -> {
                                MutableCell cell = kioskTotals.get(kiosk.getId());
                                return cell == null ? null : toKioskCell(kiosk.getId(), cell);
                            })
                            .filter(Objects::nonNull)
                            .toList();
                    return ProductRow.builder()
                            .productId(acc.productId)
                            .productCode(acc.productCode)
                            .productName(acc.productName)
                            .categoryId(acc.categoryId)
                            .categoryName(acc.categoryName)
                            .audienceCategory(acc.audienceCategory)
                            .totalQuantity(acc.totalQuantity.setScale(3, RoundingMode.HALF_UP))
                            .totalAmount(acc.totalAmount.setScale(2, RoundingMode.HALF_UP))
                            .totalTickets(acc.totalTickets)
                            .currentStock(acc.currentStock)
                            .totalQuantityIn(acc.totalQuantityIn)
                            .colorsWithSales(acc.colorsWithSales)
                            .colorsWithoutSales(acc.colorsWithoutSales)
                            .colors(acc.colors)
                            .kiosks(kioskRows)
                            .build();
                })
                .sorted(Comparator
                        .comparing((ProductRow row) -> nz(row.getTotalQuantity())).reversed()
                        .thenComparing(row -> row.getProductCode() == null ? "" : row.getProductCode(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<ColorRef> colorRefs = colorRefsByNorm.values().stream()
                .sorted(Comparator.comparing(ref -> ref.getName() == null ? "" : ref.getName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        int productsWithSales = 0;
        int productsWithoutSales = 0;
        int combos = 0;
        int combosWithSales = 0;
        int combosWithoutSales = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalTickets = 0;
        int totalEntries = 0;
        int totalStock = 0;
        for (ProductRow row : productRows) {
            if (nz(row.getTotalQuantity()).signum() > 0) {
                productsWithSales += 1;
            } else {
                productsWithoutSales += 1;
            }
            totalQty = totalQty.add(nz(row.getTotalQuantity()));
            totalAmount = totalAmount.add(nz(row.getTotalAmount()));
            totalTickets += row.getTotalTickets() == null ? 0 : row.getTotalTickets();
            totalEntries += row.getTotalQuantityIn() == null ? 0 : row.getTotalQuantityIn();
            totalStock += row.getCurrentStock() == null ? 0 : row.getCurrentStock();
            combos += row.getColors() == null ? 0 : row.getColors().size();
            combosWithSales += row.getColorsWithSales() == null ? 0 : row.getColorsWithSales();
            combosWithoutSales += row.getColorsWithoutSales() == null ? 0 : row.getColorsWithoutSales();
        }

        String kioskLabel;
        if (kioskLocationId == null) {
            kioskLabel = "Todos los kioskos";
        } else {
            LocationEntity selected = targetKiosks.get(0);
            kioskLabel = formatKioskLabel(selected);
        }

        return KioskSalesByProductColorReportResponse.builder()
                .startDate(from)
                .endDate(to)
                .kioskLocationId(kioskLocationId)
                .kioskLabel(kioskLabel)
                .kiosks(targetKiosks.stream()
                        .map(kiosk -> KioskRef.builder()
                                .id(kiosk.getId())
                                .code(safe(kiosk.getCode()))
                                .name(safe(kiosk.getName()))
                                .build())
                        .toList())
                .colors(colorRefs)
                .products(productRows)
                .totals(Totals.builder()
                        .quantity(totalQty.setScale(3, RoundingMode.HALF_UP))
                        .amount(totalAmount.setScale(2, RoundingMode.HALF_UP))
                        .tickets(totalTickets)
                        .products(productRows.size())
                        .productsWithSales(productsWithSales)
                        .productsWithoutSales(productsWithoutSales)
                        .quantityIn(totalEntries)
                        .currentStock(totalStock)
                        .colorCombinations(combos)
                        .colorCombinationsWithSales(combosWithSales)
                        .colorCombinationsWithoutSales(combosWithoutSales)
                        .build())
                .build();
    }

    private List<LocationEntity> resolveTargetKiosks(Long kioskLocationId) throws BusinessException {
        List<LocationEntity> kiosks = locationRepository.findAll().stream()
                .filter(this::isKioskLocation)
                .sorted(Comparator.comparing(item -> safe(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        if (kioskLocationId == null) {
            return kiosks.stream()
                    .filter(kiosk -> !Boolean.TRUE.equals(kiosk.getPosTestMode()))
                    .toList();
        }
        LocationEntity selected = kiosks.stream()
                .filter(kiosk -> Objects.equals(kiosk.getId(), kioskLocationId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            throw new BusinessException("El kiosko seleccionado no existe o no es un kiosko.");
        }
        return List.of(selected);
    }

    private boolean isKioskLocation(LocationEntity location) {
        String categoria = normalizeText(location != null ? location.getCategoria() : null);
        String name = normalizeText(location != null ? location.getName() : null);
        String code = normalizeText(location != null ? location.getCode() : null);
        return categoria.contains("KIOS")
                || name.contains("KIOS")
                || code.startsWith("K");
    }

    private LocalDate[] normalizeRange(LocalDate startDate, LocalDate endDate) {
        LocalDate today = GuatemalaDateTime.today();
        LocalDate from = startDate != null ? startDate : today.withDayOfMonth(1);
        LocalDate to = endDate != null ? endDate : today;
        if (to.isBefore(from)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        return new LocalDate[] { from, to };
    }

    private UserEntity getCurrentUserOrThrow() throws BusinessException {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
    }

    private static KioskCell toKioskCell(Long kioskId, MutableCell cell) {
        return KioskCell.builder()
                .kioskLocationId(kioskId)
                .quantity(cell.quantity.setScale(3, RoundingMode.HALF_UP))
                .amount(cell.amount.setScale(2, RoundingMode.HALF_UP))
                .tickets(cell.tickets)
                .currentStock(cell.stock)
                .quantityIn(cell.entries)
                .build();
    }

    private static String formatKioskLabel(LocationEntity kiosk) {
        String name = safe(kiosk.getName());
        String code = safe(kiosk.getCode());
        if (!code.isEmpty()) {
            return name + " (" + code + ")";
        }
        return name.isEmpty() ? "Kiosko " + kiosk.getId() : name;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeText(String value) {
        return safe(value)
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replaceAll("\\s+", " ");
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String catalogColorName(Long colorId, Map<Long, ColorEntity> colorsById) {
        if (colorId == null) {
            return "";
        }
        ColorEntity color = colorsById.get(colorId);
        return color != null ? safe(color.getName()) : "";
    }

    private static String colorKey(String name, Long colorId) {
        String normalized = normalizeColorName(name);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return colorId == null ? "SIN_COLOR" : "ID:" + colorId;
    }

    private static String normalizeColorName(String value) {
        String text = normalizeText(value);
        if (text.equals("SIN COLOR") || text.equals("SINCOLOR")) {
            return "";
        }
        return text;
    }

    private static void mergeColorIdentity(MutableCell cell, Long colorId, String colorName) {
        if (cell.colorId == null && colorId != null) {
            cell.colorId = colorId;
        }
        String name = safe(colorName);
        if (!name.isEmpty()) {
            cell.colorName = name;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return Long.parseLong(text);
    }

    private static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        return (int) Double.parseDouble(text);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(text);
    }

    private record SkuKey(Long productId, String colorNorm) {
    }

    private record SkuKioskKey(Long productId, String colorNorm, Long kioskId) {
    }

    private static final class MutableCell {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private int tickets;
        private int stock;
        private int entries;
        private Long colorId;
        private String colorName;
    }

    private static final class ProductAccumulator {
        private Long productId;
        private String productCode;
        private String productName;
        private Long categoryId;
        private String categoryName;
        private String audienceCategory;
        private BigDecimal totalQuantity = BigDecimal.ZERO;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private int totalTickets;
        private int currentStock;
        private int totalQuantityIn;
        private int colorsWithSales;
        private int colorsWithoutSales;
        private final List<ColorCell> colors = new ArrayList<>();
    }
}
