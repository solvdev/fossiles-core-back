package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PermissionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PermissionSyncRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PermissionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PermissionSyncResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.mapper.PermissionMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final PermissionMapper permissionMapper;

    public PermissionResponse create(PermissionRequest request) throws BusinessException {
        if (permissionRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Permission code already exists: " + request.getCode());
        }
        PermissionEntity entity = permissionMapper.toEntity(request);
        PermissionEntity saved = permissionRepository.save(entity);
        return permissionMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> findAll() {
        return permissionRepository.findAll().stream()
                .map(permissionMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PermissionResponse findById(Long id) throws ResourceNotFoundException {
        PermissionEntity entity = permissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", id));
        return permissionMapper.toResponse(entity);
    }

    public PermissionResponse update(Long id, PermissionRequest request) throws ResourceNotFoundException, BusinessException {
        PermissionEntity entity = permissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", id));

        if (!entity.getCode().equals(request.getCode()) && permissionRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Permission code already exists: " + request.getCode());
        }

        entity.setCode(request.getCode());
        entity.setDescription(request.getDescription());
        PermissionEntity updated = permissionRepository.save(entity);
        return permissionMapper.toResponse(updated);
    }

    public void delete(Long id) throws ResourceNotFoundException {
        if (!permissionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Permission", id);
        }
        permissionRepository.deleteById(id);
    }

    /**
     * Sincroniza permisos desde las rutas del frontend
     * Crea los permisos que no existen y actualiza los existentes
     */
    public PermissionSyncResponse syncPermissions(List<PermissionSyncRequest.PermissionSyncItem> routePermissions) {
        List<PermissionEntity> dbPermissions = permissionRepository.findAll();
        java.util.Map<String, PermissionEntity> dbPermissionsMap = dbPermissions.stream()
                .collect(java.util.stream.Collectors.toMap(PermissionEntity::getCode, p -> p, (p1, p2) -> p1));

        int created = 0;
        int updated = 0;
        java.util.Set<String> routePermissionCodes = new java.util.HashSet<>();
        java.util.Set<String> processedCodes = new java.util.HashSet<>(); // Para evitar duplicados en la misma ejecución

        // Procesar permisos de rutas
        for (PermissionSyncRequest.PermissionSyncItem routePerm : routePermissions) {
            String code = routePerm.getCode();
            routePermissionCodes.add(code);
            
            // Evitar procesar el mismo código dos veces en la misma ejecución
            if (processedCodes.contains(code)) {
                continue;
            }
            processedCodes.add(code);
            
            // Buscar en BD usando findByCode para asegurar que tenemos la versión más actualizada
            PermissionEntity existing = permissionRepository.findByCode(code).orElse(null);
            
            if (existing == null) {
                // Verificar nuevamente antes de crear para evitar condiciones de carrera
                if (!permissionRepository.existsByCode(code)) {
                    // Crear nuevo permiso
                    PermissionEntity newPermission = PermissionEntity.builder()
                            .code(code)
                            .description(routePerm.getDescription() != null ? routePerm.getDescription() : routePerm.getName())
                            .module(routePerm.getModule())
                            .routePath(routePerm.getRoutePath())
                            .action(routePerm.getAction())
                            .build();
                    try {
                        permissionRepository.save(newPermission);
                        created++;
                        // Actualizar el mapa para futuras referencias en esta ejecución
                        dbPermissionsMap.put(code, newPermission);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // Si falla por duplicado, significa que se creó entre la verificación y el save
                        // Intentar obtener el existente y actualizarlo
                        existing = permissionRepository.findByCode(code).orElse(null);
                        if (existing != null) {
                            // Tratar como actualización en lugar de creación
                            updated++;
                        }
                    }
                } else {
                    // Existe ahora, obtenerlo y actualizarlo
                    existing = permissionRepository.findByCode(code).orElse(null);
                }
            }
            
            // Si existe (ya sea que lo encontramos inicialmente o después de un error), actualizarlo
            if (existing != null) {
                // Actualizar permiso existente si hay cambios
                boolean needsUpdate = false;
                String newDescription = routePerm.getDescription() != null ? routePerm.getDescription() : routePerm.getName();
                if (newDescription != null && (existing.getDescription() == null || !newDescription.equals(existing.getDescription()))) {
                    existing.setDescription(newDescription);
                    needsUpdate = true;
                }
                if (routePerm.getModule() != null && 
                    (existing.getModule() == null || !routePerm.getModule().equals(existing.getModule()))) {
                    existing.setModule(routePerm.getModule());
                    needsUpdate = true;
                }
                if (routePerm.getRoutePath() != null && 
                    (existing.getRoutePath() == null || !routePerm.getRoutePath().equals(existing.getRoutePath()))) {
                    existing.setRoutePath(routePerm.getRoutePath());
                    needsUpdate = true;
                }
                if (routePerm.getAction() != null && 
                    (existing.getAction() == null || !routePerm.getAction().equals(existing.getAction()))) {
                    existing.setAction(routePerm.getAction());
                    needsUpdate = true;
                }
                if (needsUpdate) {
                    permissionRepository.save(existing);
                    updated++;
                }
            }
        }

        // Encontrar permisos huérfanos (existen en BD pero no en rutas)
        List<PermissionResponse> orphaned = dbPermissions.stream()
                .filter(p -> !routePermissionCodes.contains(p.getCode()))
                .map(permissionMapper::toResponse)
                .collect(java.util.stream.Collectors.toList());

        // Encontrar permisos faltantes (deberían existir pero no se crearon por algún error)
        List<PermissionResponse> missing = routePermissions.stream()
                .filter(rp -> {
                    // Verificar si se creó en esta sincronización
                    return !dbPermissionsMap.containsKey(rp.getCode());
                })
                .map(rp -> PermissionResponse.builder()
                        .code(rp.getCode())
                        .description(rp.getDescription() != null ? rp.getDescription() : rp.getName())
                        .module(rp.getModule())
                        .routePath(rp.getRoutePath())
                        .action(rp.getAction())
                        .build())
                .collect(java.util.stream.Collectors.toList());

        int synced = routePermissions.size() - created - updated;

        return PermissionSyncResponse.builder()
                .totalInRoutes(routePermissions.size())
                .totalInDB(dbPermissions.size() + created)
                .created(created)
                .updated(updated)
                .synced(synced)
                .missing(missing)
                .orphaned(orphaned)
                .message(String.format("Sincronización completada: %d creados, %d actualizados, %d ya sincronizados", created, updated, synced))
                .build();
    }
}

