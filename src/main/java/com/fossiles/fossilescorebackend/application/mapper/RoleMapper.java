package com.fossiles.fossilescorebackend.application.mapper;

import com.fossiles.fossilescorebackend.application.dto.request.RoleRequest;
import com.fossiles.fossilescorebackend.application.dto.response.RoleResponse;
import com.fossiles.fossilescorebackend.domain.model.Permission;
import com.fossiles.fossilescorebackend.domain.model.Role;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleMapper {

    private final PermissionMapper permissionMapper;

    public Role toDomain(RoleEntity entity) {
        if (entity == null) {
            return null;
        }
        Set<Permission> permissions = entity.getPermissions() != null
                ? entity.getPermissions().stream()
                .map(permissionMapper::toDomain)
                .collect(Collectors.toSet())
                : null;
        return Role.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .permissions(permissions)
                .build();
    }

    public RoleEntity toEntity(Role domain) {
        if (domain == null) {
            return null;
        }
        Set<PermissionEntity> permissions = domain.getPermissions() != null
                ? domain.getPermissions().stream()
                .map(permissionMapper::toEntity)
                .collect(Collectors.toSet())
                : null;
        return RoleEntity.builder()
                .id(domain.getId())
                .name(domain.getName())
                .description(domain.getDescription())
                .permissions(permissions)
                .build();
    }

    public RoleEntity toEntity(RoleRequest request) {
        if (request == null) {
            return null;
        }
        return RoleEntity.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
    }

    public RoleResponse toResponse(RoleEntity entity) {
        if (entity == null) {
            return null;
        }
        return RoleResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .permissions(entity.getPermissions() != null
                        ? entity.getPermissions().stream()
                        .map(permissionMapper::toResponse)
                        .collect(Collectors.toSet())
                        : null)
                .build();
    }

    public RoleResponse toResponse(Role domain) {
        if (domain == null) {
            return null;
        }
        return RoleResponse.builder()
                .id(domain.getId())
                .name(domain.getName())
                .description(domain.getDescription())
                .permissions(domain.getPermissions() != null
                        ? domain.getPermissions().stream()
                        .map(permissionMapper::toResponse)
                        .collect(Collectors.toSet())
                        : null)
                .build();
    }
}

