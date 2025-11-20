package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 10, message = "Code must not exceed 10 characters")
    private String code;

    @Size(max = 50, message = "Name must not exceed 50 characters")
    private String name;

    @Size(max = 10, message = "Symbol must not exceed 10 characters")
    private String symbol;
}

