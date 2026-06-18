package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerAccountEntryVoidRequest {

    @NotBlank(message = "voidReason is required")
    @Size(max = 500)
    private String voidReason;
}
