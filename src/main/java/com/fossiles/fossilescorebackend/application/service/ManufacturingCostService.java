package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ManufacturingConfigRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ManufacturingCostsResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class ManufacturingCostService {

    private final SystemConfigRepository systemConfigRepository;

    /**
     * Calculate hourly manufacturing costs for cinchos and mesas production lines
     * 
     * Cinchos formula: ((payrollCinchos + payrollWarehouse) / minutesCinchos) * 60
     * Mesas formula: ((payrollMesas + payrollWarehouse) / (numberOfTablesMesas * minutesMesas)) * 60
     */
    public ManufacturingCostsResponse calculateCosts(ManufacturingConfigRequest request) {
        BigDecimal costoHoraCinchos = BigDecimal.ZERO;
        BigDecimal costoHoraMesas = BigDecimal.ZERO;

        // Calculate cinchos hourly cost: ((payrollCinchos + payrollWarehouse) / minutesCinchos) * 60
        if (request.getMinutesCinchos() != null && request.getMinutesCinchos() > 0) {
            BigDecimal totalPayrollCinchos = request.getPayrollCinchos().add(request.getPayrollWarehouse());
            BigDecimal minutesCinchos = BigDecimal.valueOf(request.getMinutesCinchos());
            costoHoraCinchos = totalPayrollCinchos
                    .divide(minutesCinchos, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(60))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Calculate mesas hourly cost: ((payrollMesas + payrollWarehouse) / (numberOfTablesMesas * minutesMesas)) * 60
        if (request.getMinutesMesas() != null && request.getMinutesMesas() > 0 
                && request.getNumberOfTablesMesas() != null && request.getNumberOfTablesMesas() > 0) {
            BigDecimal totalPayrollMesas = request.getPayrollMesas().add(request.getPayrollWarehouse());
            BigDecimal denominator = BigDecimal.valueOf(request.getNumberOfTablesMesas())
                    .multiply(BigDecimal.valueOf(request.getMinutesMesas()));
            costoHoraMesas = totalPayrollMesas
                    .divide(denominator, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(60))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return ManufacturingCostsResponse.builder()
                .costoHoraCinchos(costoHoraCinchos)
                .costoHoraMesas(costoHoraMesas)
                .build();
    }

    /**
     * Get current manufacturing costs calculated from saved configuration
     */
    public ManufacturingCostsResponse getCurrentCosts() {
        BigDecimal payrollCinchos = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_CINCHOS");
        BigDecimal payrollMesas = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_MESAS");
        BigDecimal payrollWarehouse = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_WAREHOUSE");
        Integer minutesCinchos = getConfigValueAsInteger("MANUFACTURING_MINUTES_CINCHOS");
        Integer minutesMesas = getConfigValueAsInteger("MANUFACTURING_MINUTES_MESAS");
        Integer numberOfTablesMesas = getConfigValueAsInteger("MANUFACTURING_NUMBER_OF_TABLES_MESAS");

        ManufacturingConfigRequest request = ManufacturingConfigRequest.builder()
                .payrollCinchos(payrollCinchos != null ? payrollCinchos : BigDecimal.ZERO)
                .payrollMesas(payrollMesas != null ? payrollMesas : BigDecimal.ZERO)
                .payrollWarehouse(payrollWarehouse != null ? payrollWarehouse : BigDecimal.ZERO)
                .minutesCinchos(minutesCinchos != null ? minutesCinchos : 0)
                .minutesMesas(minutesMesas != null ? minutesMesas : 0)
                .numberOfTablesMesas(numberOfTablesMesas != null ? numberOfTablesMesas : 12)
                .build();

        return calculateCosts(request);
    }

    private BigDecimal getConfigValueAsBigDecimal(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(entity -> {
                    try {
                        return new BigDecimal(entity.getConfigValue());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private Integer getConfigValueAsInteger(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(entity -> {
                    try {
                        return Integer.parseInt(entity.getConfigValue());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}

