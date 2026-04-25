package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderRequest {
    @Size(max = 50, message = "Code must not exceed 50 characters")
    private String code;

    @NotNull(message = "Supplier ID is required")
    private Long supplierId;

    private LocalDate orderDate;

    @Size(max = 30, message = "Status must not exceed 30 characters")
    private String status;

    private List<Long> materialRequestIds;

    @Size(max = 2000, message = "Observations must not exceed 2000 characters")
    private String observations;

    private Long costCenterId;

    @NotNull(message = "Items list is required")
    @Valid
    private List<PurchaseOrderItemRequest> items;
}

