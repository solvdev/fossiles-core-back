package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.LocationRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LocationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OperationalUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CostCenterRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationRepository locationRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final CostCenterRepository costCenterRepository;

    @GetMapping
    public ResponseEntity<List<LocationResponse>> getAll() {
        List<LocationResponse> locations = locationRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(locations);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        LocationEntity entity = locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<LocationResponse> create(@Valid @RequestBody LocationRequest request) throws BusinessException {
        if (request.getCode() != null && locationRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Location code already exists: " + request.getCode());
        }
        LocationEntity entity = toEntity(request);
        LocationEntity saved = locationRepository.save(entity);
        
        // Si es un kiosko, crear automáticamente unidad operativa y centro de costo
        if (isKiosko(request.getCategoria())) {
            createOperationalUnitAndCostCenterForKiosko(saved);
        }
        
        return ResponseEntity.created(URI.create("/api/locations/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LocationResponse> update(@PathVariable Long id, @Valid @RequestBody LocationRequest request) 
            throws ResourceNotFoundException, BusinessException {
        LocationEntity entity = locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location", id));
        
        if (request.getCode() != null && !entity.getCode().equals(request.getCode()) 
                && locationRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Location code already exists: " + request.getCode());
        }
        
        updateEntity(entity, request);
        LocationEntity updated = locationRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!locationRepository.existsById(id)) {
            throw new ResourceNotFoundException("Location", id);
        }
        locationRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private LocationResponse toResponse(LocationEntity entity) {
        LocationResponse.LocationResponseBuilder builder = LocationResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .departamento(entity.getDepartamento())
                .municipio(entity.getMunicipio())
                .zona(entity.getZona())
                .categoria(entity.getCategoria())
                .encargadoId(entity.getEncargadoId());
        
        builder.felEstablishmentCode(entity.getFelEstablishmentCode())
                .felEstablishmentName(entity.getFelEstablishmentName())
                .felAddressLine(entity.getFelAddressLine())
                .felMunicipio(entity.getFelMunicipio())
                .felDepartamento(entity.getFelDepartamento())
                .posTestMode(Boolean.TRUE.equals(entity.getPosTestMode()));
        
        if (entity.getEncargado() != null) {
            String nombreCompleto = "";
            if (entity.getEncargado().getFirstName() != null) {
                nombreCompleto += entity.getEncargado().getFirstName();
            }
            if (entity.getEncargado().getLastName() != null) {
                nombreCompleto += (nombreCompleto.isEmpty() ? "" : " ") + entity.getEncargado().getLastName();
            }
            if (nombreCompleto.isEmpty()) {
                nombreCompleto = entity.getEncargado().getUsername();
            }
            builder.encargadoNombre(nombreCompleto);
        }
        
        return builder.build();
    }

    private LocationEntity toEntity(LocationRequest request) {
        return LocationEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .departamento(request.getDepartamento())
                .municipio(request.getMunicipio())
                .zona(request.getZona())
                .categoria(request.getCategoria())
                .encargadoId(request.getEncargadoId())
                .felEstablishmentCode(trimToNull(request.getFelEstablishmentCode()))
                .felEstablishmentName(trimToNull(request.getFelEstablishmentName()))
                .felAddressLine(trimToNull(request.getFelAddressLine()))
                .felMunicipio(trimToNull(request.getFelMunicipio()))
                .felDepartamento(trimToNull(request.getFelDepartamento()))
                .posTestMode(Boolean.TRUE.equals(request.getPosTestMode()))
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void updateEntity(LocationEntity entity, LocationRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDepartamento() != null) entity.setDepartamento(request.getDepartamento());
        if (request.getMunicipio() != null) entity.setMunicipio(request.getMunicipio());
        if (request.getZona() != null) entity.setZona(request.getZona());
        if (request.getCategoria() != null) entity.setCategoria(request.getCategoria());
        if (request.getEncargadoId() != null) entity.setEncargadoId(request.getEncargadoId());
        if (request.getFelEstablishmentCode() != null) {
            entity.setFelEstablishmentCode(trimToNull(request.getFelEstablishmentCode()));
        }
        if (request.getFelEstablishmentName() != null) {
            entity.setFelEstablishmentName(trimToNull(request.getFelEstablishmentName()));
        }
        if (request.getFelAddressLine() != null) {
            entity.setFelAddressLine(trimToNull(request.getFelAddressLine()));
        }
        if (request.getFelMunicipio() != null) {
            entity.setFelMunicipio(trimToNull(request.getFelMunicipio()));
        }
        if (request.getFelDepartamento() != null) {
            entity.setFelDepartamento(trimToNull(request.getFelDepartamento()));
        }
        if (request.getPosTestMode() != null) {
            entity.setPosTestMode(request.getPosTestMode());
        }
    }

    /**
     * Verifica si una categoría corresponde a un kiosko
     */
    private boolean isKiosko(String categoria) {
        if (categoria == null) {
            return false;
        }
        String categoriaUpper = categoria.toUpperCase().trim();
        return categoriaUpper.equals("KIOSKO") || 
               categoriaUpper.equals("KIOSK") || 
               categoriaUpper.contains("KIOSKO") ||
               categoriaUpper.contains("KIOSK");
    }

    /**
     * Crea automáticamente una unidad operativa y un centro de costo para un kiosko
     */
    private void createOperationalUnitAndCostCenterForKiosko(LocationEntity kiosko) {
        String baseCode = kiosko.getCode() != null ? kiosko.getCode().toUpperCase() : "K" + kiosko.getId();
        String baseName = kiosko.getName() != null ? kiosko.getName() : "Kiosko " + kiosko.getId();
        
        // Crear Unidad Operativa
        String uoCode = "UO-" + baseCode;
        if (!operationalUnitRepository.existsByCode(uoCode)) {
            OperationalUnitEntity operationalUnit = OperationalUnitEntity.builder()
                    .code(uoCode)
                    .name("Unidad Operativa " + baseName)
                    .description("Unidad operativa creada automáticamente para el kiosko: " + baseName)
                    .build();
            operationalUnitRepository.save(operationalUnit);
        }
        
        // Crear Centro de Costo
        String ccCode = "CC-" + baseCode;
        if (!costCenterRepository.existsByCode(ccCode)) {
            CostCenterEntity costCenter = CostCenterEntity.builder()
                    .code(ccCode)
                    .name("Centro de Costo " + baseName)
                    .description("Centro de costo creado automáticamente para el kiosko: " + baseName)
                    .build();
            costCenterRepository.save(costCenter);
        }
    }
}

