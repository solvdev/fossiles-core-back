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
public class SupplierRequest {
    /** PV seguido de números. Vacío al crear = el siguiente disponible. */
    @Size(max = 10, message = "El código no debe pasar de 10 caracteres")
    private String code;

    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 30, message = "NIT must not exceed 30 characters")
    private String nit;

    @Size(max = 30, message = "Phone must not exceed 30 characters")
    private String phone;

    @Email(message = "Email must be valid")
    @Size(max = 120, message = "Email must not exceed 120 characters")
    private String email;

    @Size(max = 200, message = "Address must not exceed 200 characters")
    private String address;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    @Size(max = 200, message = "Payee name must not exceed 200 characters")
    private String payeeName;

    @jakarta.validation.constraints.Min(value = 0, message = "Credit days must not be negative")
    @jakarta.validation.constraints.Max(value = 365, message = "Credit days must not exceed 365")
    private Integer creditDays;
}

