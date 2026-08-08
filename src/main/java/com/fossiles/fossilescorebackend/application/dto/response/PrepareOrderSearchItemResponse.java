package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrepareOrderSearchItemResponse {
    private Long id;
    private String code;
    private String customerName;
    private String sellerName;
    private String status;
    private String orderType;
    private String vendorShipmentNumber;
}
