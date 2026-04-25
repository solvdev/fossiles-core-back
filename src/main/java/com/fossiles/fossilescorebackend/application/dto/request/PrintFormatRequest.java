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
public class PrintFormatRequest {
    @NotBlank(message = "Document type is required")
    @Size(max = 50, message = "Document type must not exceed 50 characters")
    private String documentType;

    @NotBlank(message = "Format name is required")
    @Size(max = 100, message = "Format name must not exceed 100 characters")
    private String formatName;

    @Size(max = 500, message = "Template path must not exceed 500 characters")
    private String templatePath;

    @Size(max = 20, message = "Paper size must not exceed 20 characters")
    private String paperSize;

    @Size(max = 50, message = "Margins must not exceed 50 characters")
    private String margins;

    private String header;

    private String footer;

    @Size(max = 500, message = "Logo path must not exceed 500 characters")
    private String logoPath;

    private Boolean isDefault;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}

