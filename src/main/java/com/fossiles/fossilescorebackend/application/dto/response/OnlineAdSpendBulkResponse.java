package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code saved}: entradas con monto guardadas (nuevas o actualizadas). {@code deleted}: capturas existentes borradas. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendBulkResponse {
    private int saved;
    private int deleted;
}
