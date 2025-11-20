package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySettingsResponse {
    private Long id;
    private String companyName;
    private String nit;
    private String currencyDefault;
    private String timezone;
}

