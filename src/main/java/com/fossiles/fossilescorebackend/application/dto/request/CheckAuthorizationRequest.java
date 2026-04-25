package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckAuthorizationRequest {
    @NotBlank(message = "Check number is required")
    @Size(max = 100, message = "Check number must not exceed 100 characters")
    private String checkNumber;

    @NotNull(message = "Check amount is required")
    @Positive(message = "Check amount must be positive")
    private BigDecimal checkAmount;

    private LocalDate checkIssueDate;
}

