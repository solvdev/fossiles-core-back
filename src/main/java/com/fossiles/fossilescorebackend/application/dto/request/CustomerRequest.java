package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerRequest {
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 30, message = "NIT must not exceed 30 characters")
    private String nit;

    @Size(max = 30, message = "Legacy code must not exceed 30 characters")
    private String legacyCode;

    @Size(max = 30, message = "Phone must not exceed 30 characters")
    private String phone;

    @Email(message = "Email must be valid")
    @Size(max = 120, message = "Email must not exceed 120 characters")
    private String email;

    @Size(max = 200, message = "Address must not exceed 200 characters")
    private String address;

    @Size(max = 10, message = "Route location code must not exceed 10 characters")
    private String routeLocationCode;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

