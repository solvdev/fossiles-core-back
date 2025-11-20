package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSeriesRequest {
    @Size(max = 50, message = "Document type must not exceed 50 characters")
    private String docType;

    @Size(max = 20, message = "Prefix must not exceed 20 characters")
    private String prefix;

    @NotNull(message = "Current number is required")
    private Integer currentNumber;
}

