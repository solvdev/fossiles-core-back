package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskCustomerProfileResponse {
    private Long id;
    private String taxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
}
