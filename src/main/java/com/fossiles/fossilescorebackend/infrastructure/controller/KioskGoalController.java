package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskGoalUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.SupervisorAssignmentUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalHistoryItemResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalProgressResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGoalResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorAggregateDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorKioskAssignmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupervisorOptionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskGoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kiosk-goals")
@RequiredArgsConstructor
public class KioskGoalController {

    private final KioskGoalService kioskGoalService;

    @GetMapping("/progress")
    public ResponseEntity<KioskGoalProgressResponse> getProgress(
            @RequestParam Long kioskLocationId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.getKioskGoalProgress(kioskLocationId, year, month));
    }

    @GetMapping("/history")
    public ResponseEntity<List<KioskGoalHistoryItemResponse>> getHistory(
            @RequestParam Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.listKioskGoalHistory(kioskLocationId));
    }

    @PutMapping
    public ResponseEntity<KioskGoalResponse> upsertGoal(
            @RequestParam Long kioskLocationId,
            @Valid @RequestBody KioskGoalUpsertRequest request
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.upsertKioskGoal(
                kioskLocationId, request.getGoalYear(), request.getGoalMonth(), request.getGoalAmount()));
    }

    @GetMapping("/supervisor-assignments")
    public ResponseEntity<SupervisorKioskAssignmentResponse> getSupervisorAssignments(
            @RequestParam(required = false) Long supervisorUserId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.getSupervisorAssignments(supervisorUserId));
    }

    @PutMapping("/supervisor-assignments")
    public ResponseEntity<SupervisorKioskAssignmentResponse> updateSupervisorAssignments(
            @RequestParam Long supervisorUserId,
            @RequestBody SupervisorAssignmentUpsertRequest request
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.replaceSupervisorAssignments(
                supervisorUserId, request.getKioskLocationIds()));
    }

    @GetMapping("/supervisor-dashboard")
    public ResponseEntity<SupervisorAggregateDashboardResponse> getSupervisorDashboard(
            @RequestParam(required = false) Long supervisorUserId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.getSupervisorAggregateDashboard(supervisorUserId, year, month));
    }

    @GetMapping("/eligible-supervisors")
    public ResponseEntity<List<SupervisorOptionResponse>> getEligibleSupervisors() throws BusinessException {
        return ResponseEntity.ok(kioskGoalService.getEligibleSupervisors());
    }
}
