package com.fossiles.fossilescorebackend.application.service.onlineledger;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.CountItem;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LogEntry;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LogPage;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodDetail;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodSummary;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationAccessGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerLogEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerPeriodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineLedgerLogRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineLedgerPeriodRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Bitácora de las revisiones del libro de ventas en línea: qué se hizo en cada periodo, quién y cuándo, y el listado de
 * revisiones con su avance. Solo registra desde que existe; el libro en sí guarda únicamente el valor actual de cada celda.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineLedgerLogService {

    private static final int MAX_LIST = 120;
    private static final int MAX_PAGE = 200;

    private final OnlineLedgerLogRepository logRepository;
    private final OnlineLedgerPeriodRepository periodRepository;
    private final ReconciliationAccessGuard guard;

    @PersistenceContext
    private EntityManager em;

    // ───────── escritura ─────────

    /** Evento de un periodo (abrir, cerrar, reabrir, borrar). Va en la misma transacción del cambio. */
    public void recordPeriodEvent(OnlineLedgerPeriodEntity p, String type, String detail) {
        logRepository.save(base(p == null ? null : p.getId(), p == null ? null : p.getLabel())
                .eventType(type).detail(cut(detail, 500)).build());
    }

    /** Un cambio de celda o un bloqueo manual: nuevo borrador listo para guardar con {@link #saveAll}. */
    public OnlineLedgerLogEntity.OnlineLedgerLogEntityBuilder draft(Long periodId, String periodLabel) {
        return base(periodId, periodLabel);
    }

    /**
     * Guarda cambios de celdas. Nunca debe romper el cambio que ya se hizo en el libro: si la bitácora falla, queda en el
     * log del servidor.
     */
    public void saveAll(Collection<OnlineLedgerLogEntity> entries) {
        if (entries == null || entries.isEmpty()) return;
        try {
            logRepository.saveAll(entries);
        } catch (RuntimeException e) {
            log.error("No se pudo guardar la bitácora del libro de ventas en línea ({} registros): {}", entries.size(), e.getMessage());
        }
    }

    private OnlineLedgerLogEntity.OnlineLedgerLogEntityBuilder base(Long periodId, String periodLabel) {
        return OnlineLedgerLogEntity.builder()
                .periodId(periodId).periodLabel(periodLabel)
                .userId(guard.currentUserId()).userName(guard.currentUsername())
                .createdAt(GuatemalaDateTime.now());
    }

    static String cut(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    // ───────── lectura ─────────

    /** Avance de un rango: [ventas, revisadas, bloqueadas]. */
    @Transactional(readOnly = true)
    public long[] progress(LocalDate from, LocalDate to) {
        Object[] r = em.createQuery("SELECT COUNT(s), COALESCE(SUM(CASE WHEN a.reviewed = true THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN a.locked = true THEN 1 ELSE 0 END), 0) "
                        + "FROM OnlineSaleEntity s LEFT JOIN OnlineSaleAccountingEntity a ON a.onlineSaleId = s.id "
                        + "WHERE s.saleDate >= :f AND s.saleDate <= :t", Object[].class)
                .setParameter("f", from).setParameter("t", to).getSingleResult();
        return new long[]{((Number) r[0]).longValue(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue()};
    }

    /** Todas las revisiones (abiertas primero, luego las más recientes) con su avance y actividad. */
    @Transactional(readOnly = true)
    public List<PeriodSummary> summaries() {
        List<OnlineLedgerPeriodEntity> periods = periodRepository.findAll(
                PageRequest.of(0, MAX_LIST, Sort.by(Sort.Direction.DESC, "dateFrom"))
        ).getContent();
        if (periods.isEmpty()) return List.of();
        List<Long> ids = periods.stream().map(OnlineLedgerPeriodEntity::getId).toList();
        Map<Long, Map<String, long[]>> counts = new HashMap<>();
        Map<Long, LocalDateTime> lastAt = new HashMap<>();
        for (Object[] r : logRepository.countByPeriodAndType(ids)) {
            Long pid = (Long) r[0];
            counts.computeIfAbsent(pid, k -> new HashMap<>()).put((String) r[1], new long[]{((Number) r[2]).longValue()});
            LocalDateTime at = (LocalDateTime) r[3];
            lastAt.merge(pid, at, (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<Long, Long> editors = new HashMap<>();
        for (Object[] r : logRepository.editorsByPeriod(ids)) {
            editors.put((Long) r[0], ((Number) r[1]).longValue());
        }
        List<PeriodSummary> out = new ArrayList<>();
        for (OnlineLedgerPeriodEntity p : periods) {
            out.add(summaryOf(p, counts.getOrDefault(p.getId(), Map.of()), editors.getOrDefault(p.getId(), 0L),
                    lastAt.get(p.getId())));
        }
        // Abiertas primero; dentro de cada grupo, la más reciente primero.
        out.sort(Comparator.comparing((PeriodSummary s) -> s.getPeriod().getStatus().equals("OPEN") ? 0 : 1)
                .thenComparing((PeriodSummary s) -> s.getPeriod().getFrom(), Comparator.reverseOrder()));
        return out;
    }

    private PeriodSummary summaryOf(OnlineLedgerPeriodEntity p, Map<String, long[]> counts, long editors,
                                    LocalDateTime lastActivity) {
        long[] pr = progress(p.getDateFrom(), p.getDateTo());
        LocalDateTime last = lastActivity;
        for (LocalDateTime d : new LocalDateTime[]{p.getOpenedAt(), p.getClosedAt(), p.getReopenedAt()}) {
            if (d != null && (last == null || d.isAfter(last))) last = d;
        }
        return PeriodSummary.builder()
                .period(OnlineLedgerPeriodService.toRow(p, null))
                .sales(pr[0]).reviewed(pr[1]).locked(pr[2])
                .edits(counts.getOrDefault(OnlineLedgerLogEntity.EDIT, new long[]{0})[0])
                .editors(editors)
                .reopens(counts.getOrDefault(OnlineLedgerLogEntity.REOPEN, new long[]{0})[0])
                .lastActivityAt(last)
                .build();
    }

    @Transactional(readOnly = true)
    public PeriodDetail detail(Long id) throws ResourceNotFoundException {
        OnlineLedgerPeriodEntity p = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Periodo de revisión", id));
        Map<String, long[]> counts = new HashMap<>();
        LocalDateTime lastAt = null;
        for (Object[] r : logRepository.countByPeriodAndType(List.of(id))) {
            counts.put((String) r[1], new long[]{((Number) r[2]).longValue()});
            LocalDateTime at = (LocalDateTime) r[3];
            if (lastAt == null || at.isAfter(lastAt)) lastAt = at;
        }
        long editors = logRepository.editorsByPeriod(List.of(id)).stream()
                .mapToLong(r -> ((Number) r[1]).longValue()).findFirst().orElse(0L);
        List<CountItem> byUser = new ArrayList<>();
        for (Object[] r : logRepository.editsByUser(id)) {
            byUser.add(CountItem.builder().name(r[0] == null ? "—" : (String) r[0]).count(((Number) r[1]).longValue())
                    .sales(((Number) r[2]).longValue()).lastAt((LocalDateTime) r[3]).build());
        }
        List<CountItem> byField = new ArrayList<>();
        for (Object[] r : logRepository.editsByField(id)) {
            byField.add(CountItem.builder().name(r[0] == null ? "—" : (String) r[0]).count(((Number) r[1]).longValue()).build());
        }
        return PeriodDetail.builder()
                .summary(summaryOf(p, counts, editors, lastAt))
                .editedSales(logRepository.editedSales(id))
                .byUser(byUser).byField(byField)
                .build();
    }

    /**
     * Bitácora de un periodo, la más reciente primero. {@code kind}: edits (cambios de celdas), locks (bloqueos a mano),
     * period (abrir/cerrar/reabrir) o vacío para todo. {@code q} busca por venta, persona o campo.
     */
    @Transactional(readOnly = true)
    public LogPage log(Long periodId, String kind, String q, int page, int size) throws ResourceNotFoundException {
        if (!periodRepository.existsById(periodId)) {
            throw new ResourceNotFoundException("Periodo de revisión", periodId);
        }
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE));
        int safePage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE l.periodId = :pid");
        Map<String, Object> params = new HashMap<>();
        params.put("pid", periodId);
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "edits" -> where.append(" AND l.eventType = 'EDIT'");
            case "locks" -> where.append(" AND l.eventType IN ('LOCK', 'UNLOCK')");
            case "period" -> where.append(" AND l.eventType IN ('OPEN', 'CLOSE', 'REOPEN', 'DELETE')");
            default -> { }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(COALESCE(l.saleNumber, '')) LIKE :q OR LOWER(COALESCE(l.userName, '')) LIKE :q"
                    + " OR LOWER(COALESCE(l.fieldLabel, '')) LIKE :q OR LOWER(COALESCE(l.newValue, '')) LIKE :q"
                    + " OR LOWER(COALESCE(l.oldValue, '')) LIKE :q)");
            params.put("q", "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%");
        }
        TypedQuery<Long> count = em.createQuery("SELECT COUNT(l) FROM OnlineLedgerLogEntity l" + where, Long.class);
        TypedQuery<OnlineLedgerLogEntity> rows = em.createQuery(
                "SELECT l FROM OnlineLedgerLogEntity l" + where + " ORDER BY l.createdAt DESC, l.id DESC",
                OnlineLedgerLogEntity.class);
        params.forEach((name, value) -> {
            count.setParameter(name, value);
            rows.setParameter(name, value);
        });
        rows.setFirstResult(safePage * safeSize).setMaxResults(safeSize);
        List<LogEntry> items = rows.getResultList().stream().map(OnlineLedgerLogService::toEntry).toList();
        return LogPage.builder().items(items).total(count.getSingleResult()).page(safePage).size(safeSize).build();
    }

    private static LogEntry toEntry(OnlineLedgerLogEntity l) {
        return LogEntry.builder()
                .id(l.getId()).at(l.getCreatedAt()).userName(l.getUserName()).type(l.getEventType())
                .saleId(l.getSaleId()).saleNumber(l.getSaleNumber()).fieldLabel(l.getFieldLabel())
                .oldValue(l.getOldValue()).newValue(l.getNewValue()).detail(l.getDetail())
                .build();
    }
}
