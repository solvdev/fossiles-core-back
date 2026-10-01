package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSettingsRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSettingsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskFinancialsSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ajustes globales de Finanzas por kiosco. Permisos (VER / EDITAR) validados en el servicio. */
@RestController
@RequestMapping("/api/kiosk-financials/settings")
@RequiredArgsConstructor
public class KioskFinancialsSettingsController {

    private final KioskFinancialsSettingsService settingsService;

    @GetMapping
    public ResponseEntity<KioskFinancialsSettingsResponse> get() throws BusinessException {
        return ResponseEntity.ok(settingsService.get());
    }

    @PutMapping
    public ResponseEntity<KioskFinancialsSettingsResponse> update(@RequestBody KioskFinancialsSettingsRequest request)
            throws BusinessException {
        return ResponseEntity.ok(settingsService.update(request));
    }
}
