package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DistribucionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.EnvioRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DistribucionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.EnvioResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.DistribucionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/distribuciones")
@RequiredArgsConstructor
public class DistribucionController {

    private final DistribucionService distribucionService;

    // ========== DISTRIBUCION ==========

    @GetMapping
    public ResponseEntity<List<DistribucionResponse>> getAllDistribuciones() {
        List<DistribucionResponse> distribuciones = distribucionService.getAllDistribuciones();
        return ResponseEntity.ok(distribuciones);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DistribucionResponse> getDistribucionById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        DistribucionResponse distribucion = distribucionService.getDistribucionById(id);
        return ResponseEntity.ok(distribucion);
    }

    @PostMapping
    public ResponseEntity<DistribucionResponse> createDistribucion(@Valid @RequestBody DistribucionRequest request) {
        DistribucionResponse distribucion = distribucionService.createDistribucion(request);
        return ResponseEntity.ok(distribucion);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DistribucionResponse> updateDistribucion(
            @PathVariable Long id,
            @Valid @RequestBody DistribucionRequest request) 
            throws ResourceNotFoundException {
        DistribucionResponse distribucion = distribucionService.updateDistribucion(id, request);
        return ResponseEntity.ok(distribucion);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDistribucion(@PathVariable Long id) throws ResourceNotFoundException {
        distribucionService.deleteDistribucion(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/finalizar")
    public ResponseEntity<DistribucionResponse> finalizarDistribucion(@PathVariable Long id) 
            throws ResourceNotFoundException, BusinessException {
        DistribucionResponse distribucion = distribucionService.finalizarDistribucion(id);
        return ResponseEntity.ok(distribucion);
    }

    // ========== ENVIO ==========

    @GetMapping("/{distribucionId}/envios")
    public ResponseEntity<List<EnvioResponse>> getEnviosByDistribucion(@PathVariable Long distribucionId) {
        List<EnvioResponse> envios = distribucionService.getEnviosByDistribucion(distribucionId);
        return ResponseEntity.ok(envios);
    }

    @GetMapping("/envios/{id}")
    public ResponseEntity<EnvioResponse> getEnvioById(@PathVariable Long id) throws ResourceNotFoundException {
        EnvioResponse envio = distribucionService.getEnvioById(id);
        return ResponseEntity.ok(envio);
    }

    @PostMapping("/{distribucionId}/envios")
    public ResponseEntity<EnvioResponse> createOrUpdateEnvio(
            @PathVariable Long distribucionId,
            @Valid @RequestBody EnvioRequest request) 
            throws ResourceNotFoundException, BusinessException {
        EnvioResponse envio = distribucionService.createOrUpdateEnvio(distribucionId, request);
        return ResponseEntity.ok(envio);
    }

    @DeleteMapping("/envios/{id}")
    public ResponseEntity<Void> deleteEnvio(@PathVariable Long id) throws ResourceNotFoundException {
        distribucionService.deleteEnvio(id);
        return ResponseEntity.noContent().build();
    }
}

