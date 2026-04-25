package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
public class EmailConfigRequest {
    @NotBlank(message = "SMTP host is required")
    @Size(max = 200, message = "SMTP host must not exceed 200 characters")
    private String smtpHost;

    @NotNull(message = "SMTP port is required")
    private Integer smtpPort;

    @NotBlank(message = "Username is required")
    @Size(max = 200, message = "Username must not exceed 200 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(max = 500, message = "Password must not exceed 500 characters")
    private String password;

    @NotBlank(message = "From email is required")
    @Email(message = "From email must be a valid email address")
    @Size(max = 200, message = "From email must not exceed 200 characters")
    private String fromEmail;

    @Size(max = 200, message = "From name must not exceed 200 characters")
    private String fromName;

    private Boolean useTls;

    private Boolean useSsl;

    private Boolean isActive;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}

