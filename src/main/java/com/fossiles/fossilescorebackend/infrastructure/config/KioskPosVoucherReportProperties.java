package com.fossiles.fossilescorebackend.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kiosk-pos.voucher-report")
public class KioskPosVoucherReportProperties {

    private String defaultCardBrand = "VISA";
}
