package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskReplenishmentSuggestionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskReplenishmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reposición automática de kioscos por categoría A/B/C. Solo calcula: los envíos los crea el usuario al
 * confirmar, con el flujo de envío directo a kiosko ({@code /api/product-distributions/shipments/standalone-kiosk}).
 */
@RestController
@RequestMapping("/api/kiosk-replenishment")
@RequiredArgsConstructor
public class KioskReplenishmentController {

    private final KioskReplenishmentService kioskReplenishmentService;

    @GetMapping("/suggestion")
    public ResponseEntity<KioskReplenishmentSuggestionResponse> suggestion(
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(required = false) List<Long> locationIds
    ) throws BusinessException {
        return ResponseEntity.ok(kioskReplenishmentService.suggest(windowDays, locationIds));
    }
}
