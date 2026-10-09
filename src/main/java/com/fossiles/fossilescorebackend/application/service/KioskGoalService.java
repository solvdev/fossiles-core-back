package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalHistoryItemResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalProgressResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorAggregateDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorKioskAssignmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorOptionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskMonthlyGoalEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSupervisorAssignmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskMonthlyGoalRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSupervisorAssignmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Metas de ventas mensuales por kiosko, comisión de encargadas/supervisoras y
 * asignación de qué kioskos supervisa cada supervisora (para el cálculo agregado).
 * Deliberadamente separado de {@link KioskPosService} (que ya es muy grande) para
 * mantener el cálculo de comisión como una pieza de dominio pequeña y testeable.
 */
@Service
@RequiredArgsConstructor
public class KioskGoalService {

    private static final BigDecimal TIER1_MIN = new BigDecimal("0.70");
    private static final BigDecimal TIER2_MIN = new BigDecimal("0.90");
    private static final BigDecimal TIER3_MIN = new BigDecimal("1.00");
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.02");

    private static final BigDecimal ENCARGADA_BONUS_TIER2 = new BigDecimal("500");
    private static final BigDecimal ENCARGADA_BONUS_TIER3 = new BigDecimal("800");

    private static final BigDecimal SUPERVISORA_BONUS_TIER2 = new BigDecimal("100");
    private static final BigDecimal SUPERVISORA_BONUS_TIER3 = new BigDecimal("200");

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final KioskMonthlyGoalRepository kioskMonthlyGoalRepository;
    private final KioskSupervisorAssignmentRepository kioskSupervisorAssignmentRepository;
    private final KioskGoalAccessGuard accessGuard;

    // ---------------------------------------------------------------------
    // Cálculo puro de comisión (sin acceso a BD, fácil de probar)
    // ---------------------------------------------------------------------

    public CommissionResult computeEncargadaCommission(BigDecimal goalAmount, BigDecimal soldAmount) {
        return computeTieredCommission(goalAmount, soldAmount, ENCARGADA_BONUS_TIER2, ENCARGADA_BONUS_TIER3);
    }

    public CommissionResult computeSupervisoraCommission(BigDecimal aggregateGoalAmount, BigDecimal aggregateSoldAmount) {
        return computeTieredCommission(aggregateGoalAmount, aggregateSoldAmount, SUPERVISORA_BONUS_TIER2, SUPERVISORA_BONUS_TIER3);
    }

    private CommissionResult computeTieredCommission(
            BigDecimal goalAmount,
            BigDecimal soldAmount,
            BigDecimal tier2Bonus,
            BigDecimal tier3Bonus
    ) {
        BigDecimal safeGoal = goalAmount != null ? goalAmount : BigDecimal.ZERO;
        BigDecimal safeSold = soldAmount != null ? soldAmount : BigDecimal.ZERO;

        if (safeGoal.compareTo(BigDecimal.ZERO) <= 0) {
            return CommissionResult.builder()
                    .percentAchieved(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP))
                    .tier("NONE")
                    .commissionAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build();
        }

        BigDecimal ratio = safeSold.divide(safeGoal, 6, RoundingMode.HALF_UP);
        BigDecimal percent = ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);

        String tier;
        BigDecimal bonus;
        if (ratio.compareTo(TIER3_MIN) >= 0) {
            tier = "TIER3";
            bonus = tier3Bonus;
        } else if (ratio.compareTo(TIER2_MIN) >= 0) {
            tier = "TIER2";
            bonus = tier2Bonus;
        } else if (ratio.compareTo(TIER1_MIN) >= 0) {
            tier = "TIER1";
            bonus = BigDecimal.ZERO;
        } else {
            tier = "NONE";
            bonus = null;
        }

        BigDecimal commission = bonus == null
                ? BigDecimal.ZERO
                : bonus.add(safeSold.multiply(COMMISSION_RATE));

        return CommissionResult.builder()
                .percentAchieved(percent)
                .tier(tier)
                .commissionAmount(commission.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    // ---------------------------------------------------------------------
    // Meta y progreso de un kiosko
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public KioskGoalProgressResponse getKioskGoalProgress(Long kioskLocationId, Integer year, Integer month)
            throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        LocationEntity kiosk = resolveKiosk(kioskLocationId);
        accessGuard.assertCanViewKiosk(user, kiosk);

        YearMonth period = resolvePeriod(year, month);
        BigDecimal soldAmount = sumRealSales(List.of(kiosk.getId()), period);
        KioskMonthlyGoalEntity goal = kioskMonthlyGoalRepository
                .findByKioskLocationIdAndGoalYearAndGoalMonth(kiosk.getId(), period.getYear(), period.getMonthValue())
                .orElse(null);

        BigDecimal goalAmount = goal != null ? goal.getGoalAmount() : null;
        CommissionResult result = computeEncargadaCommission(goalAmount, soldAmount);

        return KioskGoalProgressResponse.builder()
                .kioskLocationId(kiosk.getId())
                .kioskName(kiosk.getName())
                .goalYear(period.getYear())
                .goalMonth(period.getMonthValue())
                .goalAmount(goalAmount)
                .soldAmount(soldAmount.setScale(2, RoundingMode.HALF_UP))
                .percentAchieved(result.getPercentAchieved())
                .commissionTier(result.getTier())
                .commissionAmount(result.getCommissionAmount())
                .hasGoalConfigured(goal != null)
                .build();
    }

    @Transactional
    public KioskGoalResponse upsertKioskGoal(Long kioskLocationId, Integer year, Integer month, BigDecimal goalAmount)
            throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        accessGuard.assertCanEditGoalsAndAssignments(user);
        LocationEntity kiosk = resolveKiosk(kioskLocationId);

        KioskMonthlyGoalEntity entity = kioskMonthlyGoalRepository
                .findByKioskLocationIdAndGoalYearAndGoalMonth(kiosk.getId(), year, month)
                .orElse(null);

        if (entity == null) {
            entity = KioskMonthlyGoalEntity.builder()
                    .kioskLocationId(kiosk.getId())
                    .goalYear(year)
                    .goalMonth(month)
                    .goalAmount(goalAmount)
                    .createdByUserId(user.getId())
                    .build();
        } else {
            entity.setGoalAmount(goalAmount);
            entity.setUpdatedByUserId(user.getId());
        }
        entity = kioskMonthlyGoalRepository.save(entity);

        return KioskGoalResponse.builder()
                .kioskLocationId(kiosk.getId())
                .kioskName(kiosk.getName())
                .goalYear(entity.getGoalYear())
                .goalMonth(entity.getGoalMonth())
                .goalAmount(entity.getGoalAmount())
                .build();
    }

    @Transactional(readOnly = true)
    public List<KioskGoalHistoryItemResponse> listKioskGoalHistory(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        LocationEntity kiosk = resolveKiosk(kioskLocationId);
        accessGuard.assertCanViewKiosk(user, kiosk);

        List<KioskMonthlyGoalEntity> goals =
                kioskMonthlyGoalRepository.findByKioskLocationIdOrderByGoalYearDescGoalMonthDesc(kiosk.getId());

        List<KioskGoalHistoryItemResponse> history = new ArrayList<>();
        for (KioskMonthlyGoalEntity goal : goals) {
            YearMonth period = YearMonth.of(goal.getGoalYear(), goal.getGoalMonth());
            BigDecimal soldAmount = sumRealSales(List.of(kiosk.getId()), period);
            CommissionResult result = computeEncargadaCommission(goal.getGoalAmount(), soldAmount);
            history.add(KioskGoalHistoryItemResponse.builder()
                    .goalYear(goal.getGoalYear())
                    .goalMonth(goal.getGoalMonth())
                    .goalAmount(goal.getGoalAmount())
                    .soldAmount(soldAmount.setScale(2, RoundingMode.HALF_UP))
                    .percentAchieved(result.getPercentAchieved())
                    .build());
        }
        return history;
    }

    // ---------------------------------------------------------------------
    // Asignación supervisora <-> kiosko
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Long> getAssignedKioskIds(Long supervisorUserId) {
        return kioskSupervisorAssignmentRepository.findBySupervisorUserId(supervisorUserId).stream()
                .map(KioskSupervisorAssignmentEntity::getKioskLocationId)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SupervisorKioskAssignmentResponse getSupervisorAssignments(Long supervisorUserId) throws BusinessException {
        UserEntity currentUser = getCurrentUserOrThrow();
        Long targetSupervisorId = supervisorUserId != null ? supervisorUserId : currentUser.getId();
        accessGuard.assertCanEditGoalsAndAssignments(currentUser);

        UserEntity supervisor = userRepository.findById(targetSupervisorId)
                .orElseThrow(() -> new BusinessException("No se encontró la supervisora."));

        List<Long> assignedIds = getAssignedKioskIds(targetSupervisorId);
        List<LocationEntity> assignedKiosks = locationRepository.findAllById(assignedIds);

        List<SupervisorKioskAssignmentResponse.KioskOption> kiosks = assignedKiosks.stream()
                .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .map(item -> SupervisorKioskAssignmentResponse.KioskOption.builder()
                        .id(item.getId())
                        .name(item.getName())
                        .code(item.getCode())
                        .build())
                .collect(Collectors.toList());

        return SupervisorKioskAssignmentResponse.builder()
                .supervisorUserId(supervisor.getId())
                .supervisorName(displayName(supervisor))
                .kiosks(kiosks)
                .build();
    }

    @Transactional
    public SupervisorKioskAssignmentResponse replaceSupervisorAssignments(Long supervisorUserId, List<Long> kioskLocationIds)
            throws BusinessException {
        UserEntity currentUser = getCurrentUserOrThrow();
        accessGuard.assertCanEditGoalsAndAssignments(currentUser);

        UserEntity supervisor = userRepository.findById(supervisorUserId)
                .orElseThrow(() -> new BusinessException("No se encontró la supervisora."));

        List<Long> newIds = kioskLocationIds != null ? kioskLocationIds : List.of();
        if (newIds.isEmpty()) {
            kioskSupervisorAssignmentRepository.deleteBySupervisorUserId(supervisorUserId);
        } else {
            kioskSupervisorAssignmentRepository.deleteBySupervisorUserIdAndKioskLocationIdNotIn(supervisorUserId, newIds);
        }

        List<Long> existingIds = getAssignedKioskIds(supervisorUserId);
        for (Long kioskId : newIds) {
            if (!existingIds.contains(kioskId)) {
                kioskSupervisorAssignmentRepository.save(KioskSupervisorAssignmentEntity.builder()
                        .supervisorUserId(supervisorUserId)
                        .kioskLocationId(kioskId)
                        .createdByUserId(currentUser.getId())
                        .build());
            }
        }

        return getSupervisorAssignments(supervisor.getId());
    }

    @Transactional(readOnly = true)
    public List<SupervisorOptionResponse> getEligibleSupervisors() throws BusinessException {
        getCurrentUserOrThrow();
        return userRepository.findAll().stream()
                .filter(user -> isSupervisorRole(user))
                .sorted(Comparator.comparing(this::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> SupervisorOptionResponse.builder()
                        .id(user.getId())
                        .name(displayName(user))
                        .build())
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Panel agregado de la supervisora
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SupervisorAggregateDashboardResponse getSupervisorAggregateDashboard(
            Long supervisorUserId,
            Integer year,
            Integer month
    ) throws BusinessException {
        UserEntity currentUser = getCurrentUserOrThrow();
        Long targetSupervisorId = supervisorUserId != null ? supervisorUserId : currentUser.getId();
        if (!Objects.equals(targetSupervisorId, currentUser.getId())) {
            accessGuard.assertCanEditGoalsAndAssignments(currentUser);
        } else if (!accessGuard.isSupervisor(currentUser) && !accessGuard.isAdmin(currentUser)) {
            throw new BusinessException("Tu usuario no tiene rol de supervisora de kiosko.");
        }

        UserEntity supervisor = userRepository.findById(targetSupervisorId)
                .orElseThrow(() -> new BusinessException("No se encontró la supervisora."));

        YearMonth period = resolvePeriod(year, month);
        List<Long> assignedIds = getAssignedKioskIds(targetSupervisorId);
        List<LocationEntity> assignedKiosks = locationRepository.findAllById(assignedIds).stream()
                .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        List<KioskGoalProgressResponse> kioskRows = new ArrayList<>();
        BigDecimal aggregateGoal = BigDecimal.ZERO;
        BigDecimal aggregateSold = BigDecimal.ZERO;
        boolean anyGoalConfigured = false;

        for (LocationEntity kiosk : assignedKiosks) {
            BigDecimal soldAmount = sumRealSales(List.of(kiosk.getId()), period)
                    .setScale(2, RoundingMode.HALF_UP);
            KioskMonthlyGoalEntity goal = kioskMonthlyGoalRepository
                    .findByKioskLocationIdAndGoalYearAndGoalMonth(kiosk.getId(), period.getYear(), period.getMonthValue())
                    .orElse(null);
            BigDecimal goalAmount = goal != null ? goal.getGoalAmount() : null;

            if (goalAmount != null) {
                aggregateGoal = aggregateGoal.add(goalAmount);
                anyGoalConfigured = true;
            }
            aggregateSold = aggregateSold.add(soldAmount);

            BigDecimal kioskRatioPercent = goalAmount != null && goalAmount.compareTo(BigDecimal.ZERO) > 0
                    ? soldAmount.divide(goalAmount, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

            kioskRows.add(KioskGoalProgressResponse.builder()
                    .kioskLocationId(kiosk.getId())
                    .kioskName(kiosk.getName())
                    .goalYear(period.getYear())
                    .goalMonth(period.getMonthValue())
                    .goalAmount(goalAmount)
                    .soldAmount(soldAmount)
                    .percentAchieved(kioskRatioPercent)
                    .commissionTier(null)
                    .commissionAmount(null)
                    .hasGoalConfigured(goal != null)
                    .build());
        }

        CommissionResult aggregateResult = computeSupervisoraCommission(
                anyGoalConfigured ? aggregateGoal : null,
                aggregateSold
        );

        return SupervisorAggregateDashboardResponse.builder()
                .supervisorUserId(supervisor.getId())
                .supervisorName(displayName(supervisor))
                .goalYear(period.getYear())
                .goalMonth(period.getMonthValue())
                .aggregateGoalAmount(anyGoalConfigured ? aggregateGoal.setScale(2, RoundingMode.HALF_UP) : null)
                .aggregateSoldAmount(aggregateSold.setScale(2, RoundingMode.HALF_UP))
                .percentAchieved(aggregateResult.getPercentAchieved())
                .commissionTier(aggregateResult.getTier())
                .commissionAmount(aggregateResult.getCommissionAmount())
                .kiosks(kioskRows)
                .build();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private BigDecimal sumRealSales(List<Long> kioskLocationIds, YearMonth period) {
        LocalDate startDate = period.atDay(1);
        LocalDate endDate = period.atEndOfMonth();
        List<KioskSaleEntity> sales = kioskSaleRepository
                .findByKioskLocationIdInAndSaleDateBetween(kioskLocationIds, startDate, endDate);
        BigDecimal total = BigDecimal.ZERO;
        for (KioskSaleEntity sale : sales) {
            if (!KioskPosService.countsForProductionMetrics(sale)) {
                continue;
            }
            total = total.add(sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO);
        }
        return total;
    }

    private YearMonth resolvePeriod(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        LocalDate today = GuatemalaDateTime.today();
        return YearMonth.of(today.getYear(), today.getMonthValue());
    }

    private UserEntity getCurrentUserOrThrow() throws BusinessException {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
    }

    private LocationEntity resolveKiosk(Long kioskLocationId) throws BusinessException {
        if (kioskLocationId == null) {
            throw new BusinessException("Debes indicar el kiosko.");
        }
        return locationRepository.findById(kioskLocationId)
                .orElseThrow(() -> new BusinessException("No se encontró el kiosko."));
    }

    private boolean isSupervisorRole(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getName() == null) {
                continue;
            }
            String normalized = role.getName().trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("SUPERVIS") && normalized.contains("KIOSKO")) {
                return true;
            }
        }
        return false;
    }

    private String displayName(UserEntity user) {
        if (user == null) {
            return "";
        }
        String first = safeTrim(user.getFirstName());
        String last = safeTrim(user.getLastName());
        String full = (first + " " + last).trim();
        return !full.isEmpty() ? full : safeTrim(user.getUsername());
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommissionResult {
        private BigDecimal percentAchieved;
        /** NONE | TIER1 | TIER2 | TIER3 */
        private String tier;
        private BigDecimal commissionAmount;
    }
}
