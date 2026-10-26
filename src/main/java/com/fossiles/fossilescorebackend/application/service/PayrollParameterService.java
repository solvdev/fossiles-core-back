package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollParameterRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollParameterResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Un cambio legal se registra como nueva versión con fecha de vigencia; las planillas ya calculadas no cambian. */
@Service
@RequiredArgsConstructor
public class PayrollParameterService {

    private final PayrollParameterRepository repository;
    private final PayrollAccessGuard accessGuard;

    @Transactional(readOnly = true)
    public PayrollParameters effectiveAt(LocalDate date) throws BusinessException {
        try {
            return PayrollParameters.from(valuesAt(date));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<PayrollParameterResponse> list() {
        accessGuard.assertCanViewRuns();
        Set<Long> currentIds = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (PayrollParameterEntity row : repository.findByEffectiveFromLessThanEqualOrderByCodeAscEffectiveFromDesc(GuatemalaDateTime.today())) {
            if (seen.add(row.getCode())) {
                currentIds.add(row.getId());
            }
        }
        return repository.findAll().stream()
                .sorted(Comparator.comparing(PayrollParameterEntity::getCode)
                        .thenComparing(PayrollParameterEntity::getEffectiveFrom, Comparator.reverseOrder()))
                .map(row -> PayrollParameterResponse.builder()
                        .id(row.getId())
                        .code(row.getCode())
                        .value(row.getValue())
                        .effectiveFrom(row.getEffectiveFrom())
                        .description(row.getDescription())
                        .current(currentIds.contains(row.getId()))
                        .build())
                .toList();
    }

    @Transactional
    public PayrollParameterResponse addVersion(PayrollParameterRequest request) throws BusinessException {
        accessGuard.assertCanApproveRuns();
        String code = PayrollRules.normalize(request.getCode());
        if (code == null || !PayrollParameters.REQUIRED_CODES.contains(code)) {
            throw new BusinessException("Código de parámetro desconocido: " + request.getCode());
        }
        if (code.endsWith("_PCT") && request.getValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("Un porcentaje no puede pasar de 100.");
        }
        boolean duplicate = repository.findAll().stream()
                .anyMatch(row -> row.getCode().equals(code) && row.getEffectiveFrom().equals(request.getEffectiveFrom()));
        if (duplicate) {
            throw new BusinessException("Ya existe un valor de " + code + " vigente desde " + request.getEffectiveFrom() + ".");
        }
        PayrollParameterEntity saved = repository.save(PayrollParameterEntity.builder()
                .code(code)
                .value(request.getValue())
                .effectiveFrom(request.getEffectiveFrom())
                .description(request.getDescription())
                .build());
        return list().stream().filter(row -> row.getId().equals(saved.getId())).findFirst().orElseThrow();
    }

    private Map<String, BigDecimal> valuesAt(LocalDate date) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (PayrollParameterEntity row : repository.findByEffectiveFromLessThanEqualOrderByCodeAscEffectiveFromDesc(date)) {
            values.putIfAbsent(row.getCode(), row.getValue());
        }
        return values;
    }
}
