package com.fossiles.fossilescorebackend.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kiosk-pos.deposit-report")
public class KioskPosDepositReportProperties {

    private String bankAccount = "061-0016829-2";
    private String accountName = "CUEROGLAM, S.A.";
    private String bankName = "Banco GT Continental";
}
