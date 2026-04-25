package com.fossiles.fossilescorebackend.application.mapper;

import com.fossiles.fossilescorebackend.application.dto.request.PermissionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PermissionResponse;
import com.fossiles.fossilescorebackend.domain.model.Permission;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PermissionMapper {

    public Permission toDomain(PermissionEntity entity) {
        if (entity == null) {
            return null;
        }
        return Permission.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .description(entity.getDescription())
                .build();
    }

    public List<Permission> toDomain(List<PermissionEntity> entities) {
        return entities == null ? List.of() : entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    public PermissionEntity toEntity(Permission domain) {
        if (domain == null) {
            return null;
        }
        return PermissionEntity.builder()
                .id(domain.getId())
                .code(domain.getCode())
                .description(domain.getDescription())
                .build();
    }

    public PermissionEntity toEntity(PermissionRequest request) {
        if (request == null) {
            return null;
        }
        return PermissionEntity.builder()
                .code(request.getCode())
                .description(request.getDescription())
                .build();
    }

    public PermissionResponse toResponse(PermissionEntity entity) {
        if (entity == null) {
            return null;
        }
        return PermissionResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .description(entity.getDescription())
                .module(entity.getModule())
                .routePath(entity.getRoutePath())
                .action(entity.getAction())
                .build();
    }

    public PermissionResponse toResponse(Permission domain) {
        if (domain == null) {
            return null;
        }
        return PermissionResponse.builder()
                .id(domain.getId())
                .code(domain.getCode())
                .description(domain.getDescription())
                .build();
    }
}

