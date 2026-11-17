package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.service.LeatherRecipeCatalog;
import com.fossiles.fossilescorebackend.application.service.OnlineSalesReadCache;
import com.fossiles.fossilescorebackend.application.service.ProductionCenterCache;
import com.fossiles.fossilescorebackend.application.service.UserActivityService;
import com.fossiles.fossilescorebackend.infrastructure.config.SecurityConfig;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VersionController.class)
@Import(SecurityConfig.class)
class VersionControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserActivityService userActivityService;

    @MockitoBean
    private OnlineSalesReadCache onlineSalesReadCache;

    @MockitoBean
    private ProductionCenterCache productionCenterCache;

    @MockitoBean
    private LeatherRecipeCatalog leatherRecipeCatalog;

    @Test
    void publicVersionIsReachableWithoutAuthAndFallsBack() throws Exception {
        MvcResult result = mvc.perform(get("/api/public/version").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactly("version", "commit", "buildTime");
        assertThat(body.get("version").asText()).isEqualTo("dev");
        assertThat(body.get("commit").asText()).isEqualTo("unknown");
        assertThat(body.get("buildTime").asText()).isEqualTo("1970-01-01T00:00:00Z");
    }

    @Test
    void protectedEndpointStaysClosed() throws Exception {
        int status = mvc.perform(get("/api/users")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @Test
    void injectedBuildInfoStripsTheVAndKeepsTheRcSuffix() throws Exception {
        Properties buildEntries = new Properties();
        buildEntries.setProperty("version", "v1.269.1-rc.2");
        buildEntries.setProperty("time", "2026-11-17T19:00:00Z");
        buildEntries.setProperty("commit", "ABC1234DEADBEEF");

        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> builds = mock(ObjectProvider.class);
        when(builds.getIfAvailable()).thenReturn(new BuildProperties(buildEntries));
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gits = mock(ObjectProvider.class);
        when(gits.getIfAvailable()).thenReturn(null);

        MockMvc standalone = MockMvcBuilders.standaloneSetup(new VersionController(builds, gits)).build();
        MvcResult result = standalone.perform(get("/api/public/version"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactly("version", "commit", "buildTime");
        assertThat(body.get("version").asText()).isEqualTo("1.269.1-rc.2");
        assertThat(body.get("commit").asText()).isEqualTo("abc1234");
        assertThat(body.get("buildTime").asText()).isEqualTo("2026-11-17T19:00:00Z");
    }

    @Test
    void gitPropertiesFillTheCommitWhenBuildInfoHasNone() throws Exception {
        Properties buildEntries = new Properties();
        buildEntries.setProperty("version", "1.4.2");
        buildEntries.setProperty("time", "2026-11-17T19:00:00Z");
        Properties gitEntries = new Properties();
        gitEntries.setProperty("commit.id.abbrev", "abc1234");

        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> builds = mock(ObjectProvider.class);
        when(builds.getIfAvailable()).thenReturn(new BuildProperties(buildEntries));
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gits = mock(ObjectProvider.class);
        when(gits.getIfAvailable()).thenReturn(new GitProperties(gitEntries));

        MockMvc standalone = MockMvcBuilders.standaloneSetup(new VersionController(builds, gits)).build();
        MvcResult result = standalone.perform(get("/api/public/version")).andExpect(status().isOk()).andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("version").asText()).isEqualTo("1.4.2");
        assertThat(body.get("commit").asText()).isEqualTo("abc1234");
        assertThat(body.get("buildTime").asText()).isEqualTo("2026-11-17T19:00:00Z");
        assertThat(fieldNames(body)).containsExactly("version", "commit", "buildTime");
    }

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            names.add(fields.next());
        }
        return names;
    }
}
