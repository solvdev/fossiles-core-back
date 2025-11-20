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
public class ContactRequest {
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 30, message = "Phone must not exceed 30 characters")
    private String phone;

    @Email(message = "Email must be valid")
    @Size(max = 120, message = "Email must not exceed 120 characters")
    private String email;

    @Size(max = 30, message = "NIT must not exceed 30 characters")
    private String nit;

    @Size(max = 30, message = "Type must not exceed 30 characters")
    private String type; // supplier | customer | other
}

