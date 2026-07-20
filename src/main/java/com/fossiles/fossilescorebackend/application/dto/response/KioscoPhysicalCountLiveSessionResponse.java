package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountLiveSessionResponse {
    private LocalDateTime serverTime;
    private List<KioscoPhysicalCountPresenceResponse> participants;
    private List<KioscoPhysicalCountItemSyncResponse> items;
}
