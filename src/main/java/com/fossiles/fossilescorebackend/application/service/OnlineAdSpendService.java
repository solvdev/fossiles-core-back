package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse.Day;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse.Totals;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineAdSpendEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineAdSpendRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Inversión diaria en publicidad (un solo monto por día) y su comparación contra la venta online del día.
 * La venta válida sale de {@link SalesSourceLoader#loadOnlineSales}, la misma que usa el dashboard online.
 * El monto se guarda con 2 decimales: más decimales se rechazan (no se redondea en silencio); el redondeo
 * HALF_UP aplica solo a los cálculos del reporte (ROAS).
 */
@Service
@RequiredArgsConstructor
public class OnlineAdSpendService {

    static final int MAX_BULK_ENTRIES = 400;
    static final int MAX_REPORT_DAYS = 400;
    static final int MAX_NOTES_LENGTH = 255;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999.99");

    static final String STATUS_WIN = "WIN";
    static final String STATUS_LOSS = "LOSS";
    static final String STATUS_EVEN = "EVEN";
    static final String STATUS_NO_SPEND = "NO_SPEND";

    private static final DateTimeFormatter MESSAGE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OnlineAdSpendRepository repository;
    private final SalesSourceLoader sourceLoader;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public List<OnlineAdSpendEntryResponse> list(LocalDate startDate, LocalDate endDate) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        return toResponses(repository.findBySpendDateBetweenOrderBySpendDateAsc(range.from(), range.to()));
    }

    /** Crea o actualiza la captura de un día. */
    @Transactional(rollbackFor = BusinessException.class)
    public OnlineAdSpendEntryResponse upsert(LocalDate date, BigDecimal amount, String notes) throws BusinessException {
        if (date == null) {
            throw new BusinessException("La fecha de la inversión es obligatoria.");
        }
        requireNotFuture(date, today());
        BigDecimal normalizedAmount = normalizeAmount(amount);
        String normalizedNotes = normalizeNotes(notes);

        OnlineAdSpendEntity entity = applyCapture(
                repository.findBySpendDate(date).orElse(null),
                date, normalizedAmount, normalizedNotes, securityUtil.getCurrentUserId());
        // saveAndFlush: el @PreUpdate de la entidad fija updatedAt antes de armar la respuesta.
        return toResponses(List.of(repository.saveAndFlush(entity))).get(0);
    }

    /** Borra la captura de un día; si no existe no hace nada. */
    @Transactional(rollbackFor = BusinessException.class)
    public void delete(LocalDate date) throws BusinessException {
        if (date == null) {
            throw new BusinessException("La fecha de la inversión es obligatoria.");
        }
        repository.deleteBySpendDate(date);
    }

    /**
     * Guardado masivo todo-o-nada: se valida todo el lote antes de escribir y una sola transacción
     * cubre las escrituras. {@code amount = null} borra la captura de ese día.
     */
    @Transactional(rollbackFor = BusinessException.class)
    public OnlineAdSpendBulkResponse bulk(List<OnlineAdSpendBulkRequest.Entry> entries) throws BusinessException {
        List<BulkItem> items = validateBulk(entries);

        Set<LocalDate> dates = items.stream().map(BulkItem::date).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<LocalDate, OnlineAdSpendEntity> existingByDate = new HashMap<>();
        for (OnlineAdSpendEntity existing : repository.findBySpendDateIn(dates)) {
            existingByDate.put(existing.getSpendDate(), existing);
        }

        Long userId = securityUtil.getCurrentUserId();
        List<OnlineAdSpendEntity> toSave = new ArrayList<>();
        List<OnlineAdSpendEntity> toDelete = new ArrayList<>();
        for (BulkItem item : items) {
            OnlineAdSpendEntity existing = existingByDate.get(item.date());
            if (item.amount() == null) {
                if (existing != null) {
                    toDelete.add(existing);
                }
                continue;
            }
            toSave.add(applyCapture(existing, item.date(), item.amount(), item.notes(), userId));
        }
        if (!toDelete.isEmpty()) {
            repository.deleteAll(toDelete);
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
        return OnlineAdSpendBulkResponse.builder().saved(toSave.size()).deleted(toDelete.size()).build();
    }

    /** Un elemento por día del rango (días sin venta ni inversión incluidos), de la fecha menor a la mayor. */
    @Transactional(readOnly = true)
    public OnlineAdSpendReportResponse report(LocalDate startDate, LocalDate endDate) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        long totalDays = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        if (totalDays > MAX_REPORT_DAYS) {
            throw new BusinessException("El rango no puede exceder " + MAX_REPORT_DAYS + " días.");
        }

        Map<LocalDate, BigDecimal> salesByDay = new HashMap<>();
        Map<LocalDate, Integer> ordersByDay = new HashMap<>();
        for (OnlineSaleEntity sale : sourceLoader.loadOnlineSales(range.from(), range.to())) {
            if (!range.contains(sale.getSaleDate())) {
                continue;
            }
            salesByDay.merge(sale.getSaleDate(), nz(sale.getTotalAmount()), BigDecimal::add);
            ordersByDay.merge(sale.getSaleDate(), 1, Integer::sum);
        }
        Map<LocalDate, OnlineAdSpendEntity> spendByDay = new HashMap<>();
        for (OnlineAdSpendEntity spend : repository.findBySpendDateBetweenOrderBySpendDateAsc(range.from(), range.to())) {
            spendByDay.put(spend.getSpendDate(), spend);
        }

        List<Day> days = new ArrayList<>();
        BigDecimal salesTotal = money(null);
        BigDecimal comparableSales = money(null);
        BigDecimal spendTotal = money(null);
        int ordersTotal = 0;
        int daysWithSpend = 0;
        int daysWin = 0;
        int daysLoss = 0;
        int daysEven = 0;

        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            BigDecimal sales = money(salesByDay.get(day));
            int orders = ordersByDay.getOrDefault(day, 0);
            salesTotal = salesTotal.add(sales);
            ordersTotal += orders;

            OnlineAdSpendEntity spend = spendByDay.get(day);
            if (spend == null) {
                days.add(Day.builder().date(day).salesAmount(sales).ordersCount(orders).status(STATUS_NO_SPEND).build());
                continue;
            }
            BigDecimal adSpend = money(spend.getAmount());
            BigDecimal net = sales.subtract(adSpend);
            String status;
            if (net.signum() > 0) {
                status = STATUS_WIN;
                daysWin++;
            } else if (net.signum() < 0) {
                status = STATUS_LOSS;
                daysLoss++;
            } else {
                status = STATUS_EVEN;
                daysEven++;
            }
            daysWithSpend++;
            comparableSales = comparableSales.add(sales);
            spendTotal = spendTotal.add(adSpend);
            days.add(Day.builder()
                    .date(day)
                    .salesAmount(sales)
                    .ordersCount(orders)
                    .adSpend(adSpend)
                    .netResult(net)
                    .roas(roas(sales, adSpend))
                    .status(status)
                    .notes(spend.getNotes())
                    .build());
        }

        Totals totals = Totals.builder()
                .salesAmount(salesTotal)
                .ordersCount(ordersTotal)
                .comparableSales(comparableSales)
                .adSpend(spendTotal)
                .netResult(comparableSales.subtract(spendTotal))
                .roas(roas(comparableSales, spendTotal))
                .daysWithSpend(daysWithSpend)
                .daysNoSpend((int) totalDays - daysWithSpend)
                .daysWin(daysWin)
                .daysLoss(daysLoss)
                .daysEven(daysEven)
                .build();
        return OnlineAdSpendReportResponse.builder()
                .startDate(range.from())
                .endDate(range.to())
                .totals(totals)
                .days(days)
                .build();
    }

    // ─── Validación ─────────────────────────────────────────────────

    private record BulkItem(LocalDate date, BigDecimal amount, String notes) {
    }

    private List<BulkItem> validateBulk(List<OnlineAdSpendBulkRequest.Entry> entries) throws BusinessException {
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("Debe enviar al menos una entrada.");
        }
        if (entries.size() > MAX_BULK_ENTRIES) {
            throw new BusinessException("Máximo " + MAX_BULK_ENTRIES + " entradas por solicitud.");
        }
        LocalDate today = today();
        Set<LocalDate> seen = new HashSet<>();
        List<BulkItem> items = new ArrayList<>();
        for (OnlineAdSpendBulkRequest.Entry entry : entries) {
            if (entry == null || entry.getDate() == null) {
                throw new BusinessException("Cada entrada debe tener fecha.");
            }
            LocalDate date = entry.getDate();
            if (!seen.add(date)) {
                throw new BusinessException("La fecha " + MESSAGE_DATE.format(date) + " está repetida en la solicitud.");
            }
            requireNotFuture(date, today);
            if (entry.getAmount() == null) {
                items.add(new BulkItem(date, null, null));
                continue;
            }
            try {
                items.add(new BulkItem(date, normalizeAmount(entry.getAmount()), normalizeNotes(entry.getNotes())));
            } catch (BusinessException ex) {
                throw new BusinessException(MESSAGE_DATE.format(date) + ": " + ex.getMessage(), ex);
            }
        }
        return items;
    }

    private static void requireNotFuture(LocalDate date, LocalDate today) throws BusinessException {
        if (date.isAfter(today)) {
            throw new BusinessException("La fecha " + MESSAGE_DATE.format(date)
                    + " es posterior a hoy; solo se puede registrar inversión hasta el día de hoy.");
        }
    }

    /** Monto >= 0, máximo 9,999,999.99 y a lo sumo 2 decimales (ceros finales como 10.500 sí se aceptan). */
    static BigDecimal normalizeAmount(BigDecimal amount) throws BusinessException {
        if (amount == null) {
            throw new BusinessException("El monto de la inversión es obligatorio.");
        }
        if (amount.signum() < 0) {
            throw new BusinessException("El monto de la inversión no puede ser negativo.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException("El monto de la inversión no puede exceder Q9,999,999.99.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException("El monto de la inversión admite máximo 2 decimales.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Recorta espacios; vacío se guarda como null. */
    static String normalizeNotes(String notes) throws BusinessException {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String trimmed = notes.trim();
        if (trimmed.length() > MAX_NOTES_LENGTH) {
            throw new BusinessException("Las notas no pueden exceder " + MAX_NOTES_LENGTH + " caracteres.");
        }
        return trimmed;
    }

    // ─── Mapeo ──────────────────────────────────────────────────────

    private static OnlineAdSpendEntity applyCapture(
            OnlineAdSpendEntity existing, LocalDate date, BigDecimal amount, String notes, Long userId) {
        OnlineAdSpendEntity entity = existing != null
                ? existing
                : OnlineAdSpendEntity.builder().spendDate(date).createdBy(userId).updatedBy(userId).build();
        entity.setAmount(amount);
        entity.setNotes(notes);
        if (userId != null) {
            entity.setUpdatedBy(userId);
        }
        return entity;
    }

    private static BigDecimal roas(BigDecimal sales, BigDecimal adSpend) {
        if (adSpend == null || adSpend.signum() <= 0) {
            return null;
        }
        return sales.divide(adSpend, 2, RoundingMode.HALF_UP);
    }

    private List<OnlineAdSpendEntryResponse> toResponses(List<OnlineAdSpendEntity> entities) {
        Set<Long> userIds = entities.stream()
                .map(OnlineAdSpendEntity::getUpdatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> namesById = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserEntity user : userRepository.findAllById(userIds)) {
                namesById.put(user.getId(), displayName(user));
            }
        }
        return entities.stream()
                .map(e -> OnlineAdSpendEntryResponse.builder()
                        .date(e.getSpendDate())
                        .amount(money(e.getAmount()))
                        .notes(e.getNotes())
                        .updatedAt(e.getUpdatedAt())
                        .updatedBy(e.getUpdatedBy() != null ? namesById.get(e.getUpdatedBy()) : null)
                        .build())
                .toList();
    }

    private static String displayName(UserEntity user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return !full.isEmpty() ? full : user.getUsername();
    }
}
