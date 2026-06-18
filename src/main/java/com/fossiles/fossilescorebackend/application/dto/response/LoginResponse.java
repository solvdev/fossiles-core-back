package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String refreshToken;
    /** Access token TTL in milliseconds. */
    private Long expiresIn;
    private String type = "Bearer";
    private Long id;
    private String username;
    private String email;
    private String status;
}

