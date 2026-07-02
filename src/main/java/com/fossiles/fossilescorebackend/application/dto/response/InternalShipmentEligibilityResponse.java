package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentEligibilityResponse {
    private Long employeeId;
    private String month;
    private boolean eligible;
    private String message;
    private Long existingRequestId;
    private String existingRequestStatus;
}
