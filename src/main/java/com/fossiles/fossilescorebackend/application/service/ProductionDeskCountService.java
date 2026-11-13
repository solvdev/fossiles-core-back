package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.DeskCountDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SpecialDeskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionSpecialDeskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionDeskCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionSpecialDeskRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductionDeskCountService {

    private static final int DEFAULT_DESKS = 12;
    private static final int MAX_DESKS_LIMIT = 32;
    private static final List<String> FALLBACK_CONFIG_KEYS = List.of(
            "MANUFACTURING_NUMBER_OF_TABLES",
            "PRODUCTION_TABLES_COUNT"
    );

    private final ProductionDeskCountRepository repository;
    private final SystemConfigRepository systemConfigRepository;
    private final TaskRepository taskRepository;
    private final ProductionSpecialDeskRepository specialDeskRepository;

    /**
     * Mesas de planta del día (1..numDesks) y, aparte, las mesas especiales activas (maquila).
     * {@code numDesks} sigue siendo solo planta: es lo que usan el cupo y el reparto automático.
     */
    public DeskCountDayResponse getDay(LocalDate asOfDate) throws BusinessException {
        if (asOfDate == null) {
            throw new BusinessException("La fecha efectiva es obligatoria.");
        }
        List<SpecialDeskResponse> special = activeSpecialDesks();

        Optional<ProductionDeskCountEntity> row = repository.findTopByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(asOfDate);
        if (row.isPresent()) {
            return DeskCountDayResponse.builder()
                    .effectiveDate(row.get().getEffectiveDate())
                    .numDesks(row.get().getNumDesks())
                    .resolvedKey("PRODUCTION_OVERRIDE")
                    .isDefault(false)
                    .specialDesks(special)
                    .build();
        }

        DesksCountResolution fallback = resolveFallbackNumDesks();
        return DeskCountDayResponse.builder()
                .effectiveDate(asOfDate)
                .numDesks(fallback.count())
                .resolvedKey(fallback.resolvedKey())
                .isDefault(fallback.isDefault())
                .specialDesks(special)
                .build();
    }

    /** Mesas especiales activas (maquila), en orden de número. */
    public List<SpecialDeskResponse> activeSpecialDesks() {
        List<SpecialDeskResponse> out = new ArrayList<>();
        for (ProductionSpecialDeskEntity e : specialDeskRepository.findByActiveTrueOrderByDeskAsc()) {
            out.add(ProductionSpecialDeskService.toResponse(e));
        }
        return out;
    }

    /** Números de las mesas especiales activas: el reparto automático no toca sus tareas. */
    public Set<Integer> activeSpecialDeskNumbers() {
        Set<Integer> out = new LinkedHashSet<>();
        for (ProductionSpecialDeskEntity e : specialDeskRepository.findByActiveTrueOrderByDeskAsc()) {
            if (e.getDesk() != null) {
                out.add(e.getDesk());
            }
        }
        return out;
    }

    @Transactional
    public DeskCountDayResponse replaceDay(LocalDate effectiveDate, Integer numDesks) throws BusinessException {
        if (effectiveDate == null) {
            throw new BusinessException("La fecha efectiva es obligatoria.");
        }
        if (numDesks == null) {
            throw new BusinessException("La cantidad de mesas es obligatoria.");
        }
        int n = numDesks;
        if (n < 1 || n > MAX_DESKS_LIMIT) {
            throw new BusinessException("Cantidad de mesas fuera de rango: " + n + " (1.." + MAX_DESKS_LIMIT + ").");
        }
        Set<Integer> special = activeSpecialDeskNumbers();
        for (Integer desk : special) {
            if (desk <= n) {
                throw new BusinessException("La mesa " + desk + " es de maquila: las mesas de planta pueden ser hasta "
                        + (desk - 1) + ". Si necesita más mesas de planta, cambie antes el número de la mesa de maquila.");
            }
        }

        int previous = getDay(effectiveDate).getNumDesks();

        repository.deleteByEffectiveDate(effectiveDate);
        repository.save(ProductionDeskCountEntity.builder()
                .effectiveDate(effectiveDate)
                .numDesks(n)
                .build());

        if (n < previous) {
            // La maquila queda fuera del rango de planta a propósito: sus tareas se conservan.
            Set<Integer> keep = special.isEmpty() ? Set.of(-1) : special;
            taskRepository.clearDeskOutOfRangeFromDate(effectiveDate, n, keep);
        }

        return getDay(effectiveDate);
    }

    private record DesksCountResolution(int count, String resolvedKey, boolean isDefault) {}

    private DesksCountResolution resolveFallbackNumDesks() {
        for (String key : FALLBACK_CONFIG_KEYS) {
            Optional<SystemConfigEntity> config = systemConfigRepository.findByConfigKey(key);
            if (config.isEmpty() || config.get().getConfigValue() == null) {
                continue;
            }
            try {
                int value = Integer.parseInt(config.get().getConfigValue().trim());
                if (value > 0) {
                    return new DesksCountResolution(Math.min(value, MAX_DESKS_LIMIT), key, false);
                }
            } catch (NumberFormatException ignored) {
                // next key
            }
        }
        return new DesksCountResolution(DEFAULT_DESKS, "DEFAULT", true);
    }
}
