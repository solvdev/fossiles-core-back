package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fossiles.fossilescorebackend.application.dto.response.CostCenterResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DepartmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OperationalUnitResponse;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String status;
    private String firstName;
    private String lastName;
    private String profileImageUrl;
    private DepartmentResponse department;
    private CostCenterResponse costCenter;
    private OperationalUnitResponse operationalUnit;
    private Set<RoleResponse> roles;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

