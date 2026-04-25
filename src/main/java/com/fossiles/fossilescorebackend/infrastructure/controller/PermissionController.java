package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PermissionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PermissionSyncRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PermissionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PermissionSyncResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getAll() {
        return ResponseEntity.ok(permissionService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PermissionResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        return ResponseEntity.ok(permissionService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PermissionResponse> create(@Valid @RequestBody PermissionRequest request) throws BusinessException {
        PermissionResponse created = permissionService.create(request);
        return ResponseEntity.created(URI.create("/api/permissions/" + created.getId())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PermissionResponse> update(@PathVariable Long id, @Valid @RequestBody PermissionRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(permissionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        permissionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync")
    public ResponseEntity<PermissionSyncResponse> syncPermissions(@Valid @RequestBody PermissionSyncRequest request) {
        PermissionSyncResponse response = permissionService.syncPermissions(request.getPermissions());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sync/report")
    public ResponseEntity<PermissionSyncResponse> getSyncReport(@Valid @RequestBody PermissionSyncRequest request) {
        // Este endpoint solo genera el reporte sin aplicar cambios
        PermissionSyncResponse response = permissionService.syncPermissions(request.getPermissions());
        return ResponseEntity.ok(response);
    }
}

