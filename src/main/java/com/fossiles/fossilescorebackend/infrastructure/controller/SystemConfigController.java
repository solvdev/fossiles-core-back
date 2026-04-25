package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ManufacturingConfigRequest;
import com.fossiles.fossilescorebackend.application.dto.request.SystemConfigRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ManufacturingCostsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SystemConfigResponse;
import com.fossiles.fossilescorebackend.application.service.ManufacturingCostService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system-config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigRepository systemConfigRepository;
    private final ManufacturingCostService manufacturingCostService;

    @GetMapping
    public ResponseEntity<List<SystemConfigResponse>> getAll() {
        List<SystemConfigResponse> configs = systemConfigRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{key}")
    public ResponseEntity<SystemConfigResponse> getByKey(@PathVariable String key) {
        SystemConfigEntity entity = systemConfigRepository.findByConfigKey(key)
                .orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<SystemConfigResponse> create(@Valid @RequestBody SystemConfigRequest request) {
        if (systemConfigRepository.findByConfigKey(request.getConfigKey()).isPresent()) {
            return ResponseEntity.badRequest().build();
        }
        SystemConfigEntity entity = toEntity(request);
        SystemConfigEntity saved = systemConfigRepository.save(entity);
        return ResponseEntity.ok(toResponse(saved));
    }

    @PutMapping("/{key}")
    public ResponseEntity<SystemConfigResponse> update(@PathVariable String key, @Valid @RequestBody SystemConfigRequest request) {
        SystemConfigEntity entity = systemConfigRepository.findByConfigKey(key)
                .orElse(null);
        if (entity == null) {
            entity = toEntity(request);
        } else {
            if (request.getConfigValue() != null) {
                entity.setConfigValue(request.getConfigValue());
            }
            if (request.getDescription() != null) {
                entity.setDescription(request.getDescription());
            }
        }
        SystemConfigEntity saved = systemConfigRepository.save(entity);
        return ResponseEntity.ok(toResponse(saved));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        systemConfigRepository.findByConfigKey(key)
                .ifPresent(systemConfigRepository::delete);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/manufacturing/calculate-costs")
    public ResponseEntity<ManufacturingCostsResponse> calculateManufacturingCosts(
            @Valid @RequestBody ManufacturingConfigRequest request) {
        ManufacturingCostsResponse response = manufacturingCostService.calculateCosts(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/manufacturing/current-costs")
    public ResponseEntity<ManufacturingCostsResponse> getCurrentManufacturingCosts() {
        ManufacturingCostsResponse response = manufacturingCostService.getCurrentCosts();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/manufacturing/save")
    public ResponseEntity<String> saveManufacturingConfig(@Valid @RequestBody ManufacturingConfigRequest request) {
        // Save payroll cinchos
        saveOrUpdateConfig("MANUFACTURING_PAYROLL_CINCHOS", request.getPayrollCinchos().toString(),
                "Planilla de la línea de producción de cinchos");

        // Save payroll mesas
        saveOrUpdateConfig("MANUFACTURING_PAYROLL_MESAS", request.getPayrollMesas().toString(),
                "Planilla de la línea de producción de mesas");

        // Save payroll warehouse
        saveOrUpdateConfig("MANUFACTURING_PAYROLL_WAREHOUSE", request.getPayrollWarehouse().toString(),
                "Planilla de operaciones de bodega");

        // Save minutes cinchos
        saveOrUpdateConfig("MANUFACTURING_MINUTES_CINCHOS", request.getMinutesCinchos().toString(),
                "Minutos productivos de trabajo para la línea de cinchos");

        // Save minutes mesas
        saveOrUpdateConfig("MANUFACTURING_MINUTES_MESAS", request.getMinutesMesas().toString(),
                "Minutos productivos de trabajo para la línea de mesas");

        // Save number of tables mesas
        saveOrUpdateConfig("MANUFACTURING_NUMBER_OF_TABLES_MESAS", request.getNumberOfTablesMesas().toString(),
                "Número de mesas de trabajo para la línea de mesas");

        // Also save for backward compatibility (used by ProductRecipeModal)
        saveOrUpdateConfig("MANUFACTURING_TOTAL_PAYROLL", request.getPayrollMesas().toString(),
                "Planilla para productos de mesas (categorías distintas a código FOSS)");
        saveOrUpdateConfig("MANUFACTURING_AVAILABLE_HOURS", request.getMinutesMesas().toString(),
                "Minutos productivos de trabajo para productos de mesas (por período, ej: mensual)");
        saveOrUpdateConfig("MANUFACTURING_NUMBER_OF_TABLES", request.getNumberOfTablesMesas().toString(),
                "Número de mesas de trabajo para productos de mesas");

        return ResponseEntity.ok("Configuración guardada correctamente");
    }

    private void saveOrUpdateConfig(String key, String value, String description) {
        SystemConfigEntity entity = systemConfigRepository.findByConfigKey(key).orElse(null);
        if (entity == null) {
            entity = SystemConfigEntity.builder()
                    .configKey(key)
                    .configValue(value)
                    .description(description)
                    .build();
        } else {
            entity.setConfigValue(value);
            if (description != null) {
                entity.setDescription(description);
            }
        }
        systemConfigRepository.save(entity);
    }

    private SystemConfigResponse toResponse(SystemConfigEntity entity) {
        return SystemConfigResponse.builder()
                .configKey(entity.getConfigKey())
                .configValue(entity.getConfigValue())
                .description(entity.getDescription())
                .build();
    }

    private SystemConfigEntity toEntity(SystemConfigRequest request) {
        return SystemConfigEntity.builder()
                .configKey(request.getConfigKey())
                .configValue(request.getConfigValue())
                .description(request.getDescription())
                .build();
    }
}

