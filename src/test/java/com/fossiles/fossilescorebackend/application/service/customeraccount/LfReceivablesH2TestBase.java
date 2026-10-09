package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of the LF receivables ledger on the in-memory H2 database
 * from src/test/resources/application.properties. Each test rolls back.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lf_receivables_char;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1"
})
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
