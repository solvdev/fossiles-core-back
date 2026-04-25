package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialConsumptionHistoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialConsumptionHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Servicio de inteligencia de stock que calcula:
 * - Consumo promedio histórico
 * - Punto de reorden inteligente
 * - Cantidad óptima a solicitar
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StockIntelligenceService {

    private final MaterialRepository materialRepository;
    private final MaterialConsumptionHistoryRepository consumptionHistoryRepository;
    private final SystemConfigRepository systemConfigRepository;

    // Claves de configuración
    private static final String CONFIG_SAFETY_FACTOR = "smart_purchasing.safety_factor";
    private static final String CONFIG_FORECAST_DAYS = "smart_purchasing.forecast_days";
    private static final String CONFIG_HISTORY_DAYS = "smart_purchasing.history_days";
    private static final String CONFIG_MIN_STOCK_THRESHOLD = "smart_purchasing.min_stock_threshold";

    // Valores por defecto
    private static final double DEFAULT_SAFETY_FACTOR = 1.15; // 15% extra
    private static final int DEFAULT_FORECAST_DAYS = 14;
    private static final int DEFAULT_HISTORY_DAYS = 30;
    private static final double DEFAULT_MIN_STOCK_THRESHOLD = 0.20; // 20% del máximo

    /**
     * Calcula el consumo promedio diario de un material en los últimos N días
     */
    public BigDecimal calculateAverageDailyConsumption(Long materialId, Integer days) {
        if (days == null || days <= 0) {
            days = getConfigInt(CONFIG_HISTORY_DAYS, DEFAULT_HISTORY_DAYS);
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        Double avgConsumption = consumptionHistoryRepository
                .averageConsumptionByMaterialAndDateRange(materialId, startDate, endDate);

        if (avgConsumption == null || avgConsumption == 0) {
            log.debug("No consumption history found for material {} in last {} days", materialId, days);
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(avgConsumption).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Calcula el punto de reorden inteligente basado en:
     * - Consumo promedio diario
     * - Días de entrega del proveedor
     * - Factor de seguridad
     */
    public BigDecimal calculateReorderPoint(Long materialId) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));

        // Consumo promedio diario
        BigDecimal avgDailyConsumption = calculateAverageDailyConsumption(materialId, null);
        
        if (avgDailyConsumption.compareTo(BigDecimal.ZERO) == 0) {
            // Si no hay historial, usar el mínimo configurado o 0
            if (material.getMin() != null) {
                return BigDecimal.valueOf(material.getMin());
            }
            return BigDecimal.ZERO;
        }

        // Días de entrega (del material o por defecto)
        int deliveryDays = material.getDeliveryDays() != null ? material.getDeliveryDays() : 7;
        
        // Días de forecast (buffer adicional)
        int forecastDays = getConfigInt(CONFIG_FORECAST_DAYS, DEFAULT_FORECAST_DAYS);
        
        // Factor de seguridad
        double safetyFactor = getConfigDouble(CONFIG_SAFETY_FACTOR, DEFAULT_SAFETY_FACTOR);

        // Cálculo: (Consumo promedio × (Días entrega + Forecast)) × Factor seguridad
        BigDecimal totalDays = BigDecimal.valueOf(deliveryDays + forecastDays);
        BigDecimal reorderPoint = avgDailyConsumption
                .multiply(totalDays)
                .multiply(BigDecimal.valueOf(safetyFactor))
                .setScale(3, RoundingMode.HALF_UP);

        log.debug("Reorder point for material {}: {} (avg daily: {}, days: {}, safety: {})",
                materialId, reorderPoint, avgDailyConsumption, totalDays, safetyFactor);

        return reorderPoint;
    }

    /**
     * Determina si se debe generar una solicitud de material
     */
    public boolean shouldGenerateRequest(Long materialId, BigDecimal requiredQuantity) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));

        BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
        
        // Verificar si el stock actual es menor que lo requerido
        if (currentStock.compareTo(requiredQuantity) < 0) {
            return true;
        }

        // Verificar si está por debajo del punto de reorden
        BigDecimal reorderPoint = calculateReorderPoint(materialId);
        if (reorderPoint.compareTo(BigDecimal.ZERO) > 0 && currentStock.compareTo(reorderPoint) < 0) {
            return true;
        }

        // Verificar umbral mínimo configurado
        if (material.getMax() != null && material.getMax() > 0) {
            double minThreshold = getConfigDouble(CONFIG_MIN_STOCK_THRESHOLD, DEFAULT_MIN_STOCK_THRESHOLD);
            BigDecimal minStock = BigDecimal.valueOf(material.getMax())
                    .multiply(BigDecimal.valueOf(minThreshold));
            
            if (currentStock.compareTo(minStock) < 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calcula la cantidad inteligente a solicitar considerando:
     * - Cantidad necesaria inmediata
     - Consumo futuro estimado
     - Factor de seguridad
     */
    public BigDecimal calculateSmartRequestQuantity(Long materialId, BigDecimal requiredQuantity) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));

        BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
        
        // Cantidad necesaria inmediata
        BigDecimal immediateNeed = requiredQuantity.subtract(currentStock);
        if (immediateNeed.compareTo(BigDecimal.ZERO) < 0) {
            immediateNeed = BigDecimal.ZERO;
        }

        // Consumo futuro estimado
        BigDecimal avgDailyConsumption = calculateAverageDailyConsumption(materialId, null);
        int forecastDays = getConfigInt(CONFIG_FORECAST_DAYS, DEFAULT_FORECAST_DAYS);
        BigDecimal futureConsumption = avgDailyConsumption
                .multiply(BigDecimal.valueOf(forecastDays))
                .setScale(3, RoundingMode.HALF_UP);

        // Factor de seguridad
        double safetyFactor = getConfigDouble(CONFIG_SAFETY_FACTOR, DEFAULT_SAFETY_FACTOR);

        // Calcular cantidad total: max(inmediata, futura) × factor seguridad
        BigDecimal baseQuantity = immediateNeed.max(futureConsumption);
        BigDecimal smartQuantity = baseQuantity
                .multiply(BigDecimal.valueOf(safetyFactor))
                .setScale(3, RoundingMode.HALF_UP);

        // Asegurar que sea al menos la cantidad inmediata necesaria
        if (smartQuantity.compareTo(immediateNeed) < 0) {
            smartQuantity = immediateNeed;
        }

        log.debug("Smart request quantity for material {}: {} (immediate: {}, future: {}, safety: {})",
                materialId, smartQuantity, immediateNeed, futureConsumption, safetyFactor);

        return smartQuantity;
    }

    /**
     * Calcula el consumo promedio semanal
     */
    public BigDecimal calculateAverageWeeklyConsumption(Long materialId, Integer weeks) {
        if (weeks == null || weeks <= 0) {
            weeks = 4; // Por defecto 4 semanas
        }
        int days = weeks * 7;
        BigDecimal avgDaily = calculateAverageDailyConsumption(materialId, days);
        return avgDaily.multiply(BigDecimal.valueOf(7)).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Calcula el consumo promedio mensual
     */
    public BigDecimal calculateAverageMonthlyConsumption(Long materialId, Integer months) {
        if (months == null || months <= 0) {
            months = 1; // Por defecto 1 mes
        }
        int days = months * 30;
        BigDecimal avgDaily = calculateAverageDailyConsumption(materialId, days);
        return avgDaily.multiply(BigDecimal.valueOf(30)).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Calcula la Cantidad Económica de Pedido (EOQ - Economic Order Quantity)
     * Fórmula de Wilson: EOQ = √((2 × D × S) / H)
     * Donde:
     * D = Demanda anual (consumo anual estimado)
     * S = Costo de ordenar (costo fijo por pedido)
     * H = Costo de almacenamiento por unidad por año
     */
    public BigDecimal calculateEOQ(Long materialId, BigDecimal orderCost, BigDecimal holdingCostPerUnit) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));

        // Calcular demanda anual (consumo promedio diario × 365)
        BigDecimal avgDailyConsumption = calculateAverageDailyConsumption(materialId, null);
        if (avgDailyConsumption.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("No consumption history for material {}, cannot calculate EOQ", materialId);
            return BigDecimal.ZERO;
        }

        BigDecimal annualDemand = avgDailyConsumption.multiply(BigDecimal.valueOf(365))
                .setScale(3, RoundingMode.HALF_UP);

        // Si no se proporciona orderCost, usar un valor por defecto
        if (orderCost == null || orderCost.compareTo(BigDecimal.ZERO) <= 0) {
            orderCost = BigDecimal.valueOf(50.0); // Q 50.00 por defecto
        }

        // Si no se proporciona holdingCostPerUnit, calcular basado en el costo del material
        if (holdingCostPerUnit == null || holdingCostPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            // Usar el costo unitario del material × porcentaje de almacenamiento (ej: 20%)
            BigDecimal unitCost = material.getUnitCost() != null ? material.getUnitCost() 
                : (material.getCost() != null ? material.getCost() 
                : (material.getPurchasePrice() != null && material.getPurchaseQuantity() != null 
                    && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? material.getPurchasePrice().divide(material.getPurchaseQuantity(), 4, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(2.0)));
            holdingCostPerUnit = unitCost.multiply(BigDecimal.valueOf(0.20)); // 20% del costo unitario
        }

        // Calcular EOQ: √((2 × D × S) / H)
        BigDecimal numerator = BigDecimal.valueOf(2)
                .multiply(annualDemand)
                .multiply(orderCost);
        
        if (holdingCostPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Holding cost is zero or negative for material {}, cannot calculate EOQ", materialId);
            return BigDecimal.ZERO;
        }

        BigDecimal eoqValue = numerator.divide(holdingCostPerUnit, 10, RoundingMode.HALF_UP);
        
        // Calcular raíz cuadrada
        double eoqDouble = Math.sqrt(eoqValue.doubleValue());
        BigDecimal eoq = BigDecimal.valueOf(eoqDouble).setScale(2, RoundingMode.HALF_UP);

        log.debug("EOQ for material {}: {} (annual demand: {}, order cost: {}, holding cost: {})",
                materialId, eoq, annualDemand, orderCost, holdingCostPerUnit);

        return eoq;
    }

    /**
     * Calcula días de inventario restantes basado en consumo promedio
     */
    public BigDecimal calculateDaysOfInventory(Long materialId) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));

        BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
        BigDecimal avgDailyConsumption = calculateAverageDailyConsumption(materialId, null);

        if (avgDailyConsumption.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return currentStock.divide(avgDailyConsumption, 2, RoundingMode.HALF_UP);
    }

    /**
     * Registra consumo de material (se llama cuando se consume material)
     */
    public void recordConsumption(Long materialId, BigDecimal quantity, String source, Long sourceReferenceId) {
        LocalDate today = LocalDate.now();
        
        // Buscar registro del día actual
        Optional<MaterialConsumptionHistoryEntity> existing = consumptionHistoryRepository
                .findByMaterialIdAndConsumptionDate(materialId, today);

        if (existing.isPresent()) {
            // Actualizar registro existente
            MaterialConsumptionHistoryEntity entity = existing.get();
            entity.setQuantityConsumed(entity.getQuantityConsumed().add(quantity));
            consumptionHistoryRepository.save(entity);
        } else {
            // Crear nuevo registro
            MaterialConsumptionHistoryEntity entity = MaterialConsumptionHistoryEntity.builder()
                    .materialId(materialId)
                    .consumptionDate(today)
                    .quantityConsumed(quantity)
                    .source(source)
                    .sourceReferenceId(sourceReferenceId)
                    .build();
            consumptionHistoryRepository.save(entity);
        }

        log.debug("Recorded consumption: material={}, quantity={}, source={}", materialId, quantity, source);
    }

    // Métodos auxiliares para obtener configuración
    private double getConfigDouble(String key, double defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Double.parseDouble(config.getConfigValue());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid config value for {}: {}", key, config.getConfigValue());
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    private int getConfigInt(String key, int defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Integer.parseInt(config.getConfigValue());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid config value for {}: {}", key, config.getConfigValue());
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}

