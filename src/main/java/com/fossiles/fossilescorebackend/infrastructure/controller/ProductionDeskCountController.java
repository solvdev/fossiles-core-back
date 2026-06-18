package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DeskCountUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DeskCountDayResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductionDeskCountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/production/desks-count")
public class ProductionDeskCountController {

    private final ProductionDeskCountService productionDeskCountService;

    @GetMapping
    public ResponseEntity<DeskCountDayResponse> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) throws BusinessException {
        return ResponseEntity.ok(productionDeskCountService.getDay(date));
    }

    @PutMapping
    public ResponseEntity<DeskCountDayResponse> replaceDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody @Valid DeskCountUpdateRequest body
    ) throws BusinessException {
        return ResponseEntity.ok(productionDeskCountService.replaceDay(date, body.getNumDesks()));
    }
}

