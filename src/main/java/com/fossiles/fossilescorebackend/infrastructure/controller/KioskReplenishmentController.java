package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskReplenishmentDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskReplenishmentSuggestionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.service.KioskReplenishmentService;
import com.fossiles.fossilescorebackend.application.service.ProductDistributionService;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reposición automática de kioscos por categoría A/B/C. La sugerencia solo calcula; al confirmar se crea una
 * distribución de reposición con un envío por kiosco ({@link ProductDistributionService#createReplenishmentDistribution}).
 */
@RestController
@RequestMapping("/api/kiosk-replenishment")
@RequiredArgsConstructor
public class KioskReplenishmentController {

    private final KioskReplenishmentService kioskReplenishmentService;
    private final ProductDistributionService productDistributionService;

    @GetMapping("/suggestion")
    public ResponseEntity<KioskReplenishmentSuggestionResponse> suggestion(
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(required = false) List<Long> locationIds
    ) throws BusinessException {
        return ResponseEntity.ok(kioskReplenishmentService.suggest(windowDays, locationIds));
    }

    @PostMapping("/distributions")
    public ResponseEntity<ProductDistributionResponse> createDistribution(
            @RequestBody KioskReplenishmentDistributionRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(productDistributionService.createReplenishmentDistribution(request));
    }
}
