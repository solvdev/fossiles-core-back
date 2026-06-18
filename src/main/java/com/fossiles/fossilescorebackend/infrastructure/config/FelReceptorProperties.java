package com.fossiles.fossilescorebackend.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "fel.receptor")
public class FelReceptorProperties {
    private String url = "https://consultareceptores.feel.com.gt/rest/action";
    private String emisorCodigo = "";
    private String emisorClave = "";
    private boolean enabled = true;
}
