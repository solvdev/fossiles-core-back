package com.fossiles.fossilescorebackend.application.dto.request;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExcelCommitRequest {

    /** true = reemplaza lo que ya exista para (sitio, año-mes); false = rechaza si ya hay datos. */
    private boolean replaceExisting;
    private List<FileCommit> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileCommit {
        private String fileName;
        private String sha256;
        private Integer year;
        private Integer month;
        /** excelName -> {siteId} (mapeo manual) o {create:{name,...}} (nuevo sitio histórico). Sin entrada = alias existente. */
        private Map<String, SiteMappingEntry> siteMapping;
        /** issueId -> número (reemplaza la celda) o "IGNORE". */
        private Map<String, Object> resolutions;
        private KioskExcelDataDto data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteMappingEntry {
        private Long siteId;
        private CreateSite create;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateSite {
        private String name;
        /** ACTIVE | CLOSED (por defecto CLOSED). */
        private String status;
        private LocalDate closedOn;
    }
}
