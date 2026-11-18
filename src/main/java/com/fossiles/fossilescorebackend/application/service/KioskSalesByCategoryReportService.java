package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Row;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Totals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskSalesByCategoryReportService {

    static final String SIN_CATEGORIA = "Sin categoría";
    static final int MAX_RANGE_DAYS = 366;

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final KioskSalesByCategoryReportRepository reportRepository;

    public KioskSalesByCategoryReportResponse getReport(LocalDate from, LocalDate to, String kioskIds)
            throws BusinessException {
        validateRange(from, to);
        List<Long> requestedIds = parseKioskIds(kioskIds);
        requireReportsAccess();
        List<Long> locationIds = resolveKioskIds(requestedIds);
        return build(from, to, locationIds);
    }

    KioskSalesByCategoryReportResponse build(LocalDate from, LocalDate to, List<Long> kioskIds) {
        Map<AccKey, Acc> byRow = new HashMap<>();
        addSales(byRow, reportRepository.findSaleLines(from, to, kioskIds));
        addReturns(byRow, reportRepository.findReturns(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), kioskIds));

        List<Row> rows = new ArrayList<>();
        BigDecimal totalUnits = BigDecimal.ZERO;
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
            totalUnits = totalUnits.add(acc.units);
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
                        .build())
                .build();
    }

    private void addSales(Map<AccKey, Acc> byRow, List<SaleLine> lines) {
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
                add(byRow, line.categoryId(), line.categoryName(), catalogProduct(line), qty, amount);
            }
        }
    }

    private void addReturns(Map<AccKey, Acc> byRow, List<ReturnRow> rows) {
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
                    addExchangeUnits(byRow, slipRows);
                }
                continue;
            }
            boolean hasItemRows = slipRows.stream().anyMatch(row -> row.returnedItemId() != null);
            if (hasItemRows) {
                addReturnedItemRows(byRow, slipRows, consumed);
            } else {
                addHeaderReturn(byRow, slipRows, consumed);
            }
        }
    }

    /** El cambio mueve unidades. El dinero queda en la venta original; la venta nueva ya neteó el crédito. */
    private void addExchangeUnits(Map<AccKey, Acc> byRow, List<ReturnRow> slipRows) {
        boolean hasItemRows = slipRows.stream().anyMatch(row -> row.returnedItemId() != null);
        if (!hasItemRows) {
            ReturnRow head = slipRows.get(0);
            add(byRow, head.categoryId(), head.categoryName(), catalogProduct(head),
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
            add(byRow, row.categoryId(), row.categoryName(), catalogProduct(row),
                    negate(row.returnedItemQuantity()), BigDecimal.ZERO);
        }
    }

    private void addReturnedItemRows(
            Map<AccKey, Acc> byRow,
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
                add(byRow, head.categoryId(), head.categoryName(), catalogProduct(head),
                        negate(head.returnedItemQuantity()), negate(amount));
            }
        }
    }

    private void addHeaderReturn(
            Map<AccKey, Acc> byRow,
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
            add(byRow, head.categoryId(), head.categoryName(), catalogProduct(head),
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

    private static void add(
            Map<AccKey, Acc> byRow,
            Long categoryId,
            String categoryName,
            ProductEntity product,
            BigDecimal units,
            BigDecimal amount
    ) {
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
        acc.units = acc.units.add(units == null ? BigDecimal.ZERO : units);
        acc.amount = acc.amount.add(amount == null ? BigDecimal.ZERO : amount);
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
