package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.DistribucionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.EnvioRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DistribucionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.EnvioResponse;
import com.fossiles.fossilescorebackend.application.dto.response.EnvioDetalleResponse;
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
import java.util.List;
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
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;

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
     * Finaliza una distribución:
     * - Descuenta productos de BODEGA_PT
     * - Suma productos a cada kiosko
     * - Registra movimientos en kardex
     */
    public DistribucionResponse finalizarDistribucion(Long distribucionId) 
            throws ResourceNotFoundException, BusinessException {
        DistribucionEntity distribucion = distribucionRepository.findById(distribucionId)
                .orElseThrow(() -> new ResourceNotFoundException("Distribucion", distribucionId));
        
        if ("FINALIZADA".equals(distribucion.getEstado())) {
            throw new BusinessException("La distribución ya está finalizada");
        }
        
        // Obtener todos los envíos de la distribución
        List<EnvioEntity> envios = envioRepository.findByDistribucionId(distribucionId);
        
        if (envios.isEmpty()) {
            throw new BusinessException("No se puede finalizar una distribución sin envíos");
        }
        
        // Obtener ubicación de BODEGA_PT
        LocationEntity bodegaPT = getOrCreateInventoryLocation("BODEGA_PT");
        if (bodegaPT == null) {
            throw new BusinessException("No se encontró la ubicación BODEGA_PT");
        }
        
        // Validar y procesar cada envío
        for (EnvioEntity envio : envios) {
            List<EnvioDetalleEntity> detalles = envioDetalleRepository.findByEnvioId(envio.getId());
            
            for (EnvioDetalleEntity detalle : detalles) {
                ProductEntity product = productRepository.findById(detalle.getProductId()).orElse(null);
                String productCode = product != null && product.getCode() != null ? product.getCode().trim().toUpperCase() : "";
                if (productCode.startsWith("SUM")) {
                    continue;
                }

                // Validar stock disponible en BODEGA_PT
                ProductInventoryLocation bodegaInventory = productInventoryLocationRepository
                        .findByProductIdAndLocationIdAndColorId(detalle.getProductId(), bodegaPT.getId(), null)
                        .orElse(null);
                
                BigDecimal stockDisponible = bodegaInventory != null ? bodegaInventory.getQuantity() : BigDecimal.ZERO;
                
                if (stockDisponible.compareTo(detalle.getCantidad()) < 0) {
                    String productName = product != null ? product.getName() : "Producto ID " + detalle.getProductId();
                    throw new BusinessException(
                        String.format("Stock insuficiente en BODEGA_PT para %s. Disponible: %s, Solicitado: %s",
                            productName, stockDisponible, detalle.getCantidad())
                    );
                }
                
                // Descontar de BODEGA_PT
                if (bodegaInventory == null) {
                    bodegaInventory = ProductInventoryLocation.builder()
                            .productId(detalle.getProductId())
                            .locationId(bodegaPT.getId())
                            .quantity(BigDecimal.ZERO)
                            .build();
                }
                bodegaInventory.setQuantity(bodegaInventory.getQuantity().subtract(detalle.getCantidad()));
                productInventoryLocationRepository.save(bodegaInventory);
                
                // Registrar movimiento en kardex (BODEGA_PT)
                ProductInventoryKardex kardexBodega = ProductInventoryKardex.builder()
                        .productId(detalle.getProductId())
                        .locationId(bodegaPT.getId())
                        .movementType("DISTRIBUTION_EXIT")
                        .quantity(detalle.getCantidad().negate())
                        .quantityBefore(bodegaInventory.getQuantity().add(detalle.getCantidad()))
                        .quantityAfter(bodegaInventory.getQuantity())
                        .referenceType("DISTRIBUCION")
                        .referenceId(distribucionId)
                        .description("Salida por distribución " + distribucion.getNumeroDistribucion() + " - Envío " + envio.getNumeroEnvio())
                        .movementDate(LocalDateTime.now())
                        .build();
                productInventoryKardexRepository.save(kardexBodega);
                
                // Sumar al kiosko
                ProductInventoryLocation kioskoInventory = productInventoryLocationRepository
                        .findByProductIdAndLocationIdAndColorId(detalle.getProductId(), envio.getLocationId(), null)
                        .orElse(null);
                
                if (kioskoInventory == null) {
                    kioskoInventory = ProductInventoryLocation.builder()
                            .productId(detalle.getProductId())
                            .locationId(envio.getLocationId())
                            .quantity(BigDecimal.ZERO)
                            .build();
                }
                kioskoInventory.setQuantity(kioskoInventory.getQuantity().add(detalle.getCantidad()));
                productInventoryLocationRepository.save(kioskoInventory);
                
                // Registrar movimiento en kardex (Kiosko)
                ProductInventoryKardex kardexKiosko = ProductInventoryKardex.builder()
                        .productId(detalle.getProductId())
                        .locationId(envio.getLocationId())
                        .movementType("DISTRIBUTION_ENTRY")
                        .quantity(detalle.getCantidad())
                        .quantityBefore(kioskoInventory.getQuantity().subtract(detalle.getCantidad()))
                        .quantityAfter(kioskoInventory.getQuantity())
                        .referenceType("DISTRIBUCION")
                        .referenceId(distribucionId)
                        .description("Entrada por distribución " + distribucion.getNumeroDistribucion() + " - Envío " + envio.getNumeroEnvio())
                        .movementDate(LocalDateTime.now())
                        .build();
                productInventoryKardexRepository.save(kardexKiosko);
            }
            
            // Actualizar estado del envío
            envio.setEstado("ENVIADO");
            envioRepository.save(envio);
        }
        
        // Actualizar estado de la distribución
        distribucion.setEstado("FINALIZADA");
        DistribucionEntity saved = distribucionRepository.save(distribucion);
        
        return toDistribucionResponse(saved);
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

