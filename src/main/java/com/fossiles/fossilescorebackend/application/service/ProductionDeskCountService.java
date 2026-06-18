package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.DeskCountDayResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionDeskCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    public DeskCountDayResponse getDay(LocalDate asOfDate) throws BusinessException {
        if (asOfDate == null) {
            throw new BusinessException("La fecha efectiva es obligatoria.");
        }

        Optional<ProductionDeskCountEntity> row = repository.findTopByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(asOfDate);
        if (row.isPresent()) {
            return DeskCountDayResponse.builder()
                    .effectiveDate(row.get().getEffectiveDate())
                    .numDesks(row.get().getNumDesks())
                    .resolvedKey("PRODUCTION_OVERRIDE")
                    .isDefault(false)
                    .build();
        }

        DesksCountResolution fallback = resolveFallbackNumDesks();
        return DeskCountDayResponse.builder()
                .effectiveDate(asOfDate)
                .numDesks(fallback.count())
                .resolvedKey(fallback.resolvedKey())
                .isDefault(fallback.isDefault())
                .build();
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

        int previous = getDay(effectiveDate).getNumDesks();

        repository.deleteByEffectiveDate(effectiveDate);
        repository.save(ProductionDeskCountEntity.builder()
                .effectiveDate(effectiveDate)
                .numDesks(n)
                .build());

        if (n < previous) {
            taskRepository.clearDeskOutOfRangeFromDate(effectiveDate, n);
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

