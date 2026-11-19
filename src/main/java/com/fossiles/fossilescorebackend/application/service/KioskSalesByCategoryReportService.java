package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Row;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Totals;
import com.fossiles.fossilescorebackend.application.dto.response.KioskTopProductsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.KioskSalePricing;
import com.fossiles.fossilescorebackend.application.util.KioskSalesCategorySubgroup;
import com.fossiles.fossilescorebackend.application.util.KioskSalesCategorySubgroup.Subgroup;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSalesByCategoryReportRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSalesByCategoryReportRepository.ReturnRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSalesByCategoryReportRepository.SaleLine;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.FelIvaCalculator;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskSalesByCategoryReportService {

    static final String SIN_CATEGORIA = "Sin categoría";
    static final int MAX_RANGE_DAYS = 366;
    private static final int TOP_COLORS = 3;

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final KioskSalesByCategoryReportRepository reportRepository;

    public KioskSalesByCategoryReportResponse getReport(LocalDate from, LocalDate to, String kioskIds)
            throws BusinessException {
        return getReport(from, to, kioskIds, false);
    }

    /** Con {@code compare} agrega el periodo anterior (mismos días justo antes de {@code from}). */
    public KioskSalesByCategoryReportResponse getReport(
            LocalDate from, LocalDate to, String kioskIds, boolean compare
    ) throws BusinessException {
        validateRange(from, to);
        List<Long> requestedIds = parseKioskIds(kioskIds);
        requireReportsAccess();
        List<Long> locationIds = resolveKioskIds(requestedIds);
        KioskSalesByCategoryReportResponse current = build(from, to, locationIds);
        if (compare) {
            LocalDate[] prev = previousRange(from, to);
            KioskSalesByCategoryReportResponse previous = build(prev[0], prev[1], locationIds);
            current.setPreviousFrom(prev[0]);
            current.setPreviousTo(prev[1]);
            current.setPrevious(previous);
        }
        return current;
    }

    /**
     * Productos de una categoría (y subgrupo) de más a menos vendidos, con las mismas reglas de
     * importe y devoluciones del reporte. {@code categoryId} nulo = «Sin categoría».
     * Sin {@code subgroupCode} se excluye el empaque.
     */
    public KioskTopProductsResponse getTopProducts(
            LocalDate from, LocalDate to, String kioskIds,
            Long categoryId, String subgroupCode, boolean compare
    ) throws BusinessException {
        validateRange(from, to);
        List<Long> requestedIds = parseKioskIds(kioskIds);
        requireReportsAccess();
        List<Long> locationIds = resolveKioskIds(requestedIds);
        String wantedSubgroup = subgroupCode == null || subgroupCode.isBlank()
                ? null : subgroupCode.trim().toUpperCase(Locale.ROOT);

        Ctx current = collect(from, to, locationIds);
        Ctx previous = null;
        LocalDate[] prev = null;
        if (compare) {
            prev = previousRange(from, to);
            previous = collect(prev[0], prev[1], locationIds);
        }

        String categoryName = null;
        BigDecimal totalUnits = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal prevUnits = BigDecimal.ZERO;
        BigDecimal prevAmount = BigDecimal.ZERO;
        List<KioskTopProductsResponse.Item> items = new ArrayList<>();
        for (ProdAcc acc : current.byProduct.values()) {
            if (!matchesTop(acc, categoryId, wantedSubgroup)) {
                continue;
            }
            categoryName = acc.categoryName;
            totalUnits = totalUnits.add(acc.units);
            totalAmount = totalAmount.add(acc.amount);
            ProdAcc before = previous == null ? null : previous.byProduct.get(acc.productId);
            if (before != null) {
                prevUnits = prevUnits.add(before.units);
                prevAmount = prevAmount.add(before.amount);
            }
            if (acc.units.signum() <= 0) {
                continue;
            }
            BigDecimal amount = money(acc.amount);
            items.add(KioskTopProductsResponse.Item.builder()
                    .productId(acc.productId)
                    .productCode(acc.code)
                    .productName(acc.name)
                    .subgroupCode(acc.subgroupCode)
                    .subgroupLabel(acc.subgroupLabel)
                    .units(units(acc.units))
                    .totalAmount(amount)
                    .totalAmountNoIva(withoutIva(amount))
                    .kiosksSold(acc.kiosks.size())
                    .previousUnits(previous == null ? null : units(before == null ? BigDecimal.ZERO : before.units))
                    .previousAmount(previous == null ? null : money(before == null ? BigDecimal.ZERO : before.amount))
                    .topColors(topColors(acc))
                    .build());
        }
        // Productos que solo vendieron el periodo anterior no aparecen: el top mira lo vendido ahora.
        items.sort(Comparator.comparing(KioskTopProductsResponse.Item::getUnits, Comparator.reverseOrder())
                .thenComparing(KioskTopProductsResponse.Item::getTotalAmount, Comparator.reverseOrder())
                .thenComparing(KioskTopProductsResponse.Item::getProductName, Comparator.nullsLast(String::compareTo)));
        BigDecimal amountTotal = money(totalAmount);
        return KioskTopProductsResponse.builder()
                .from(from)
                .to(to)
                .previousFrom(prev == null ? null : prev[0])
                .previousTo(prev == null ? null : prev[1])
                .categoryId(categoryId)
                .categoryName(categoryName == null ? SIN_CATEGORIA : categoryName)
                .subgroupCode(wantedSubgroup)
                .kioskCount(locationIds.size())
                .totals(KioskTopProductsResponse.Totals.builder()
                        .units(units(totalUnits))
                        .totalAmount(amountTotal)
                        .totalAmountNoIva(withoutIva(amountTotal))
                        .products(items.size())
                        .previousUnits(previous == null ? null : units(prevUnits))
                        .previousAmount(previous == null ? null : money(prevAmount))
                        .build())
                .items(items)
                .build();
    }

    private static boolean matchesTop(ProdAcc acc, Long categoryId, String wantedSubgroup) {
        if (!Objects.equals(acc.categoryId, categoryId)) {
            return false;
        }
        if (wantedSubgroup == null) {
            return !KioskSalesCategorySubgroup.EMPAQUE.equals(acc.subgroupCode);
        }
        return wantedSubgroup.equals(acc.subgroupCode);
    }

    private static List<KioskTopProductsResponse.ColorUnits> topColors(ProdAcc acc) {
        return acc.colors.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_COLORS)
                .map(entry -> KioskTopProductsResponse.ColorUnits.builder()
                        .color(entry.getKey())
                        .units(units(entry.getValue()))
                        .build())
                .toList();
    }

    /** Los {@code n} días inmediatamente anteriores a {@code from}, con la misma duración que el rango. */
    static LocalDate[] previousRange(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        return new LocalDate[]{prevTo.minusDays(days - 1), prevTo};
    }

    private Ctx collect(LocalDate from, LocalDate to, List<Long> kioskIds) {
        Ctx ctx = new Ctx();
        addSales(ctx, reportRepository.findSaleLines(from, to, kioskIds));
        addReturns(ctx, reportRepository.findReturns(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), kioskIds));
        return ctx;
    }

    KioskSalesByCategoryReportResponse build(LocalDate from, LocalDate to, List<Long> kioskIds) {
        Map<AccKey, Acc> byRow = collect(from, to, kioskIds).byRow;

        List<Row> rows = new ArrayList<>();
        BigDecimal totalUnits = BigDecimal.ZERO;
        BigDecimal packagingUnits = BigDecimal.ZERO;
        BigDecimal packagingAmount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Acc acc : byRow.values()) {
            BigDecimal units = units(acc.units);
            BigDecimal amount = money(acc.amount);
            rows.add(Row.builder()
                    .categoryId(acc.categoryId)
                    .categoryName(acc.categoryName)
                    .subgroupCode(acc.subgroupCode)
                    .subgroupLabel(acc.subgroupLabel)
                    .units(units)
                    .totalAmount(amount)
                    .totalAmountNoIva(withoutIva(amount))
                    .build());
            if (KioskSalesCategorySubgroup.EMPAQUE.equals(acc.subgroupCode)) {
                packagingUnits = packagingUnits.add(acc.units);
                packagingAmount = packagingAmount.add(amount);
            } else {
                totalUnits = totalUnits.add(acc.units);
            }
            totalAmount = totalAmount.add(amount);
        }
        rows.sort(Comparator.comparing(Row::getTotalAmount, Comparator.reverseOrder())
                .thenComparing(Row::getCategoryName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Row::getSubgroupLabel, Comparator.nullsLast(String::compareTo)));
        totalAmount = money(totalAmount);
        return KioskSalesByCategoryReportResponse.builder()
                .from(from)
                .to(to)
                .rows(rows)
                .totals(Totals.builder()
                        .units(units(totalUnits))
                        .totalAmount(totalAmount)
                        .totalAmountNoIva(withoutIva(totalAmount))
                        .packagingUnits(units(packagingUnits))
                        .packagingAmount(money(packagingAmount))
                        .build())
                .build();
    }

    private void addSales(Ctx ctx, List<SaleLine> lines) {
        Map<Long, List<SaleLine>> bySale = new LinkedHashMap<>();
        for (SaleLine line : lines) {
            if (line.saleId() == null || line.lineId() == null) {
                continue;
            }
            bySale.computeIfAbsent(line.saleId(), id -> new ArrayList<>()).add(line);
        }
        for (List<SaleLine> saleLines : bySale.values()) {
            SaleLine head = saleLines.get(0);
            List<KioskSaleItemEntity> items = new ArrayList<>();
            for (SaleLine line : saleLines) {
                items.add(toItem(line.lineId(), line.productCode(), line.quantity(), line.unitPrice(),
                        line.lineTotal(), line.lineDiscount()));
            }
            KioskSaleEntity sale = KioskSaleEntity.builder()
                    .id(head.saleId())
                    .discountAmount(head.saleDiscount())
                    .items(items)
                    .build();
            Map<Long, BigDecimal> paid = KioskSalePricing.paidLineAmounts(sale, items);
            for (int i = 0; i < saleLines.size(); i++) {
                SaleLine line = saleLines.get(i);
                BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
                BigDecimal amount = paid.getOrDefault(line.lineId(), BigDecimal.ZERO);
                add(ctx, line.categoryId(), line.categoryName(), catalogProduct(line), qty, amount,
                        line.kioskId(), line.colorName());
            }
        }
    }

    private void addReturns(Ctx ctx, List<ReturnRow> rows) {
        Map<Long, List<ReturnRow>> bySlip = new LinkedHashMap<>();
        for (ReturnRow row : rows) {
            if (row.slipId() == null) {
                continue;
            }
            bySlip.computeIfAbsent(row.slipId(), id -> new ArrayList<>()).add(row);
        }
        List<List<ReturnRow>> slips = new ArrayList<>(bySlip.values());
        slips.sort(Comparator
                .comparing((List<ReturnRow> slip) -> slip.get(0).returnedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(slip -> slip.get(0).slipId()));
        Map<Long, BigDecimal> consumed = new HashMap<>();
        for (List<ReturnRow> slipRows : slips) {
            ReturnRow head = slipRows.get(0);
            if ("EXCHANGE".equalsIgnoreCase(head.slipKind())) {
                if (head.inPeriod()) {
                    addExchangeUnits(ctx, slipRows);
                }
                continue;
            }
            boolean hasItemRows = slipRows.stream().anyMatch(row -> row.returnedItemId() != null);
            if (hasItemRows) {
                addReturnedItemRows(ctx, slipRows, consumed);
            } else {
                addHeaderReturn(ctx, slipRows, consumed);
            }
        }
    }

    /** El cambio mueve unidades. El dinero queda en la venta original; la venta nueva ya neteó el crédito. */
    private void addExchangeUnits(Ctx ctx, List<ReturnRow> slipRows) {
        boolean hasItemRows = slipRows.stream().anyMatch(row -> row.returnedItemId() != null);
        if (!hasItemRows) {
            ReturnRow head = slipRows.get(0);
            add(ctx, head.categoryId(), head.categoryName(), catalogProduct(head),
                    negate(head.headerQuantity()), BigDecimal.ZERO);
            return;
        }
        Map<Long, ReturnRow> byItem = new LinkedHashMap<>();
        for (ReturnRow row : slipRows) {
            if (row.returnedItemId() != null) {
                byItem.putIfAbsent(row.returnedItemId(), row);
            }
        }
        for (ReturnRow row : byItem.values()) {
            add(ctx, row.categoryId(), row.categoryName(), catalogProduct(row),
                    negate(row.returnedItemQuantity()), BigDecimal.ZERO);
        }
    }

    private void addReturnedItemRows(
            Ctx ctx,
            List<ReturnRow> slipRows,
            Map<Long, BigDecimal> consumed
    ) {
        Map<Long, List<ReturnRow>> byItem = new LinkedHashMap<>();
        for (ReturnRow row : slipRows) {
            if (row.returnedItemId() == null) {
                continue;
            }
            byItem.computeIfAbsent(row.returnedItemId(), id -> new ArrayList<>()).add(row);
        }
        List<List<ReturnRow>> itemGroups = new ArrayList<>(byItem.values());
        itemGroups.sort(Comparator.comparing(group -> group.get(0).itemLineNo(), Comparator.nullsLast(Comparator.naturalOrder())));
        boolean inPeriod = slipRows.get(0).inPeriod();
        for (List<ReturnRow> itemRows : itemGroups) {
            ReturnRow head = itemRows.get(0);
            BigDecimal amount = cappedReturnAmount(
                    head.returnedItemSaleItemId(),
                    head.returnedItemQuantity(),
                    head.returnedItemLineTotal(),
                    itemRows,
                    consumed);
            if (inPeriod) {
                add(ctx, head.categoryId(), head.categoryName(), catalogProduct(head),
                        negate(head.returnedItemQuantity()), negate(amount));
            }
        }
    }

    private void addHeaderReturn(
            Ctx ctx,
            List<ReturnRow> slipRows,
            Map<Long, BigDecimal> consumed
    ) {
        ReturnRow head = slipRows.get(0);
        BigDecimal amount = cappedReturnAmount(
                head.headerSaleItemId(),
                head.headerQuantity(),
                head.headerAmount(),
                slipRows,
                consumed);
        if (head.inPeriod()) {
            add(ctx, head.categoryId(), head.categoryName(), catalogProduct(head),
                    negate(head.headerQuantity()), negate(amount));
        }
    }

    /**
     * Importe al precio pagado, sin pasar lo que aún no se había devuelto de esa línea.
     * Los slips anteriores (aunque caigan fuera del rango) consumen el saldo.
     */
    private static BigDecimal cappedReturnAmount(
            Long originalSaleItemId,
            BigDecimal returnedQty,
            BigDecimal fallbackAmount,
            List<ReturnRow> rows,
            Map<Long, BigDecimal> consumed
    ) {
        List<KioskSaleItemEntity> items = saleItems(rows);
        KioskSaleItemEntity target = items.stream()
                .filter(item -> Objects.equals(item.getId(), originalSaleItemId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return money(fallbackAmount);
        }
        BigDecimal linePaid = paidOf(rows, items).getOrDefault(target.getId(), BigDecimal.ZERO);
        BigDecimal requested = KioskSalePricing.paidPortion(linePaid, target.getQuantity(), returnedQty);
        BigDecimal already = consumed.getOrDefault(target.getId(), BigDecimal.ZERO);
        BigDecimal remaining = linePaid.subtract(already);
        if (remaining.signum() < 0) {
            remaining = BigDecimal.ZERO;
        }
        BigDecimal capped = money(requested).min(money(remaining));
        if (capped.signum() < 0) {
            capped = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        consumed.put(target.getId(), already.add(capped));
        return capped;
    }

    private static Map<Long, BigDecimal> paidOf(List<ReturnRow> rows, List<KioskSaleItemEntity> items) {
        ReturnRow head = rows.get(0);
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .id(head.saleId())
                .discountAmount(head.saleDiscount())
                .items(items)
                .build();
        return KioskSalePricing.paidLineAmounts(sale, items);
    }

    private static List<KioskSaleItemEntity> saleItems(List<ReturnRow> rows) {
        Map<Long, KioskSaleItemEntity> byId = new LinkedHashMap<>();
        for (ReturnRow row : rows) {
            if (row.lineId() == null || byId.containsKey(row.lineId())) {
                continue;
            }
            byId.put(row.lineId(), toItem(row.lineId(), row.lineProductCode(), row.lineQuantity(),
                    row.lineUnitPrice(), row.lineTotal(), row.lineDiscount()));
        }
        return new ArrayList<>(byId.values());
    }

    /** Devoluciones y cambios: restan, y no traen kiosko ni color. */
    private static void add(
            Ctx ctx,
            Long categoryId,
            String categoryName,
            ProductEntity product,
            BigDecimal units,
            BigDecimal amount
    ) {
        add(ctx, categoryId, categoryName, product, units, amount, null, null);
    }

    private static void add(
            Ctx ctx,
            Long categoryId,
            String categoryName,
            ProductEntity product,
            BigDecimal units,
            BigDecimal amount,
            Long kioskId,
            String colorName
    ) {
        Map<AccKey, Acc> byRow = ctx.byRow;
        String name = categoryName == null || categoryName.isBlank() ? SIN_CATEGORIA : categoryName.trim();
        Long resolvedCategoryId = SIN_CATEGORIA.equals(name) && (categoryName == null || categoryName.isBlank())
                ? null
                : categoryId;
        Subgroup subgroup = KioskSalesCategorySubgroup.classify(product);
        String subgroupCode = subgroup == null ? null : subgroup.code();
        String subgroupLabel = subgroup == null ? null : subgroup.label();
        AccKey accKey = new AccKey(resolvedCategoryId, subgroupCode);
        Acc acc = byRow.computeIfAbsent(accKey,
                ignored -> new Acc(resolvedCategoryId, name, subgroupCode, subgroupLabel));
        if (acc.categoryName == null || acc.categoryName.isBlank()) {
            acc.categoryName = name;
        }
        BigDecimal addUnits = units == null ? BigDecimal.ZERO : units;
        BigDecimal addAmount = amount == null ? BigDecimal.ZERO : amount;
        acc.units = acc.units.add(addUnits);
        acc.amount = acc.amount.add(addAmount);

        if (product != null && product.getId() != null) {
            ProdAcc prod = ctx.byProduct.computeIfAbsent(product.getId(),
                    id -> new ProdAcc(id, product.getCode(), product.getName(),
                            resolvedCategoryId, name, subgroupCode, subgroupLabel));
            prod.units = prod.units.add(addUnits);
            prod.amount = prod.amount.add(addAmount);
            if (kioskId != null && addUnits.signum() > 0) {
                prod.kiosks.add(kioskId);
            }
            if (colorName != null && !colorName.isBlank() && addUnits.signum() > 0) {
                prod.colors.merge(colorName.trim(), addUnits, BigDecimal::add);
            }
        }
    }

    private static ProductEntity catalogProduct(SaleLine line) {
        return catalogProduct(
                line.catalogProductId(),
                line.catalogProductCode(),
                line.catalogProductName(),
                line.audienceCategory(),
                line.cinchoType(),
                line.cinchoForKids());
    }

    private static ProductEntity catalogProduct(ReturnRow row) {
        return catalogProduct(
                row.catalogProductId(),
                row.catalogProductCode(),
                row.catalogProductName(),
                row.audienceCategory(),
                row.cinchoType(),
                row.cinchoForKids());
    }

    private static ProductEntity catalogProduct(
            Long id,
            String code,
            String name,
            String audienceCategory,
            String cinchoType,
            Boolean cinchoForKids
    ) {
        if (id == null) {
            return null;
        }
        return ProductEntity.builder()
                .id(id)
                .code(code)
                .name(name)
                .audienceCategory(audienceCategory)
                .cinchoType(cinchoType)
                .cinchoForKids(Boolean.TRUE.equals(cinchoForKids))
                .build();
    }

    private static KioskSaleItemEntity toItem(
            Long id,
            String productCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal discountAmount
    ) {
        return KioskSaleItemEntity.builder()
                .id(id)
                .productCode(productCode)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .lineTotal(lineTotal)
                .discountAmount(discountAmount)
                .build();
    }

    private void requireReportsAccess() {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("No se pudo identificar el usuario autenticado.");
        }
        UserEntity user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("No se pudo identificar el usuario autenticado."));
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new AccessDeniedException(
                    "Solo administradores, logística o contabilidad pueden ver este reporte.");
        }
    }

    /**
     * Misma regla que {@code KioskSalesByProductColorReportService}: sin ids, todos los kioskos
     * excepto los de piloto ({@code posTestMode}) y Entrecueros ({@code pos_mode} o nombre).
     * Un id explícito se acepta aunque sea piloto o Entrecueros (el SQL omite sus datos).
     */
    private List<Long> resolveKioskIds(List<Long> requestedIds) throws BusinessException {
        List<LocationEntity> kiosks = locationRepository.findAll().stream()
                .filter(KioskSalesByCategoryReportService::isKioskLocation)
                .sorted(Comparator.comparing(item -> safe(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (requestedIds.isEmpty()) {
            List<Long> ids = kiosks.stream()
                    .filter(kiosk -> !Boolean.TRUE.equals(kiosk.getPosTestMode()))
                    .filter(kiosk -> !isExcludedEntrecueros(kiosk))
                    .map(LocationEntity::getId)
                    .toList();
            if (ids.isEmpty()) {
                throw new BusinessException("No hay kioskos para generar el reporte.");
            }
            return ids;
        }
        for (Long id : requestedIds) {
            boolean known = kiosks.stream().anyMatch(kiosk -> Objects.equals(kiosk.getId(), id));
            if (!known) {
                throw new BusinessException("El kiosko seleccionado no existe o no es un kiosko.");
            }
        }
        return requestedIds.stream().distinct().toList();
    }

    static void validateRange(LocalDate from, LocalDate to) throws BusinessException {
        if (from == null || to == null) {
            throw new BusinessException("La fecha inicial y la fecha final son obligatorias.");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException("El rango no puede superar 366 días.");
        }
    }

    static List<Long> parseKioskIds(String raw) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(token);
                if (id <= 0) {
                    throw new NumberFormatException(token);
                }
                ids.add(id);
            } catch (NumberFormatException ex) {
                throw new BusinessException("Los identificadores de kiosko no son válidos.");
            }
        }
        return ids;
    }

    /** pos_mode ENTRECUEROS (trim/upper) o el nombre coincide con {@code %entrecuer%}. */
    static boolean isExcludedEntrecueros(LocationEntity location) {
        if (location == null) {
            return false;
        }
        if (KioskPosMode.isEntrecueros(location.getPosMode())) {
            return true;
        }
        String name = location.getName() == null ? "" : location.getName().toLowerCase(Locale.ROOT);
        return name.contains("entrecuer");
    }

    /** Igual que el reporte de ventas por color: categoría, nombre o código de kiosko. */
    static boolean isKioskLocation(LocationEntity location) {
        String categoria = normalizeText(location != null ? location.getCategoria() : null);
        String name = normalizeText(location != null ? location.getName() : null);
        String code = normalizeText(location != null ? location.getCode() : null);
        return categoria.contains("KIOS")
                || name.contains("KIOS")
                || code.startsWith("K");
    }

    private static BigDecimal withoutIva(BigDecimal totalAmount) {
        return totalAmount.divide(FelIvaCalculator.IVA_FACTOR, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal units(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
        BigDecimal stripped = scaled.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.negate();
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

    private record AccKey(Long categoryId, String subgroupCode) {
    }

    /** Acumuladores de una consulta: por categoría+subgrupo (reporte) y por producto (top). */
    private static final class Ctx {
        private final Map<AccKey, Acc> byRow = new HashMap<>();
        private final Map<Long, ProdAcc> byProduct = new HashMap<>();
    }

    private static final class ProdAcc {
        private final Long productId;
        private final String code;
        private final String name;
        private final Long categoryId;
        private final String categoryName;
        private final String subgroupCode;
        private final String subgroupLabel;
        private BigDecimal units = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private final Set<Long> kiosks = new HashSet<>();
        private final Map<String, BigDecimal> colors = new HashMap<>();

        private ProdAcc(Long productId, String code, String name, Long categoryId, String categoryName,
                        String subgroupCode, String subgroupLabel) {
            this.productId = productId;
            this.code = code;
            this.name = name;
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.subgroupCode = subgroupCode;
            this.subgroupLabel = subgroupLabel;
        }
    }

    private static final class Acc {
        private final Long categoryId;
        private String categoryName;
        private final String subgroupCode;
        private final String subgroupLabel;
        private BigDecimal units = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;

        private Acc(Long categoryId, String categoryName, String subgroupCode, String subgroupLabel) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.subgroupCode = subgroupCode;
            this.subgroupLabel = subgroupLabel;
        }
    }
}
