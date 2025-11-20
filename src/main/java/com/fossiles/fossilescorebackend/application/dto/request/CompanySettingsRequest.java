package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySettingsRequest {
    @Size(max = 200, message = "Company name must not exceed 200 characters")
    private String companyName;

    @Size(max = 30, message = "NIT must not exceed 30 characters")
    private String nit;

    @Size(max = 10, message = "Currency default must not exceed 10 characters")
    private String currencyDefault;

    @Size(max = 50, message = "Timezone must not exceed 50 characters")
    private String timezone;
}

