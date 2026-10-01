package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSettingsRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSettingsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskPnlCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Ajustes globales de Finanzas por kiosco (tabla clave/valor {@code kiosk_financial_setting}), leídos y escritos con
 * SQL directo. Hoy: método del punto de equilibrio. Si la tabla no existe (migración sin ejecutar) se usa el valor por
 * defecto y no se permite guardar, con un mensaje claro.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KioskFinancialsSettingsService {

    static final String TABLE = "kiosk_financial_setting";
    static final String KEY_BREAK_EVEN_MODE = "BREAK_EVEN_MODE";

    private final JdbcTemplate jdbc;
    private final KioskFinancialsAccessGuard guard;

    @Transactional(readOnly = true)
    public KioskFinancialsSettingsResponse get() throws BusinessException {
        guard.assertCanView();
        boolean persisted = tableExists();
        return KioskFinancialsSettingsResponse.builder()
                .breakEvenMode(breakEvenMode())
                .flatBreakEvenRate(KioskPnlCalculator.FLAT_VARIABLE_RATE)
                .persisted(persisted)
                .updatedAt(persisted ? updatedAt(KEY_BREAK_EVEN_MODE) : null)
                .build();
    }

    /** Método del punto de equilibrio vigente: RATES (por defecto) o FLAT. Nunca falla: ante cualquier duda, RATES. */
    @Transactional(readOnly = true)
    public String breakEvenMode() {
        if (!tableExists()) {
            return KioskFinancialsReportService.BREAK_EVEN_RATES;
        }
        List<String> values = jdbc.queryForList(
                "SELECT setting_value FROM " + TABLE + " WHERE setting_key = ?", String.class, KEY_BREAK_EVEN_MODE);
        String value = values.isEmpty() ? null : values.get(0);
        return isValidMode(value) ? value.trim().toUpperCase(Locale.ROOT) : KioskFinancialsReportService.BREAK_EVEN_RATES;
    }

    @Transactional(rollbackFor = Exception.class)
    public KioskFinancialsSettingsResponse update(KioskFinancialsSettingsRequest request) throws BusinessException {
        guard.assertCanEdit();
        if (request == null || request.getBreakEvenMode() == null) {
            throw new BusinessException("Indique el ajuste a cambiar (breakEvenMode).");
        }
        if (!isValidMode(request.getBreakEvenMode())) {
            throw new BusinessException("breakEvenMode inválido: '" + request.getBreakEvenMode() + "' (use RATES o FLAT).");
        }
        if (!tableExists()) {
            throw new BusinessException("No se puede guardar: falta ejecutar scripts/migration-kiosk-financials-settings.sql.");
        }
        upsert(KEY_BREAK_EVEN_MODE, request.getBreakEvenMode().trim().toUpperCase(Locale.ROOT), guard.currentUserId());
        return get();
    }

    private void upsert(String key, String value, Long userId) {
        int updated = jdbc.update("UPDATE " + TABLE + " SET setting_value = ?, updated_by = ?, updated_at = ? "
                + "WHERE setting_key = ?", value, userId, Timestamp.valueOf(LocalDateTime.now()), key);
        if (updated == 0) {
            jdbc.update("INSERT INTO " + TABLE + " (setting_key, setting_value, updated_by, updated_at) VALUES (?, ?, ?, ?)",
                    key, value, userId, Timestamp.valueOf(LocalDateTime.now()));
        }
    }

    private LocalDateTime updatedAt(String key) {
        List<Timestamp> values = jdbc.queryForList(
                "SELECT updated_at FROM " + TABLE + " WHERE setting_key = ?", Timestamp.class, key);
        return values.isEmpty() || values.get(0) == null ? null : values.get(0).toLocalDateTime();
    }

    private static boolean isValidMode(String mode) {
        if (mode == null) {
            return false;
        }
        String m = mode.trim().toUpperCase(Locale.ROOT);
        return KioskFinancialsReportService.BREAK_EVEN_RATES.equals(m) || KioskFinancialsReportService.BREAK_EVEN_FLAT.equals(m);
    }

    /** Se consulta antes para no ejecutar SQL inválido: en PostgreSQL abortaría la transacción en curso. */
    private boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?", Integer.class, TABLE);
        return n != null && n > 0;
    }
}
