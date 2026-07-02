package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoNotificationRecipientRequest {

    @NotBlank(message = "El nombre es obligatorio.")
    private String name;

    @NotBlank(message = "El correo es obligatorio.")
    private String email;
}
