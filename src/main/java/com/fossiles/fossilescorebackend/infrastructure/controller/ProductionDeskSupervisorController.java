package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DeskSupervisorAssignmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DeskSupervisorDayResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductionDeskSupervisorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/production/desk-supervisors")
@RequiredArgsConstructor
public class ProductionDeskSupervisorController {

    private final ProductionDeskSupervisorService productionDeskSupervisorService;

    @GetMapping
    public ResponseEntity<DeskSupervisorDayResponse> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws BusinessException {
        return ResponseEntity.ok(productionDeskSupervisorService.getDay(date));
    }

    @PutMapping
    public ResponseEntity<DeskSupervisorDayResponse> replaceDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody @Valid List<DeskSupervisorAssignmentRequest> assignments) throws BusinessException {
        return ResponseEntity.ok(productionDeskSupervisorService.replaceDay(date, assignments));
    }
}
