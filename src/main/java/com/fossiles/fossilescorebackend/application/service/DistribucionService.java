package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.DistribucionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.EnvioRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DistribucionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.EnvioResponse;
import com.fossiles.fossilescorebackend.application.dto.response.EnvioDetalleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class DistribucionService {

    private final DistribucionRepository distribucionRepository;
    private final EnvioRepository envioRepository;
    private final EnvioDetalleRepository envioDetalleRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductInventoryService productInventoryService;
    private final ProductShipmentRepository productShipmentRepository;

    private static final String SUM_PRODUCT_CODE_PREFIX = "SUM";
    private static final java.util.Set<String> MODERN_SHIPMENT_INVENTORY_STATUSES = java.util.Set.of(
            "SENT", "DELIVERED", "COMPLETED", "RECEIVED");

    private static String normalizeEstado(String estado) {
        return estado == null ? "" : estado.trim().toUpperCase(Locale.ROOT);
    }

    // ========== DISTRIBUCION ==========

    /**
     * Crea una nueva distribución
     */
    public DistribucionResponse createDistribucion(DistribucionRequest request) {
        // Generar número de distribución automáticamente
        String numeroDistribucion = generateNumeroDistribucion();
        
        DistribucionEntity entity = DistribucionEntity.builder()
                .numeroDistribucion(numeroDistribucion)
                .fecha(request.getFecha())
                .estado(request.getEstado() != null ? request.getEstado() : "BORRADOR")
                .descripcion(request.getDescripcion())
                .build();
        
        DistribucionEntity saved = distribucionRepository.save(entity);
        return toDistribucionResponse(saved);
    }

    /**
     * Obtiene todas las distribuciones
     */
    public List<DistribucionResponse> getAllDistribuciones() {
        return distribucionRepository.findAll().stream()
                .map(this::toDistribucionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene una distribución por ID
     */
    public DistribucionResponse getDistribucionById(Long id) throws ResourceNotFoundException {
        DistribucionEntity entity = distribucionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", id));
        return toDistribucionResponse(entity);
    }

    /**
     * Actualiza una distribución
     */
    public DistribucionResponse updateDistribucion(Long id, DistribucionRequest request) throws ResourceNotFoundException {
        DistribucionEntity entity = distribucionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", id));
        
        if (request.getFecha() != null) entity.setFecha(request.getFecha());
        if (request.getDescripcion() != null) entity.setDescripcion(request.getDescripcion());
        if (request.getEstado() != null) entity.setEstado(request.getEstado());
        
        DistribucionEntity saved = distribucionRepository.save(entity);
        return toDistribucionResponse(saved);
    }

    /**
     * Elimina una distribución (y todos sus envíos)
     */
    public void deleteDistribucion(Long id) throws ResourceNotFoundException {
        DistribucionEntity entity = distribucionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", id));
        distribucionRepository.delete(entity);
    }

    // ========== ENVIO ==========

    /**
     * Crea o actualiza un envío dentro de una distribución
     */
    public EnvioResponse createOrUpdateEnvio(Long distribucionId, EnvioRequest request) 
            throws ResourceNotFoundException, BusinessException {
        // Validar que la distribución existe
        DistribucionEntity distribucion = distribucionRepository.findById(distribucionId)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", distribucionId));
        
        // Validar que la ubicación existe
        LocationEntity location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", request.getLocationId()));
        
        // Buscar si ya existe un envío para este kiosko en esta distribución
        List<EnvioEntity> existingEnvios = envioRepository.findByDistribucionId(distribucionId);
        EnvioEntity envio = existingEnvios.stream()
                .filter(e -> e.getLocationId().equals(request.getLocationId()))
                .findFirst()
                .orElse(null);
        
        String numeroEnvio;
        if (envio == null) {
            // Crear nuevo envío
            numeroEnvio = generateNumeroEnvio(distribucionId);
            envio = EnvioEntity.builder()
                    .distribucionId(distribucionId)
                    .numeroEnvio(numeroEnvio)
                    .locationId(request.getLocationId())
                    .estado("PENDIENTE")
                    .fechaEnvio(request.getFechaEnvio())
                    .observaciones(request.getObservaciones())
                    .build();
        } else {
            // Actualizar envío existente
            if (request.getFechaEnvio() != null) envio.setFechaEnvio(request.getFechaEnvio());
            if (request.getObservaciones() != null) envio.setObservaciones(request.getObservaciones());
        }
        
        EnvioEntity savedEnvio = envioRepository.save(envio);
        
        // Eliminar detalles anteriores
        envioDetalleRepository.deleteByEnvioId(savedEnvio.getId());
        
        // Crear nuevos detalles
        List<EnvioDetalleEntity> detalles = request.getProductos().stream()
                .map(detalleRequest -> {
                    // Validar que el producto existe
                    if (!productRepository.existsById(detalleRequest.getProductId())) {
                        try {
                            throw new ResourceNotFoundException("Product", detalleRequest.getProductId());
                        } catch (ResourceNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    
                    return EnvioDetalleEntity.builder()
                            .envioId(savedEnvio.getId())
                            .productId(detalleRequest.getProductId())
                            .cantidad(detalleRequest.getCantidad())
                            .build();
                })
                .collect(Collectors.toList());
        
        envioDetalleRepository.saveAll(detalles);
        
        return toEnvioResponse(savedEnvio);
    }

    /**
     * Obtiene todos los envíos de una distribución
     */
    public List<EnvioResponse> getEnviosByDistribucion(Long distribucionId) {
        return envioRepository.findByDistribucionId(distribucionId).stream()
                .map(this::toEnvioResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene un envío por ID
     */
    public EnvioResponse getEnvioById(Long id) throws ResourceNotFoundException {
        EnvioEntity entity = envioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Envio", id));
        return toEnvioResponse(entity);
    }

    /**
     * Elimina un envío
     */
    public void deleteEnvio(Long id) throws ResourceNotFoundException {
        EnvioEntity entity = envioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Envio", id));
        envioRepository.delete(entity);
    }

    // ========== FINALIZAR DISTRIBUCION ==========

    /**
     * Confirma el plan de la distribución (sin mover inventario).
     * La salida de Bodega PT se registra en {@link #enviarEnvio(Long)} y la entrada al kiosko en {@link #confirmarRecepcionEnvio(Long)}.
     */
    public DistribucionResponse finalizarDistribucion(Long distribucionId)
            throws ResourceNotFoundException, BusinessException {
        DistribucionEntity distribucion = distribucionRepository.findById(distribucionId)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", distribucionId));

        if ("FINALIZADA".equalsIgnoreCase(String.valueOf(distribucion.getEstado()))) {
            throw new BusinessException("La distribución ya está finalizada");
        }

        List<EnvioEntity> envios = envioRepository.findByDistribucionId(distribucionId);
        if (envios.isEmpty()) {
            throw new BusinessException("No se puede finalizar una distribución sin envíos");
        }

        for (EnvioEntity envio : envios) {
            List<EnvioDetalleEntity> detalles = envioDetalleRepository.findByEnvioId(envio.getId());
            if (detalles.isEmpty()) {
                throw new BusinessException("El envío " + envio.getNumeroEnvio() + " no tiene productos");
            }
            String est = normalizeEstado(envio.getEstado());
            if ("EN_TRANSITO".equals(est) || "RECIBIDO".equals(est) || "ENVIADO".equals(est)) {
                continue;
            }
            envio.setEstado("CONFIRMADO");
            envioRepository.save(envio);
        }

        distribucion.setEstado("FINALIZADA");
        DistribucionEntity saved = distribucionRepository.save(distribucion);
        return toDistribucionResponse(saved);
    }

    /**
     * Registra salida de Bodega PT por envío (después de finalizar la distribución).
     */
    public EnvioResponse enviarEnvio(Long envioId) throws ResourceNotFoundException, BusinessException {
        EnvioEntity envio = envioRepository.findById(envioId)
                .orElseThrow(() -> new ResourceNotFoundException("Envio", envioId));
        DistribucionEntity distribucion = distribucionRepository.findById(envio.getDistribucionId())
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", envio.getDistribucionId()));

        assertNoModernProductShipmentInventoryForKiosk(envio.getDistribucionId(), envio.getLocationId());

        if (!"FINALIZADA".equalsIgnoreCase(String.valueOf(distribucion.getEstado()))) {
            throw new BusinessException("La distribución debe estar finalizada antes de registrar el envío a Bodega PT.");
        }

        String estadoEnvio = normalizeEstado(envio.getEstado());
        if ("EN_TRANSITO".equals(estadoEnvio) || "RECIBIDO".equals(estadoEnvio) || "ENVIADO".equals(estadoEnvio)) {
            throw new BusinessException("Este envío ya fue procesado. Estado actual: " + estadoEnvio);
        }
        if (!"CONFIRMADO".equals(estadoEnvio) && !"PENDIENTE".equals(estadoEnvio)) {
            throw new BusinessException("Solo se puede enviar un envío en estado PENDIENTE o CONFIRMADO. Estado actual: " + estadoEnvio);
        }

        List<EnvioDetalleEntity> detalles = envioDetalleRepository.findByEnvioId(envioId);
        if (detalles.isEmpty()) {
            throw new BusinessException("El envío no tiene productos");
        }

        LocationEntity kiosk = locationRepository.findById(envio.getLocationId()).orElse(null);

        // envio_detalle no guarda color ni talla: se resuelve la variante cuando el producto tiene
        // una sola en las bodegas de despacho. Con varias, la descarga falla de forma visible en
        // lugar de descontar la fila equivocada.
        List<String> shortages = new ArrayList<>();
        for (EnvioDetalleEntity detalle : detalles) {
            if (detalle == null || isPackagingProduct(detalle.getProductId())) continue;
            BigDecimal qty = detalle.getCantidad() != null ? detalle.getCantidad() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;

            Long colorId = productInventoryService.resolveDispatchColorId(detalle.getProductId(), null);
            BigDecimal alreadyOut = productInventoryService.getNetConsumedForLine(
                    "DISTRIBUTION_EXIT", envio.getId(), "DISTRIBUTION_EXIT",
                    detalle.getProductId(), null, colorId, detalle.getId());
            BigDecimal stillNeeded = qty.subtract(alreadyOut);
            if (stillNeeded.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal availableTotal = productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                    detalle.getProductId(), colorId, null);
            if (availableTotal.compareTo(stillNeeded) < 0) {
                ProductEntity product = productRepository.findById(detalle.getProductId()).orElse(null);
                String name = product != null ? product.getCode() + " - " + product.getName() : "Producto #" + detalle.getProductId();
                shortages.add(name + ": disponible " + availableTotal + " (Devoluciones + Bodega PT), requerido " + stillNeeded);
            }
        }
        if (!shortages.isEmpty()) {
            throw new BusinessException("Stock insuficiente en Devoluciones / Bodega PT para enviar:\n• "
                    + String.join("\n• ", shortages));
        }

        for (EnvioDetalleEntity detalle : detalles) {
            if (detalle == null || isPackagingProduct(detalle.getProductId())) continue;
            BigDecimal qty = detalle.getCantidad() != null ? detalle.getCantidad() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;
            productInventoryService.decrementFromDispatchWarehouses(
                    detalle.getProductId(),
                    productInventoryService.resolveDispatchColorId(detalle.getProductId(), null),
                    null,
                    qty,
                    "DISTRIBUTION_EXIT",
                    envio.getId(),
                    envio.getNumeroEnvio(),
                    "Salida por envio " + envio.getNumeroEnvio()
                            + " (" + distribucion.getNumeroDistribucion() + ") hacia "
                            + (kiosk != null ? kiosk.getName() : "kiosko"),
                    "DISTRIBUTION_EXIT",
                    detalle.getId());
        }

        envio.setEstado("EN_TRANSITO");
        envio.setFechaEnvio(LocalDate.now());
        envioRepository.save(envio);
        return toEnvioResponse(envioRepository.findById(envioId).orElse(envio));
    }

    /**
     * Confirma recepción en kiosko e ingresa inventario (envío debe estar en tránsito).
     */
    public EnvioResponse confirmarRecepcionEnvio(Long envioId) throws ResourceNotFoundException, BusinessException {
        EnvioEntity envio = envioRepository.findById(envioId)
                .orElseThrow(() -> new ResourceNotFoundException("Envio", envioId));
        DistribucionEntity distribucion = distribucionRepository.findById(envio.getDistribucionId())
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", envio.getDistribucionId()));

        assertNoModernProductShipmentInventoryForKiosk(envio.getDistribucionId(), envio.getLocationId());

        String estadoEnvio = normalizeEstado(envio.getEstado());
        if (!"EN_TRANSITO".equals(estadoEnvio)) {
            throw new BusinessException("Solo se puede confirmar recepción con envío EN_TRANSITO. Estado actual: " + estadoEnvio);
        }

        List<EnvioDetalleEntity> detalles = envioDetalleRepository.findByEnvioId(envioId);
        for (EnvioDetalleEntity detalle : detalles) {
            if (detalle == null || isPackagingProduct(detalle.getProductId())) continue;
            BigDecimal qty = detalle.getCantidad() != null ? detalle.getCantidad() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;
            ProductInventoryLocationResponse beforeResp = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detalle.getProductId(), envio.getLocationId(), null);
            BigDecimal before = beforeResp.getQuantity();
            productInventoryService.incrementInventory(detalle.getProductId(), envio.getLocationId(), null, qty);
            ProductInventoryLocationResponse afterResp = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detalle.getProductId(), envio.getLocationId(), null);
            BigDecimal after = afterResp.getQuantity();
            productInventoryService.recordProductMovementIfAbsent(
                    detalle.getProductId(),
                    envio.getLocationId(),
                    null,
                    "DISTRIBUTION_ENTRY",
                    qty,
                    before,
                    after,
                    null,
                    "DISTRIBUCION",
                    envio.getId(),
                    envio.getNumeroEnvio(),
                    "Recepcion en kiosko - distribucion " + distribucion.getNumeroDistribucion());
        }

        envio.setEstado("RECIBIDO");
        envioRepository.save(envio);
        return toEnvioResponse(envioRepository.findById(envioId).orElse(envio));
    }

    private void assertNoModernProductShipmentInventoryForKiosk(Long distributionId, Long locationId)
            throws BusinessException {
        if (distributionId == null || locationId == null) {
            return;
        }
        boolean modernActive = productShipmentRepository
                .findByDistributionIdAndLocationId(distributionId, locationId)
                .map(shipment -> {
                    String status = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase(Locale.ROOT);
                    return MODERN_SHIPMENT_INVENTORY_STATUSES.contains(status);
                })
                .orElse(false);
        if (modernActive) {
            throw new BusinessException(
                    "Esta distribución ya tiene envío de producto terminado en Preparar envíos. "
                            + "No use el flujo legacy de envío/recepción para mover inventario otra vez.");
        }
    }

    private boolean isPackagingProduct(Long productId) {
        if (productId == null) return false;
        ProductEntity product = productRepository.findById(productId).orElse(null);
        if (product == null) return false;
        String code = product.getCode() != null ? product.getCode().trim().toUpperCase(Locale.ROOT) : "";
        return code.startsWith(SUM_PRODUCT_CODE_PREFIX);
    }

    // ========== HELPER METHODS ==========

    private String generateNumeroDistribucion() {
        Integer maxNum = distribucionRepository.findMaxNumeroDistribucion();
        int nextNum = (maxNum != null ? maxNum : 0) + 1;
        return String.format("DIS-%05d", nextNum);
    }

    private String generateNumeroEnvio(Long distribucionId) {
        Integer maxNum = envioRepository.findMaxNumeroEnvioByDistribucion(distribucionId);
        int nextNum = (maxNum != null ? maxNum : 0) + 1;
        return String.format("ENV-%03d", nextNum);
    }

    /**
     * Obtiene o crea la ubicación en locations basándose en inventory_location_type
     */
    private LocationEntity getOrCreateInventoryLocation(String categoryCode) {
        Optional<com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity> locationType = 
            inventoryLocationTypeRepository.findByCodeAndIsActiveTrue(categoryCode.toUpperCase());
        
        if (locationType.isEmpty()) {
            return null;
        }
        
        // Buscar si existe en locations
        Optional<LocationEntity> existing = locationRepository.findAll().stream()
                .filter(loc -> locationType.get().getCode().equals(loc.getCode()))
                .findFirst();
        
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // Crear si no existe
        LocationEntity newLocation = LocationEntity.builder()
                .code(locationType.get().getCode())
                .name(locationType.get().getName())
                .categoria(categoryCode.toUpperCase())
                .build();
        return locationRepository.save(newLocation);
    }

    private DistribucionResponse toDistribucionResponse(DistribucionEntity entity) {
        List<EnvioEntity> envios = envioRepository.findByDistribucionId(entity.getId());
        
        return DistribucionResponse.builder()
                .id(entity.getId())
                .numeroDistribucion(entity.getNumeroDistribucion())
                .fecha(entity.getFecha())
                .estado(entity.getEstado())
                .descripcion(entity.getDescripcion())
                .cantidadEnvios(envios.size())
                .envios(envios.stream().map(this::toEnvioResponse).collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private EnvioResponse toEnvioResponse(EnvioEntity entity) {
        List<EnvioDetalleEntity> detalles = envioDetalleRepository.findByEnvioId(entity.getId());
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);
        DistribucionEntity distribucion = distribucionRepository.findById(entity.getDistribucionId()).orElse(null);
        
        return EnvioResponse.builder()
                .id(entity.getId())
                .distribucionId(entity.getDistribucionId())
                .numeroDistribucion(distribucion != null ? distribucion.getNumeroDistribucion() : null)
                .numeroEnvio(entity.getNumeroEnvio())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .estado(entity.getEstado())
                .fechaEnvio(entity.getFechaEnvio())
                .observaciones(entity.getObservaciones())
                .cantidadProductos(detalles.size())
                .productos(detalles.stream().map(this::toEnvioDetalleResponse).collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private EnvioDetalleResponse toEnvioDetalleResponse(EnvioDetalleEntity entity) {
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        
        return EnvioDetalleResponse.builder()
                .id(entity.getId())
                .envioId(entity.getEnvioId())
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .cantidad(entity.getCantidad())
                .build();
    }
}

