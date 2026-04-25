package com.fossiles.fossilescorebackend.application.mapper;

import com.fossiles.fossilescorebackend.application.dto.request.UserRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CostCenterResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DepartmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OperationalUnitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.RoleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.domain.model.CostCenter;
import com.fossiles.fossilescorebackend.domain.model.Department;
import com.fossiles.fossilescorebackend.domain.model.OperationalUnit;
import com.fossiles.fossilescorebackend.domain.model.Role;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final RoleMapper roleMapper;

    // Entity to Domain
    public User toDomain(UserEntity entity) {
        if (entity == null) return null;
        
        Set<Role> roles = entity.getRoles() != null 
            ? entity.getRoles().stream()
                .map(roleMapper::toDomain)
                .collect(Collectors.toSet())
            : null;
        
        return User.builder()
            .id(entity.getId())
            .username(entity.getUsername())
            .email(entity.getEmail())
            .password(entity.getPassword())
            .status(entity.getStatus())
            .firstName(entity.getFirstName())
            .lastName(entity.getLastName())
            .profileImageUrl(entity.getProfileImageUrl())
            .department(toDepartmentDomain(entity.getDepartment()))
            .costCenter(toCostCenterDomain(entity.getCostCenter()))
            .operationalUnit(toOperationalUnitDomain(entity.getOperationalUnit()))
            .roles(roles)
            .build();
    }

    // Domain to Entity
    public UserEntity toEntity(User domain) {
        if (domain == null) return null;
        
        Set<RoleEntity> roles = domain.getRoles() != null
            ? domain.getRoles().stream()
                .map(roleMapper::toEntity)
                .collect(Collectors.toSet())
            : null;
        
        return UserEntity.builder()
            .id(domain.getId())
            .username(domain.getUsername())
            .email(domain.getEmail())
            .password(domain.getPassword())
            .status(domain.getStatus())
            .firstName(domain.getFirstName())
            .lastName(domain.getLastName())
            .profileImageUrl(domain.getProfileImageUrl())
            .department(toDepartmentEntity(domain.getDepartment()))
            .costCenter(toCostCenterEntity(domain.getCostCenter()))
            .operationalUnit(toOperationalUnitEntity(domain.getOperationalUnit()))
            .roles(roles)
            .build();
    }

    // Request to Domain
    public User toDomain(UserRequest request) {
        if (request == null) return null;
        
        return User.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .profileImageUrl(request.getProfileImageUrl())
            .password(request.getPassword())
            .status(request.getStatus() != null ? request.getStatus() : "active")
            .department(request.getDepartmentId() != null ? Department.builder().id(request.getDepartmentId()).build() : null)
            .costCenter(request.getCostCenterId() != null ? CostCenter.builder().id(request.getCostCenterId()).build() : null)
            .operationalUnit(request.getOperationalUnitId() != null ? OperationalUnit.builder().id(request.getOperationalUnitId()).build() : null)
            .build();
    }

    // Domain to Response
    public UserResponse toResponse(User domain) {
        if (domain == null) return null;
        
        Set<RoleResponse> roles = domain.getRoles() != null
            ? domain.getRoles().stream()
                .map(roleMapper::toResponse)
                .collect(Collectors.toSet())
            : null;
        
        return UserResponse.builder()
            .id(domain.getId())
            .username(domain.getUsername())
            .email(domain.getEmail())
            .status(domain.getStatus())
            .firstName(domain.getFirstName())
            .lastName(domain.getLastName())
            .profileImageUrl(domain.getProfileImageUrl())
            .department(toDepartmentResponse(domain.getDepartment()))
            .costCenter(toCostCenterResponse(domain.getCostCenter()))
            .operationalUnit(toOperationalUnitResponse(domain.getOperationalUnit()))
            .roles(roles)
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }

    // Entity to Response
    public UserResponse toResponse(UserEntity entity) {
        return toResponse(toDomain(entity));
    }

    private Department toDepartmentDomain(DepartmentEntity entity) {
        if (entity == null) return null;
        return Department.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private DepartmentEntity toDepartmentEntity(Department department) {
        if (department == null || department.getId() == null) return null;
        DepartmentEntity entity = new DepartmentEntity();
        entity.setId(department.getId());
        return entity;
    }

    private DepartmentResponse toDepartmentResponse(Department department) {
        if (department == null) return null;
        return DepartmentResponse.builder()
                .id(department.getId())
                .code(department.getCode())
                .name(department.getName())
                .description(department.getDescription())
                .build();
    }

    private CostCenter toCostCenterDomain(CostCenterEntity entity) {
        if (entity == null) return null;
        return CostCenter.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private CostCenterEntity toCostCenterEntity(CostCenter costCenter) {
        if (costCenter == null || costCenter.getId() == null) return null;
        CostCenterEntity entity = new CostCenterEntity();
        entity.setId(costCenter.getId());
        return entity;
    }

    private CostCenterResponse toCostCenterResponse(CostCenter costCenter) {
        if (costCenter == null) return null;
        return CostCenterResponse.builder()
                .id(costCenter.getId())
                .code(costCenter.getCode())
                .name(costCenter.getName())
                .description(costCenter.getDescription())
                .build();
    }

    private OperationalUnit toOperationalUnitDomain(OperationalUnitEntity entity) {
        if (entity == null) return null;
        return OperationalUnit.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private OperationalUnitEntity toOperationalUnitEntity(OperationalUnit operationalUnit) {
        if (operationalUnit == null || operationalUnit.getId() == null) return null;
        OperationalUnitEntity entity = new OperationalUnitEntity();
        entity.setId(operationalUnit.getId());
        return entity;
    }

    private OperationalUnitResponse toOperationalUnitResponse(OperationalUnit operationalUnit) {
        if (operationalUnit == null) return null;
        return OperationalUnitResponse.builder()
                .id(operationalUnit.getId())
                .code(operationalUnit.getCode())
                .name(operationalUnit.getName())
                .description(operationalUnit.getDescription())
                .build();
    }

}

