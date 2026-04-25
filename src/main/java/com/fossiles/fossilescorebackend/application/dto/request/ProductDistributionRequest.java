package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDistributionRequest {
    
    @NotNull(message = "Distribution date is required")
    private LocalDate distributionDate;
    
    private String description;
    
    private String status; // DRAFT, CONFIRMED, SENT, COMPLETED
}

