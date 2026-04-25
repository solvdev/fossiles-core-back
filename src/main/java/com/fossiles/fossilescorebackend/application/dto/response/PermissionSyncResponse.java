package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionSyncResponse {
    private int totalInRoutes;
    private int totalInDB;
    private int created;
    private int updated;
    private int synced;
    private List<PermissionResponse> missing;
    private List<PermissionResponse> orphaned;
    private String message;
}

