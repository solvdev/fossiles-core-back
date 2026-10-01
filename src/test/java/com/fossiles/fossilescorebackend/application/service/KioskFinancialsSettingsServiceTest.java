package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSettingsRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ajuste del método del punto de equilibrio: por defecto RATES, persiste hasta que se cambie, y valida. */
class KioskFinancialsSettingsServiceTest {

    private JdbcTemplate jdbc;
    private KioskFinancialsAccessGuard guard;
    private KioskFinancialsSettingsService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:set_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        guard = Mockito.mock(KioskFinancialsAccessGuard.class);
        Mockito.when(guard.currentUserId()).thenReturn(5L);
        service = new KioskFinancialsSettingsService(jdbc, guard);
    }

    private void createTable() {
        jdbc.execute("CREATE TABLE kiosk_financial_setting (setting_key VARCHAR(60) PRIMARY KEY, "
                + "setting_value VARCHAR(255) NOT NULL, updated_by BIGINT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
    }

    @Test
    void withoutTheTableTheModeIsRatesAndSavingExplainsTheMigration() throws Exception {
        assertThat(service.breakEvenMode()).isEqualTo("RATES");
        assertThat(service.get().getPersisted()).isFalse();
        assertThatThrownBy(() -> service.update(KioskFinancialsSettingsRequest.builder().breakEvenMode("FLAT").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("migration-kiosk-financials-settings.sql");
    }

    @Test
    void emptyTableOrUnknownValueFallsBackToRates() {
        createTable();
        assertThat(service.breakEvenMode()).isEqualTo("RATES");
        jdbc.update("INSERT INTO kiosk_financial_setting (setting_key, setting_value) VALUES ('BREAK_EVEN_MODE', 'raro')");
        assertThat(service.breakEvenMode()).isEqualTo("RATES");
    }

    @Test
    void savedModeStaysUntilItIsChangedAgainAndCarriesAuthorAndFlatRate() throws Exception {
        createTable();

        var saved = service.update(KioskFinancialsSettingsRequest.builder().breakEvenMode("flat").build());

        assertThat(saved.getBreakEvenMode()).isEqualTo("FLAT");
        assertThat(saved.getFlatBreakEvenRate()).isEqualByComparingTo("0.27");
        assertThat(saved.getPersisted()).isTrue();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(service.breakEvenMode()).isEqualTo("FLAT"); // lo que leen todos los reportes
        assertThat(jdbc.queryForObject("SELECT updated_by FROM kiosk_financial_setting", Long.class)).isEqualTo(5L);

        service.update(KioskFinancialsSettingsRequest.builder().breakEvenMode("RATES").build());
        assertThat(service.breakEvenMode()).isEqualTo("RATES");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kiosk_financial_setting", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidOrMissingModeIsRejectedAndPermissionsAreChecked() throws Exception {
        createTable();
        assertThatThrownBy(() -> service.update(KioskFinancialsSettingsRequest.builder().breakEvenMode("otro").build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(new KioskFinancialsSettingsRequest()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(null)).isInstanceOf(BusinessException.class);

        Mockito.doThrow(new BusinessException("sin permiso")).when(guard).assertCanEdit();
        assertThatThrownBy(() -> service.update(KioskFinancialsSettingsRequest.builder().breakEvenMode("FLAT").build()))
                .hasMessage("sin permiso");
        assertThat(service.breakEvenMode()).isEqualTo("RATES"); // nada se guardó
    }
}
