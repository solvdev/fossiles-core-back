package com.fossiles.fossilescorebackend.application.mapper;

import com.fossiles.fossilescorebackend.application.dto.request.UserRequest;
import com.fossiles.fossilescorebackend.application.dto.response.RoleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.domain.model.Role;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class UserMapper {

    // Entity to Domain
    public User toDomain(UserEntity entity) {
        if (entity == null) return null;
        
        Set<Role> roles = entity.getRoles() != null 
            ? entity.getRoles().stream()
                .map(this::roleEntityToDomain)
                .collect(Collectors.toSet())
            : null;
        
        return User.builder()
            .id(entity.getId())
            .username(entity.getUsername())
            .email(entity.getEmail())
            .password(entity.getPassword())
            .status(entity.getStatus())
            .roles(roles)
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    // Domain to Entity
    public UserEntity toEntity(User domain) {
        if (domain == null) return null;
        
        Set<RoleEntity> roles = domain.getRoles() != null
            ? domain.getRoles().stream()
                .map(this::roleDomainToEntity)
                .collect(Collectors.toSet())
            : null;
        
        return UserEntity.builder()
            .id(domain.getId())
            .username(domain.getUsername())
            .email(domain.getEmail())
            .password(domain.getPassword())
            .status(domain.getStatus())
            .roles(roles)
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }

    // Request to Domain
    public User toDomain(UserRequest request) {
        if (request == null) return null;
        
        return User.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .password(request.getPassword())
            .status(request.getStatus() != null ? request.getStatus() : "active")
            .build();
    }

    // Domain to Response
    public UserResponse toResponse(User domain) {
        if (domain == null) return null;
        
        Set<RoleResponse> roles = domain.getRoles() != null
            ? domain.getRoles().stream()
                .map(this::roleToResponse)
                .collect(Collectors.toSet())
            : null;
        
        return UserResponse.builder()
            .id(domain.getId())
            .username(domain.getUsername())
            .email(domain.getEmail())
            .status(domain.getStatus())
            .roles(roles)
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }

    // Entity to Response
    public UserResponse toResponse(UserEntity entity) {
        return toResponse(toDomain(entity));
    }

    // Helper methods for Role
    private Role roleEntityToDomain(RoleEntity entity) {
        if (entity == null) return null;
        return Role.builder()
            .id(entity.getId())
            .name(entity.getName())
            .description(entity.getDescription())
            .build();
    }

    private RoleEntity roleDomainToEntity(Role domain) {
        if (domain == null) return null;
        return RoleEntity.builder()
            .id(domain.getId())
            .name(domain.getName())
            .description(domain.getDescription())
            .build();
    }

    private RoleResponse roleToResponse(Role domain) {
        if (domain == null) return null;
        return RoleResponse.builder()
            .id(domain.getId())
            .name(domain.getName())
            .description(domain.getDescription())
            .build();
    }
}

