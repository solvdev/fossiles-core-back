package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterization tests of LF receivables on the in-memory H2 database of src/test/resources/application.properties.
 * Each test rolls back. Tests named "CURRENT BEHAVIOR (bug)" pin today's behavior and must flip when it is fixed.
 */
@SpringBootTest
@Transactional
abstract class LfReceivablesH2TestBase {

    @MockitoBean
    SecurityUtil securityUtil;

    @Autowired
    private ApplicationContext context;

    LfReceivablesFixture fx;

    @BeforeEach
    void createFixture() {
        fx = new LfReceivablesFixture(context);
    }
}
