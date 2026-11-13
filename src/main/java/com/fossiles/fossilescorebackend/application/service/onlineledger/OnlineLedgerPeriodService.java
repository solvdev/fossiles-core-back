package com.fossiles.fossilescorebackend.application.service.onlineledger;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationAccessGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerPeriodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleAccountingEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineLedgerPeriodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleAccountingRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Periodos de revisión del libro de ventas en línea (apertura y cierre por rango o mes).
 *
 * <p>Abrir es obligatorio para editar: el libro solo admite cambios en ventas de un periodo abierto. Cerrar bloquea todas
 * las ventas con fecha de venta dentro del rango (crea su fila contable si no la tenía) y, mientras siga cerrado, el
 * libro rechaza cambios también en ventas que se registren después con fecha dentro del periodo. Reabrir es solo de
 * administradores y desbloquea únicamente lo que bloqueó el cierre.
 */
@Service
@RequiredArgsConstructor
public class OnlineLedgerPeriodService {

    private static final int BATCH = 500;
    private static final long MAX_DAYS = 366;
    private static final String[] MONTHS = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto",
            "Septiembre", "Octubre", "Noviembre", "Diciembre"};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OnlineLedgerPeriodRepository periodRepository;
    private final OnlineSaleAccountingRepository accountingRepository;
    private final ReconciliationAccessGuard guard;

    @PersistenceContext
    private EntityManager em;

    // ───────── permisos ─────────

    public boolean canManage() {
        return guard.hasAny(ReconciliationAccessGuard.PERM_RECONCILE);
    }

    public boolean canReopen() {
        return guard.isAdmin();
    }

    private void assertCanManage() throws BusinessException {
        if (!canManage()) {
            throw new BusinessException("Solo Administración y Contabilidad pueden abrir o cerrar periodos de revisión.");
        }
    }

    // ───────── lectura ─────────

    @Transactional(readOnly = true)
    public List<PeriodRow> list() {
        return periodRepository.findTop60ByOrderByDateFromDesc().stream().map(p -> toRow(p, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<PeriodRow> overlapping(LocalDate from, LocalDate to) {
        if (from == null || to == null) return List.of();
        return periodRepository.findOverlapping(from, to).stream().map(p -> toRow(p, null)).toList();
    }

    /** Todos los periodos (abiertos y cerrados) que se cruzan con el rango. */
    public List<OnlineLedgerPeriodEntity> between(LocalDate from, LocalDate to) {
        if (from == null || to == null) return List.of();
        return periodRepository.findOverlapping(from, to);
    }

    /** Periodos cerrados que se cruzan con el rango (para marcar filas y rechazar cambios). */
    public List<OnlineLedgerPeriodEntity> closedBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) return List.of();
        return periodRepository.findOverlapping(from, to).stream().filter(OnlineLedgerPeriodEntity::isClosed).toList();
    }

    public Optional<OnlineLedgerPeriodEntity> closedFor(LocalDate date) {
        return closedBetween(date, date).stream().findFirst();
    }

    // ───────── abrir / cerrar / reabrir ─────────

    @Transactional(rollbackFor = Exception.class)
    public PeriodRow open(PeriodRequest request) throws BusinessException {
        assertCanManage();
        if (request == null || request.getFrom() == null || request.getTo() == null) {
            throw new BusinessException("Indique desde y hasta qué fecha va el periodo.");
        }
        LocalDate from = request.getFrom();
        LocalDate to = request.getTo();
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException("Un periodo de revisión puede durar como máximo un año.");
        }
        List<OnlineLedgerPeriodEntity> clash = periodRepository.findOverlapping(from, to);
        if (!clash.isEmpty()) {
            OnlineLedgerPeriodEntity c = clash.get(0);
            throw new BusinessException("Ese rango se cruza con el periodo «" + c.getLabel() + "» ("
                    + (c.isClosed() ? "cerrado" : "abierto") + "). Use ese periodo o elija fechas que no se crucen.");
        }
        String note = request.getNote() == null ? null : request.getNote().trim();
        if (note != null && note.length() > 500) {
            throw new BusinessException("La nota puede tener como máximo 500 caracteres.");
        }
        OnlineLedgerPeriodEntity p = OnlineLedgerPeriodEntity.builder()
                .dateFrom(from).dateTo(to).label(label(from, to))
                .status(OnlineLedgerPeriodEntity.OPEN)
                .note(note == null || note.isEmpty() ? null : note)
                .openedAt(GuatemalaDateTime.now())
                .openedBy(guard.currentUserId())
                .openedByName(guard.currentUsername())
                .build();
        return toRow(periodRepository.saveAndFlush(p), null);
    }

    /** Cierra el periodo: bloquea todas sus ventas. Lo que ya estaba bloqueado a mano se queda como estaba. */
    @Transactional(rollbackFor = Exception.class)
    public PeriodRow close(Long id) throws BusinessException, ResourceNotFoundException {
        assertCanManage();
        OnlineLedgerPeriodEntity p = find(id);
        if (p.isClosed()) {
            throw new BusinessException("El periodo «" + p.getLabel() + "» ya está cerrado.");
        }
        List<Long> saleIds = em.createQuery(
                        "SELECT s.id FROM OnlineSaleEntity s WHERE s.saleDate >= :f AND s.saleDate <= :t", Long.class)
                .setParameter("f", p.getDateFrom())
                .setParameter("t", p.getDateTo())
                .getResultList();
        Long userId = guard.currentUserId();
        String username = guard.currentUsername();
        LocalDateTime now = GuatemalaDateTime.now();
        int locked = 0;
        for (int i = 0; i < saleIds.size(); i += BATCH) {
            List<Long> batch = saleIds.subList(i, Math.min(saleIds.size(), i + BATCH));
            Map<Long, OnlineSaleAccountingEntity> existing = new HashMap<>();
            for (OnlineSaleAccountingEntity a : accountingRepository.findByOnlineSaleIdIn(batch)) {
                existing.put(a.getOnlineSaleId(), a);
            }
            List<OnlineSaleAccountingEntity> toSave = new ArrayList<>();
            for (Long saleId : batch) {
                OnlineSaleAccountingEntity a = existing.get(saleId);
                if (a == null) {
                    a = OnlineSaleAccountingEntity.builder().onlineSaleId(saleId).reviewed(false).locked(false).build();
                }
                if (Boolean.TRUE.equals(a.getLocked())) {
                    continue;
                }
                a.setLocked(true);
                a.setLockedAt(now);
                a.setLockedBy(userId);
                a.setLockedByName(username);
                a.setLockedPeriodId(p.getId());
                a.setUpdatedBy(userId);
                toSave.add(a);
            }
            accountingRepository.saveAll(toSave);
            locked += toSave.size();
        }
        accountingRepository.flush();
        p.setStatus(OnlineLedgerPeriodEntity.CLOSED);
        p.setClosedAt(now);
        p.setClosedBy(userId);
        p.setClosedByName(username);
        return toRow(periodRepository.saveAndFlush(p), locked);
    }

    /** Reabre un periodo cerrado (solo administradores): desbloquea lo que bloqueó su cierre. */
    @Transactional(rollbackFor = Exception.class)
    public PeriodRow reopen(Long id) throws BusinessException, ResourceNotFoundException {
        if (!canReopen()) {
            throw new BusinessException("Solo un administrador puede reabrir un periodo cerrado.");
        }
        OnlineLedgerPeriodEntity p = find(id);
        if (!p.isClosed()) {
            throw new BusinessException("El periodo «" + p.getLabel() + "» ya está abierto.");
        }
        Long userId = guard.currentUserId();
        List<OnlineSaleAccountingEntity> rows = accountingRepository.findByLockedPeriodId(p.getId());
        for (OnlineSaleAccountingEntity a : rows) {
            a.setLocked(false);
            a.setLockedAt(null);
            a.setLockedBy(null);
            a.setLockedByName(null);
            a.setLockedPeriodId(null);
            a.setUpdatedBy(userId);
        }
        accountingRepository.saveAllAndFlush(rows);
        p.setStatus(OnlineLedgerPeriodEntity.OPEN);
        p.setReopenedAt(GuatemalaDateTime.now());
        p.setReopenedByName(guard.currentUsername());
        p.setClosedAt(null);
        p.setClosedBy(null);
        p.setClosedByName(null);
        return toRow(periodRepository.saveAndFlush(p), null);
    }

    /** Borra un periodo abierto por error (uno cerrado hay que reabrirlo primero). */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) throws BusinessException, ResourceNotFoundException {
        assertCanManage();
        OnlineLedgerPeriodEntity p = find(id);
        if (p.isClosed()) {
            throw new BusinessException("Un periodo cerrado no se puede borrar. Un administrador puede reabrirlo.");
        }
        periodRepository.delete(p);
    }

    // ───────── auxiliares ─────────

    private OnlineLedgerPeriodEntity find(Long id) throws ResourceNotFoundException {
        return periodRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Periodo de revisión", id));
    }

    /** «Noviembre 2026» si es el mes completo; si no, «01/11/2026 al 15/11/2026». */
    static String label(LocalDate from, LocalDate to) {
        YearMonth ym = YearMonth.from(from);
        if (from.getDayOfMonth() == 1 && to.equals(ym.atEndOfMonth())) {
            return MONTHS[from.getMonthValue() - 1] + " " + from.getYear();
        }
        return from.equals(to) ? from.format(DAY) : from.format(DAY) + " al " + to.format(DAY);
    }

    static PeriodRow toRow(OnlineLedgerPeriodEntity p, Integer lockedCount) {
        return PeriodRow.builder()
                .id(p.getId()).from(p.getDateFrom()).to(p.getDateTo()).label(p.getLabel()).status(p.getStatus())
                .note(p.getNote())
                .openedAt(p.getOpenedAt()).openedByName(p.getOpenedByName())
                .closedAt(p.getClosedAt()).closedByName(p.getClosedByName())
                .reopenedAt(p.getReopenedAt()).reopenedByName(p.getReopenedByName())
                .lockedCount(lockedCount)
                .build();
    }
}
