package com.fossiles.fossilescorebackend.application.mapper;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.domain.model.Employee;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EmployeeMapper {

    private final UserMapper userMapper;

    // Entity to Domain
    public Employee toDomain(EmployeeEntity entity) {
        if (entity == null) return null;
        
        return Employee.builder()
            .id(entity.getId())
            .firstName(entity.getFirstName())
            .lastName(entity.getLastName())
            .email(entity.getEmail())
            .phone(entity.getPhone())
            .dpi(entity.getDpi())
            .hireDate(entity.getHireDate())
            .position(entity.getPosition())
            .salary(entity.getSalary())
            .bankAccount(entity.getBankAccount())
            .paymentMethod(entity.getPaymentMethod())
            .igssDeduction(entity.getIgssDeduction())
            .quincenaBruta(entity.getQuincenaBruta())
            .quincenaNeta(entity.getQuincenaNeta())
            .departmentId(entity.getDepartmentId())
            .costCenterId(entity.getCostCenterId())
            .operationalUnitId(entity.getOperationalUnitId())
            .status(entity.getStatus())
            .createdAt(entity.getCreatedAt())
            .createdBy(entity.getCreatedBy())
            .updatedAt(entity.getUpdatedAt())
            .updatedBy(entity.getUpdatedBy())
            .user(entity.getUsers() != null && !entity.getUsers().isEmpty() 
                ? userMapper.toDomain(entity.getUsers().iterator().next()) 
                : null)
            .build();
    }

    // Domain to Entity
    public EmployeeEntity toEntity(Employee domain) {
        if (domain == null) return null;
        
        EmployeeEntity entity = EmployeeEntity.builder()
            .id(domain.getId())
            .firstName(domain.getFirstName())
            .lastName(domain.getLastName())
            .email(domain.getEmail())
            .phone(domain.getPhone())
            .dpi(domain.getDpi())
            .hireDate(domain.getHireDate())
            .position(domain.getPosition())
            .salary(domain.getSalary())
            .bankAccount(domain.getBankAccount())
            .paymentMethod(domain.getPaymentMethod())
            .igssDeduction(domain.getIgssDeduction())
            .quincenaBruta(domain.getQuincenaBruta())
            .quincenaNeta(domain.getQuincenaNeta())
            .departmentId(domain.getDepartmentId())
            .costCenterId(domain.getCostCenterId())
            .operationalUnitId(domain.getOperationalUnitId())
            .status(domain.getStatus())
            .createdBy(domain.getCreatedBy())
            .updatedBy(domain.getUpdatedBy())
            .build();
        
        if (domain.getUser() != null) {
            UserEntity userEntity = userMapper.toEntity(domain.getUser());
            entity.getUsers().add(userEntity);
        }
        
        return entity;
    }

    // Request to Domain
    public Employee toDomain(EmployeeRequest request) {
        if (request == null) return null;
        
        Employee.EmployeeBuilder builder = Employee.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(request.getEmail())
            .phone(request.getPhone())
            .dpi(request.getDpi())
            .hireDate(request.getHireDate())
            .position(request.getPosition())
            .salary(request.getSalary())
            .bankAccount(request.getBankAccount())
            .paymentMethod(request.getPaymentMethod())
            .igssDeduction(request.getIgssDeduction())
            .quincenaBruta(request.getQuincenaBruta())
            .quincenaNeta(request.getQuincenaNeta())
            .departmentId(request.getDepartmentId())
            .costCenterId(request.getCostCenterId())
            .operationalUnitId(request.getOperationalUnitId())
            .status(request.getStatus() != null ? request.getStatus() : "active");
        
        // Si viene userId, crear un objeto User con ese ID para asociación
        if (request.getUserId() != null) {
            builder.user(User.builder().id(request.getUserId()).build());
        }
        
        return builder.build();
    }

    // Domain to Response
    public EmployeeResponse toResponse(Employee domain) {
        if (domain == null) return null;
        
        UserResponse userResponse = domain.getUser() != null 
            ? userMapper.toResponse(domain.getUser()) 
            : null;
        
        return EmployeeResponse.builder()
            .id(domain.getId())
            .firstName(domain.getFirstName())
            .lastName(domain.getLastName())
            .email(domain.getEmail())
            .phone(domain.getPhone())
            .dpi(domain.getDpi())
            .hireDate(domain.getHireDate())
            .position(domain.getPosition())
            .salary(domain.getSalary())
            .bankAccount(domain.getBankAccount())
            .paymentMethod(domain.getPaymentMethod())
            .igssDeduction(domain.getIgssDeduction())
            .quincenaBruta(domain.getQuincenaBruta())
            .quincenaNeta(domain.getQuincenaNeta())
            .departmentId(domain.getDepartmentId())
            .costCenterId(domain.getCostCenterId())
            .operationalUnitId(domain.getOperationalUnitId())
            .status(domain.getStatus())
            .createdAt(domain.getCreatedAt())
            .createdBy(domain.getCreatedBy())
            .updatedAt(domain.getUpdatedAt())
            .updatedBy(domain.getUpdatedBy())
            .user(userResponse)
            .build();
    }

    // Entity to Response
    public EmployeeResponse toResponse(EmployeeEntity entity) {
        return toResponse(toDomain(entity));
    }
}

