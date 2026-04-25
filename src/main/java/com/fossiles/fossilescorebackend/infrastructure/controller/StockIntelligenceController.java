package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.MaterialRequestItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.MaterialRequestRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PurchaseService;
import com.fossiles.fossilescorebackend.application.service.StockIntelligenceService;
import com.fossiles.fossilescorebackend.application.service.SmartMaterialRequestService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialConsumptionHistoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialConsumptionHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StockIntelligenceController {

    private final StockIntelligenceService stockIntelligenceService;
    private final MaterialRepository materialRepository;
    private final MaterialConsumptionHistoryRepository consumptionHistoryRepository;
    private final PurchaseService purchaseService;

    @GetMapping("/materials/{materialId}/consumption-history")
    public ResponseEntity<List<Map<String, Object>>> getConsumptionHistory(
            @PathVariable Long materialId,
            @RequestParam(defaultValue = "30") Integer days) {
        
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        
        List<MaterialConsumptionHistoryEntity> history = consumptionHistoryRepository
                .findByMaterialIdAndConsumptionDateBetween(materialId, startDate, endDate);
        
        List<Map<String, Object>> response = history.stream()
                .map(entry -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", entry.getId());
                    map.put("consumptionDate", entry.getConsumptionDate());
                    map.put("quantityConsumed", entry.getQuantityConsumed());
                    map.put("source", entry.getSource());
                    map.put("sourceReferenceId", entry.getSourceReferenceId());
                    return map;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/materials/{materialId}/stock-intelligence")
    public ResponseEntity<Map<String, Object>> getStockIntelligence(@PathVariable Long materialId) {
        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material not found: " + materialId));
        
        BigDecimal avgDailyConsumption = stockIntelligenceService
                .calculateAverageDailyConsumption(materialId, null);
        BigDecimal avgWeeklyConsumption = stockIntelligenceService
                .calculateAverageWeeklyConsumption(materialId, null);
        BigDecimal avgMonthlyConsumption = stockIntelligenceService
                .calculateAverageMonthlyConsumption(materialId, null);
        BigDecimal reorderPoint = stockIntelligenceService.calculateReorderPoint(materialId);
        BigDecimal daysOfInventory = stockIntelligenceService.calculateDaysOfInventory(materialId);
        
        // Calcular EOQ (usando valores por defecto si no están configurados)
        BigDecimal eoq = stockIntelligenceService.calculateEOQ(materialId, null, null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("materialId", materialId);
        response.put("sku", material.getSku());
        response.put("name", material.getName());
        response.put("currentStock", material.getQuantity());
        response.put("minStock", material.getMin());
        response.put("maxStock", material.getMax());
        response.put("deliveryDays", material.getDeliveryDays());
        response.put("averageDailyConsumption", avgDailyConsumption);
        response.put("averageWeeklyConsumption", avgWeeklyConsumption);
        response.put("averageMonthlyConsumption", avgMonthlyConsumption);
        response.put("reorderPoint", reorderPoint);
        response.put("daysOfInventory", daysOfInventory);
        response.put("eoq", eoq);
        response.put("purchasePrice", material.getPurchasePrice());
        response.put("unitCost", material.getUnitCost() != null ? material.getUnitCost() : material.getCost());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/materials/critical-stock")
    public ResponseEntity<List<Map<String, Object>>> getCriticalMaterials() {
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        List<Map<String, Object>> criticalMaterials = allMaterials.stream()
                .filter(material -> {
                    BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
                    BigDecimal reorderPoint = stockIntelligenceService.calculateReorderPoint(material.getId());
                    
                    // Material crítico si:
                    // 1. Stock es menor que punto de reorden
                    // 2. Stock es menor que mínimo configurado
                    // 3. Stock es cero o negativo
                    return currentStock.compareTo(BigDecimal.ZERO) <= 0 ||
                           (reorderPoint.compareTo(BigDecimal.ZERO) > 0 && currentStock.compareTo(reorderPoint) < 0) ||
                           (material.getMin() != null && currentStock.compareTo(BigDecimal.valueOf(material.getMin())) < 0);
                })
                .map(material -> {
                    BigDecimal avgDailyConsumption = stockIntelligenceService
                            .calculateAverageDailyConsumption(material.getId(), null);
                    BigDecimal reorderPoint = stockIntelligenceService.calculateReorderPoint(material.getId());
                    
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", material.getId());
                    map.put("sku", material.getSku());
                    map.put("name", material.getName());
                    map.put("quantity", material.getQuantity());
                    map.put("min", material.getMin());
                    map.put("max", material.getMax());
                    map.put("reorderPoint", reorderPoint);
                    map.put("averageDailyConsumption", avgDailyConsumption);
                    BigDecimal daysOfInventory = stockIntelligenceService.calculateDaysOfInventory(material.getId());
                    map.put("daysOfInventory", daysOfInventory);
                    BigDecimal eoq = stockIntelligenceService.calculateEOQ(material.getId(), null, null);
                    map.put("eoq", eoq);
                    return map;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(criticalMaterials);
    }

    @PostMapping("/material-requests/generate-auto")
    public ResponseEntity<Map<String, Object>> generateAutoRequests() {
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        int generatedCount = 0;
        List<MaterialRequestResponse> createdRequests = new ArrayList<>();
        List<Map<String, Object>> failedRequests = new ArrayList<>();
        
        // Agrupar materiales críticos por solicitud
        Map<Long, List<MaterialRequestItemRequest>> materialsByRequest = new HashMap<>();
        
        for (MaterialEntity material : allMaterials) {
            BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
            BigDecimal reorderPoint = stockIntelligenceService.calculateReorderPoint(material.getId());
            
            // Verificar si necesita generar solicitud
            if (reorderPoint.compareTo(BigDecimal.ZERO) > 0 && currentStock.compareTo(reorderPoint) < 0) {
                BigDecimal eoq = stockIntelligenceService.calculateEOQ(material.getId(), null, null);
                BigDecimal suggestedQuantity = eoq.compareTo(BigDecimal.ZERO) > 0 
                    ? eoq 
                    : reorderPoint.multiply(BigDecimal.valueOf(2)); // Si no hay EOQ, usar 2x punto de reorden
                
                // Crear item de solicitud
                MaterialRequestItemRequest item = MaterialRequestItemRequest.builder()
                        .materialId(material.getId())
                        .quantityRequested(suggestedQuantity)
                        .build();
                
                // Agrupar todos en una sola solicitud con origen AUTO_REORDEN
                materialsByRequest.computeIfAbsent(0L, k -> new ArrayList<>()).add(item);
            }
        }
        
        // Crear solicitud única con todos los materiales críticos
        if (!materialsByRequest.isEmpty() && !materialsByRequest.get(0L).isEmpty()) {
            try {
                MaterialRequestRequest request = MaterialRequestRequest.builder()
                        .origin("AUTO_REORDEN")
                        .items(materialsByRequest.get(0L))
                        .observations("Solicitud generada automáticamente por sistema de inteligencia de stock")
                        .build();
                
                MaterialRequestResponse createdRequest = purchaseService.createMaterialRequest(request);
                createdRequests.add(createdRequest);
                generatedCount = materialsByRequest.get(0L).size();
            } catch (Exception e) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", e.getMessage());
                error.put("materialsCount", materialsByRequest.get(0L).size());
                failedRequests.add(error);
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("generatedCount", generatedCount);
        response.put("createdRequests", createdRequests);
        response.put("failedRequests", failedRequests);
        response.put("message", "Se generó " + (createdRequests.size() > 0 ? "1" : "0") + " solicitud automática con " + generatedCount + " materiales");
        
        return ResponseEntity.ok(response);
    }
}

