package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.BankAccountDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequeLayoutDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ConfigResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.LayoutField;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentMethodDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ChequeLayoutEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BankAccountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ChequeLayoutRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollRunLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PaymentMethodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuración de Pagos a proveedores: cuentas bancarias (con su chequera y formato de cheque), formas de pago y el
 * formato de cada cheque. También lleva la numeración de la chequera de cada cuenta.
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentConfigService {

    static final String PAYABLES_START_KEY = "accounting.payables.start_date";
    /** Si nadie fijó la fecha de inicio, cuentas por pagar mira los últimos días (no todo el historial). */
    static final int DEFAULT_LOOKBACK_DAYS = 60;

    private static final Set<String> FIELD_KEYS = Set.of("DATE", "AMOUNT", "PAYEE", "WORDS", "CONCEPT", "NUMBER", "LEGEND");
    private static final Set<String> FONTS = Set.of("TIMES", "ARIAL", "COURIER");
    private static final Pattern CHEQUE_NUMBER = Pattern.compile("^(\\d+)(.*)$");

    private final ChequeLayoutRepository layoutRepository;
    private final BankAccountRepository accountRepository;
    private final PaymentMethodRepository methodRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final PayrollRunLineRepository payrollLineRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final SupplierPaymentAccessGuard guard;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ConfigResponse getConfig() throws BusinessException {
        guard.assertCanView();
        return buildConfig();
    }

    ConfigResponse buildConfig() throws BusinessException {
        Map<Long, ChequeLayoutEntity> layouts = new HashMap<>();
        layoutRepository.findAll().forEach(l -> layouts.put(l.getId(), l));
        List<BankAccountDto> accounts = new ArrayList<>();
        for (BankAccountEntity a : accountRepository.findAllByOrderByNameAsc()) {
            ChequeLayoutEntity layout = a.getChequeLayoutId() != null ? layouts.get(a.getChequeLayoutId()) : null;
            accounts.add(new BankAccountDto(a.getId(), a.getName(), a.getAccountNumber(), a.getLedgerCode(),
                    a.getLedgerName(), a.getFirstChequeNumber(), Boolean.TRUE.equals(a.getSharesPayrollChecks()),
                    a.getChequeLayoutId(), layout != null ? layout.getName() : null, Boolean.TRUE.equals(a.getActive()),
                    suggestNextCheque(a)));
        }
        List<ChequeLayoutDto> layoutDtos = layoutRepository.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
        List<PaymentMethodDto> methods = methodRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(SupplierPaymentConfigService::toDto).toList();
        return new ConfigResponse(accounts, layoutDtos, methods, payablesStartDate(), guard.canConfigure());
    }

    /** Desde cuándo se muestran las órdenes recibidas en cuentas por pagar. */
    LocalDate payablesStartDate() {
        return systemConfigRepository.findByConfigKey(PAYABLES_START_KEY)
                .map(SystemConfigEntity::getConfigValue)
                .map(value -> {
                    try {
                        return LocalDate.parse(value);
                    } catch (DateTimeParseException e) {
                        return null;
                    }
                })
                .orElseGet(() -> GuatemalaDateTime.today().minusDays(DEFAULT_LOOKBACK_DAYS));
    }

    // ------------------------------------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------------------------------------

    @Transactional
    public ConfigResponse savePayablesStart(LocalDate date) throws BusinessException {
        guard.assertCanConfigure();
        if (date == null) {
            throw new BusinessException("Indique la fecha desde la que se muestran las cuentas por pagar.");
        }
        SystemConfigEntity config = systemConfigRepository.findByConfigKey(PAYABLES_START_KEY)
                .orElseGet(() -> SystemConfigEntity.builder().configKey(PAYABLES_START_KEY)
                        .description("Cuentas por pagar: fecha desde la que se muestran las órdenes recibidas").build());
        config.setConfigValue(date.toString());
        systemConfigRepository.save(config);
        return buildConfig();
    }

    @Transactional
    public ConfigResponse saveLayout(Long id, ChequeLayoutDto dto) throws BusinessException {
        Long userId = guard.assertCanConfigure();
        validate(dto);
        ChequeLayoutEntity entity = id == null ? ChequeLayoutEntity.builder().createdBy(userId).build() : findLayout(id);
        entity.setName(dto.name().trim());
        entity.setWidthMm(BigDecimal.valueOf(dto.widthMm()));
        entity.setHeightMm(BigDecimal.valueOf(dto.heightMm()));
        entity.setFontFamily(dto.fontFamily());
        entity.setFieldsJson(writeFields(dto.fields()));
        entity.setActive(dto.active());
        entity.setUpdatedBy(userId);
        layoutRepository.save(entity);
        return buildConfig();
    }

    @Transactional
    public ConfigResponse saveAccount(Long id, BankAccountDto dto) throws BusinessException {
        Long userId = guard.assertCanConfigure();
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BusinessException("Indique el nombre de la cuenta.");
        }
        if (dto.ledgerCode() == null || dto.ledgerCode().isBlank()) {
            throw new BusinessException("Indique la cuenta contable de la cuenta bancaria.");
        }
        if (dto.chequeLayoutId() != null) {
            findLayout(dto.chequeLayoutId());
        }
        BankAccountEntity entity = id == null ? BankAccountEntity.builder().createdBy(userId).build()
                : accountRepository.findById(id).orElseThrow(() -> new BusinessException("La cuenta no existe."));
        entity.setName(dto.name().trim());
        entity.setAccountNumber(blankToNull(dto.accountNumber()));
        entity.setLedgerCode(dto.ledgerCode().trim());
        entity.setLedgerName(blankToNull(dto.ledgerName()));
        String first = blankToNull(dto.firstChequeNumber());
        if (first != null && !CHEQUE_NUMBER.matcher(PayrollRunService.compactCheque(first)).matches()) {
            throw new BusinessException("El primer número de cheque debe empezar con dígitos.");
        }
        entity.setFirstChequeNumber(first == null ? null : PayrollRunService.compactCheque(first));
        entity.setSharesPayrollChecks(dto.sharesPayrollChecks());
        entity.setChequeLayoutId(dto.chequeLayoutId());
        entity.setActive(dto.active());
        entity.setUpdatedBy(userId);
        accountRepository.save(entity);
        return buildConfig();
    }

    @Transactional
    public ConfigResponse saveMethod(Long id, PaymentMethodDto dto) throws BusinessException {
        guard.assertCanConfigure();
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BusinessException("Indique el nombre de la forma de pago.");
        }
        PaymentMethodEntity entity = id == null ? new PaymentMethodEntity()
                : methodRepository.findById(id).orElseThrow(() -> new BusinessException("La forma de pago no existe."));
        entity.setName(dto.name().trim());
        entity.setRequiresBank(dto.requiresBank());
        // Un cheque siempre sale de una cuenta y lleva su número.
        entity.setPrintsCheque(dto.printsCheque());
        entity.setRequiresBank(dto.requiresBank() || dto.printsCheque());
        entity.setRequiresReference(dto.requiresReference() || dto.printsCheque());
        entity.setSortOrder(dto.sortOrder());
        entity.setActive(dto.active());
        try {
            methodRepository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException("Ya existe una forma de pago con ese nombre.");
        }
        return buildConfig();
    }

    // ------------------------------------------------------------------------------------------------
    // Formato del cheque
    // ------------------------------------------------------------------------------------------------

    ChequeLayoutEntity findLayout(Long id) throws BusinessException {
        return layoutRepository.findById(id).orElseThrow(() -> new BusinessException("El formato de cheque no existe."));
    }

    ChequeLayoutDto toDto(ChequeLayoutEntity e) {
        return new ChequeLayoutDto(e.getId(), e.getName(), e.getWidthMm().doubleValue(), e.getHeightMm().doubleValue(),
                e.getFontFamily(), readFields(e.getFieldsJson()), Boolean.TRUE.equals(e.getActive()));
    }

    private static PaymentMethodDto toDto(PaymentMethodEntity e) {
        return new PaymentMethodDto(e.getId(), e.getName(), Boolean.TRUE.equals(e.getRequiresBank()),
                Boolean.TRUE.equals(e.getRequiresReference()), Boolean.TRUE.equals(e.getPrintsCheque()),
                e.getSortOrder() == null ? 0 : e.getSortOrder(), Boolean.TRUE.equals(e.getActive()));
    }

    private List<LayoutField> readFields(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<LayoutField>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeFields(List<LayoutField> fields) throws BusinessException {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new BusinessException("No se pudo guardar el formato del cheque.");
        }
    }

    static void validate(ChequeLayoutDto dto) throws BusinessException {
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BusinessException("Indique el nombre del formato.");
        }
        if (dto.widthMm() < 50 || dto.widthMm() > 400 || dto.heightMm() < 30 || dto.heightMm() > 300) {
            throw new BusinessException("El tamaño del cheque no es válido (ancho 50 a 400 mm, alto 30 a 300 mm).");
        }
        if (dto.fontFamily() == null || !FONTS.contains(dto.fontFamily())) {
            throw new BusinessException("El tipo de letra debe ser TIMES, ARIAL o COURIER.");
        }
        if (dto.fields() == null || dto.fields().isEmpty()) {
            throw new BusinessException("El formato debe tener al menos un dato.");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (LayoutField f : dto.fields()) {
            if (f.key() == null || !FIELD_KEYS.contains(f.key()) || !seen.add(f.key())) {
                throw new BusinessException("Dato de cheque no válido o repetido: " + f.key() + ".");
            }
            if (!"LEFT".equals(f.side()) && !"RIGHT".equals(f.side())) {
                throw new BusinessException("El lado de «" + f.key() + "» debe ser izquierda o derecha.");
            }
            if (f.top() < 0 || f.top() > dto.heightMm() || f.edge() < 0 || f.edge() > dto.widthMm()) {
                throw new BusinessException("«" + f.key() + "» queda fuera del cheque.");
            }
            if (f.size() < 5 || f.size() > 24) {
                throw new BusinessException("El tamaño de letra de «" + f.key() + "» debe estar entre 5 y 24 puntos.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Numeración de la chequera
    // ------------------------------------------------------------------------------------------------

    /** Números de cheque ya usados en la chequera de la cuenta, anulados incluidos (un cheque anulado no se reutiliza). */
    List<String> usedChequeNumbers(BankAccountEntity account) {
        Set<Long> chequeMethods = new java.util.HashSet<>();
        methodRepository.findAll().stream().filter(m -> Boolean.TRUE.equals(m.getPrintsCheque()))
                .forEach(m -> chequeMethods.add(m.getId()));
        List<String> used = new ArrayList<>();
        for (SupplierPaymentEntity p : paymentRepository.findByBankAccountId(account.getId())) {
            if (p.getReference() != null && chequeMethods.contains(p.getPaymentMethodId())) {
                used.add(p.getReference());
            }
        }
        if (Boolean.TRUE.equals(account.getSharesPayrollChecks())) {
            used.addAll(payrollLineRepository.findChequeNumbersOfAllOtherRuns(-1L));
        }
        return used;
    }

    /** Siguiente número de la chequera: el más alto usado + 1 (con su ancho y sufijo), o el primero de la cuenta. */
    String suggestNextCheque(BankAccountEntity account) {
        BigInteger best = null;
        int width = 0;
        String suffix = "";
        for (String number : usedChequeNumbers(account)) {
            Matcher matcher = CHEQUE_NUMBER.matcher(PayrollRunService.compactCheque(number));
            if (!matcher.matches()) {
                continue;
            }
            BigInteger value = new BigInteger(matcher.group(1));
            if (best == null || value.compareTo(best) > 0) {
                best = value;
                width = matcher.group(1).length();
                suffix = matcher.group(2);
            }
        }
        if (best == null) {
            return account.getFirstChequeNumber();
        }
        return String.format("%0" + width + "d", best.add(BigInteger.ONE)) + suffix;
    }

    boolean isChequeUsed(BankAccountEntity account, String number) {
        String key = PayrollRunService.chequeKey(number);
        return usedChequeNumbers(account).stream().anyMatch(n -> PayrollRunService.chequeKey(n).equals(key));
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
