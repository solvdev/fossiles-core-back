package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.MaterialRequestItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.MaterialRequestRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio inteligente para generar solicitudes de materiales automáticamente
 * cuando se detecta falta de stock en órdenes de producción o tareas
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SmartMaterialRequestService {

    private final PurchaseService purchaseService;
    private final StockIntelligenceService stockIntelligenceService;
    private final BomRepository bomRepository;
    private final BomItemRepository bomItemRepository;
    private final MaterialRepository materialRepository;
    private final MaterialRequestRepository materialRequestRepository;
    private final MaterialRequestItemRepository materialRequestItemRepository;
    private final SystemConfigRepository systemConfigRepository;

    private static final String CONFIG_AUTO_GENERATE_ENABLED = "smart_purchasing.auto_generate_enabled";

    /**
     * Verifica stock y genera solicitudes automáticamente para una orden de producción
     */
    public List<MaterialRequestResponse> checkAndGenerateRequestsForProductionOrder(
            Long productionOrderId, Long productId, BigDecimal orderQuantity) {
        
        // Verificar si la generación automática está habilitada
        if (!isAutoGenerateEnabled()) {
            log.debug("Auto-generation of material requests is disabled");
            return new ArrayList<>();
        }

        // PROTECCIÓN: Verificar si ya se generó una solicitud para esta orden
        if (hasRequestForProductionOrder(productionOrderId)) {
            log.debug("Skipping auto-generation: Production order {} already has material requests", 
                    productionOrderId);
            return new ArrayList<>();
        }

        log.info("Checking stock for production order {} (product: {}, quantity: {})", 
                productionOrderId, productId, orderQuantity);

        // Obtener BOM activo del producto (status "A" = activo)
        BomEntity bom = bomRepository.findByProductIdAndStatus(productId, "A")
                .stream()
                .findFirst()
                .orElse(null);

        if (bom == null) {
            log.warn("No active BOM found for product {}", productId);
            return new ArrayList<>();
        }

        // Obtener items del BOM
        List<BomItemEntity> bomItems = bomItemRepository.findByBomId(bom.getId());
        
        if (bomItems.isEmpty()) {
            log.warn("BOM {} has no items", bom.getId());
            return new ArrayList<>();
        }

        List<MaterialRequestResponse> generatedRequests = new ArrayList<>();
        List<MaterialRequestItemRequest> requestItems = new ArrayList<>();

        // Verificar cada material del BOM
        for (BomItemEntity bomItem : bomItems) {
            MaterialEntity material = materialRepository.findById(bomItem.getMaterialId())
                    .orElse(null);

            if (material == null) {
                log.warn("Material {} not found for BOM item {}", bomItem.getMaterialId(), bomItem.getId());
                continue;
            }

            // Calcular cantidad necesaria para esta orden
            BigDecimal requiredQuantity = bomItem.getQuantity()
                    .multiply(orderQuantity)
                    .setScale(3, java.math.RoundingMode.HALF_UP);

            // Verificar si se debe generar solicitud
            if (stockIntelligenceService.shouldGenerateRequest(material.getId(), requiredQuantity)) {
                // Calcular cantidad inteligente a solicitar
                BigDecimal smartQuantity = stockIntelligenceService.calculateSmartRequestQuantity(
                        material.getId(), requiredQuantity);

                // Verificar si ya existe una solicitud pendiente para este material
                if (!hasPendingRequestForMaterial(material.getId())) {
                    MaterialRequestItemRequest itemRequest = MaterialRequestItemRequest.builder()
                            .materialId(material.getId())
                            .quantityRequested(smartQuantity)
                            .uomId(material.getUomId())
                            .build();
                    requestItems.add(itemRequest);

                    log.info("Will generate request for material {}: {} (required: {}, stock: {})",
                            material.getSku(), smartQuantity, requiredQuantity,
                            material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO);
                } else {
                    log.debug("Material {} already has a pending request", material.getSku());
                }
            }
        }

        // Generar solicitud si hay items
        if (!requestItems.isEmpty()) {
            MaterialRequestRequest request = MaterialRequestRequest.builder()
                    .origin("ORDEN_PRODUCCION")
                    .originReferenceId(productionOrderId)
                    .items(requestItems)
                    .observations("Generada automáticamente por falta de stock en orden de producción")
                    .build();

            try {
                MaterialRequestResponse response = purchaseService.createMaterialRequest(request);
                generatedRequests.add(response);
                log.info("Generated automatic material request {} for production order {}",
                        response.getId(), productionOrderId);
            } catch (Exception e) {
                log.error("Error generating automatic material request for production order {}: {}",
                        productionOrderId, e.getMessage(), e);
            }
        } else {
            log.info("No material requests needed for production order {}", productionOrderId);
        }

        return generatedRequests;
    }

    /**
     * Verifica si ya existe una solicitud pendiente o aprobada reciente para un material
     * Evita generar solicitudes duplicadas
     */
    private boolean hasPendingRequestForMaterial(Long materialId) {
        // Buscar solicitudes PENDIENTE o APROBADA (que aún no se han comprado)
        List<MaterialRequestEntity> relevantRequests = materialRequestRepository.findAll().stream()
                .filter(req -> "PENDIENTE".equals(req.getStatus()) || "APROBADA".equals(req.getStatus()))
                .collect(Collectors.toList());

        for (MaterialRequestEntity request : relevantRequests) {
            // Verificar items de la solicitud
            List<MaterialRequestItemEntity> items = materialRequestItemRepository
                    .findByMaterialRequestId(request.getId());
            
            boolean hasMaterial = items.stream()
                    .anyMatch(item -> item.getMaterialId().equals(materialId));
            
            if (hasMaterial) {
                log.debug("Material {} already has a {} request (ID: {})", 
                        materialId, request.getStatus(), request.getId());
                return true;
            }
        }

        return false;
    }
    
    /**
     * Verifica si ya se generó una solicitud para esta orden de producción
     * Evita generar múltiples solicitudes para la misma orden
     */
    private boolean hasRequestForProductionOrder(Long productionOrderId) {
        List<MaterialRequestEntity> existingRequests = materialRequestRepository
                .findByOriginAndOriginReferenceId("ORDEN_PRODUCCION", productionOrderId);
        
        // Si ya existe una solicitud para esta orden, no generar otra
        if (!existingRequests.isEmpty()) {
            log.debug("Production order {} already has {} material request(s)", 
                    productionOrderId, existingRequests.size());
            return true;
        }
        
        return false;
    }

    /**
     * Verifica si la generación automática está habilitada
     */
    private boolean isAutoGenerateEnabled() {
        return systemConfigRepository.findByConfigKey(CONFIG_AUTO_GENERATE_ENABLED)
                .map(config -> "true".equalsIgnoreCase(config.getConfigValue()))
                .orElse(true); // Por defecto habilitado
    }
}

