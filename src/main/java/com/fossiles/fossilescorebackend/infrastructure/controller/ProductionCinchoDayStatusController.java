package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CinchoDayStatusUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CinchoDayWorkStatusUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CinchoDayStatusDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CinchoDayStatusEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductionCinchoDayStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/production/cincho-day-status")
@RequiredArgsConstructor
public class ProductionCinchoDayStatusController {

    private final ProductionCinchoDayStatusService productionCinchoDayStatusService;

    @GetMapping
    public ResponseEntity<CinchoDayStatusDayResponse> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws BusinessException {
        return ResponseEntity.ok(productionCinchoDayStatusService.getStatusesForDate(date));
    }

    @PutMapping
    public ResponseEntity<CinchoDayStatusEntryResponse> setDelivered(
            @Valid @RequestBody CinchoDayStatusUpdateRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(productionCinchoDayStatusService.setDelivered(request));
    }

    @PutMapping("/work-status")
    public ResponseEntity<CinchoDayStatusEntryResponse> setWorkStatus(
            @Valid @RequestBody CinchoDayWorkStatusUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(productionCinchoDayStatusService.setWorkStatus(request));
    }
}
