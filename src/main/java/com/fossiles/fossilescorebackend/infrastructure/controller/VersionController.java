package com.fossiles.fossilescorebackend.infrastructure.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class VersionController {

    static final String FALLBACK_VERSION = "dev";
    static final String FALLBACK_COMMIT = "unknown";
    static final Instant FALLBACK_BUILD_TIME = Instant.EPOCH;

    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("version", versionValue());
        body.put("commit", commitValue());
        body.put("buildTime", buildTimeValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    private String versionValue() {
        try {
            BuildProperties build = buildProperties.getIfAvailable();
            if (build == null) {
                return FALLBACK_VERSION;
            }
            return normalizeVersion(build.getVersion());
        } catch (RuntimeException ex) {
            return FALLBACK_VERSION;
        }
    }

    private String commitValue() {
        try {
            String raw = null;
            BuildProperties build = buildProperties.getIfAvailable();
            if (build != null) {
                raw = build.get("commit");
            }
            if (raw == null || raw.isBlank()) {
                GitProperties git = gitProperties.getIfAvailable();
                if (git != null) {
                    raw = git.getShortCommitId();
                }
            }
            return normalizeCommit(raw);
        } catch (RuntimeException ex) {
            return FALLBACK_COMMIT;
        }
    }

    private String buildTimeValue() {
        try {
            BuildProperties build = buildProperties.getIfAvailable();
            Instant time = build == null ? null : build.getTime();
            if (time == null) {
                time = FALLBACK_BUILD_TIME;
            }
            return DateTimeFormatter.ISO_INSTANT.format(time);
        } catch (RuntimeException ex) {
            return DateTimeFormatter.ISO_INSTANT.format(FALLBACK_BUILD_TIME);
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) {
            return FALLBACK_VERSION;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return FALLBACK_VERSION;
        }
        if (trimmed.length() > 1 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? FALLBACK_VERSION : trimmed;
    }

    private static String normalizeCommit(String raw) {
        if (raw == null) {
            return FALLBACK_COMMIT;
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("[0-9a-fA-F]{7,64}")) {
            return FALLBACK_COMMIT;
        }
        return trimmed.substring(0, 7).toLowerCase(Locale.ROOT);
    }
}
