package com.fossiles.fossilescorebackend.infrastructure.controller;

import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versión desplegada del back. Los valores se inyectan al compilar (build.gradle → build-info.properties,
 * con APP_VERSION / APP_COMMIT que fija scripts/compute-version.js); nunca se escriben a mano.
 * Sin esas variables (compilación local) la versión es "dev".
 *
 * <p>{@code /api/version} es la ruta oficial; {@code /api/public/version} es el alias que ya consume el front.
 */
@RestController
public class VersionController {

    private final ObjectProvider<BuildProperties> buildProperties;

    public VersionController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping({"/api/version", "/api/public/version"})
    public ResponseEntity<Map<String, String>> version() {
        BuildProperties build = buildProperties.getIfAvailable();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("version", build != null && build.getVersion() != null ? build.getVersion() : "dev");
        body.put("commit", build != null && build.get("commit") != null ? build.get("commit") : "unknown");
        body.put("buildTime", build != null && build.getTime() != null ? build.getTime().toString() : null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
