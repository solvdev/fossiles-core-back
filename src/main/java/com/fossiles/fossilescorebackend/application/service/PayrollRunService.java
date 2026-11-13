package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollChequeAssignRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollPayDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeApplicationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeApplicationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollRunLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollRunRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Planilla quincenal por grupo de pago: BORRADOR (se recalcula libremente) → APROBADA (rebaja los cargos descontados)
 * → PAGADA (cheques numerados y partida contable). Una planilla en borrador o aprobada se puede anular; una pagada no.
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class PayrollRunService {

    public static final String STATUS_DRAFT = "BORRADOR";
    public static final String STATUS_APPROVED = "APROBADA";
    public static final String STATUS_PAID = "PAGADA";
    public static final String STATUS_CANCELLED = "ANULADA";
    /** Banco de la chequera de los formatos de voucher actuales. */
    static final String DEFAULT_BANK = "BANCO G Y T CONTINENTAL";
    /** Cuántos días antes de la fecha programada se puede adelantar el pago. */
    static final int MAX_DAYS_EARLY = 10;

    private static final Pattern CHEQUE_NUMBER = Pattern.compile("^(\\d+)(.*)$");

    private final PayrollRunRepository runRepository;
    private final PayrollRunLineRepository lineRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeChargeRepository chargeRepository;
    private final EmployeeChargeApplicationRepository applicationRepository;
    private final PayrollParameterService parameterService;
    private final AccountingService accountingService;
    private final PayrollAccessGuard accessGuard;

    /**
     * Quiénes entran en una planilla: los de su grupo que no cobraron ya el período en otra planilla, y los de otro
     * grupo cuya planilla del mismo período ya se aprobó sin ellos (cambiaron de grupo a mitad de quincena).
     */
    private record Eligibility(List<EmployeeEntity> payable, long paidElsewhere, long fromOtherGroup) {}

    @Transactional(readOnly = true)
    public List<PayrollRunResponse> list() {
        accessGuard.assertCanViewRuns();
        return runRepository.findAllByOrderByScheduledDateDescIdDesc().stream()
                .map(run -> toResponse(run, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayrollRunResponse get(Long id) throws ResourceNotFoundException {
        accessGuard.assertCanViewRuns();
        PayrollRunEntity run = runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", id));
        return toDetail(run, lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id));
    }

    /** Días de pago desde el mes anterior, para poder preparar una planilla atrasada, con la corrida que ya tengan. */
    @Transactional(readOnly = true)
    public List<PayrollPayDayResponse> payDays(String payGroup, int count) throws BusinessException {
        accessGuard.assertCanViewRuns();
        List<String> groups = payGroup == null || payGroup.isBlank()
                ? List.of(PayrollRules.GROUP_PLANTA, PayrollRules.GROUP_ADMINISTRACION)
                : List.of(validGroup(payGroup));
        LocalDate from = GuatemalaDateTime.today().minusMonths(1).withDayOfMonth(1);
        int perGroup = Math.max(1, Math.min(count, 24));
        List<PayrollPayDayResponse> result = new ArrayList<>();
        for (String group : groups) {
            for (PayrollCalendar.PayDay day : PayrollCalendar.upcoming(group, from, perGroup)) {
                PayrollRunEntity run = runRepository.findActive(group, day.scheduledDate()).orElse(null);
                result.add(PayrollPayDayResponse.builder()
                        .payGroup(group)
                        .scheduledDate(day.scheduledDate())
                        .payDate(run == null ? day.payDate() : run.getPayDate())
                        .periodStart(day.periodStart())
                        .periodEnd(day.periodEnd())
                        .runId(run == null ? null : run.getId())
                        .runStatus(run == null ? null : run.getStatus())
                        .build());
            }
        }
        result.sort(Comparator.comparing(PayrollPayDayResponse::getScheduledDate)
                .thenComparing(PayrollPayDayResponse::getPayGroup));
        return result;
    }

    public PayrollRunResponse create(PayrollRunCreateRequest request) throws BusinessException {
        accessGuard.assertCanCreateRuns();
        String group = validGroup(request.getPayGroup());
        if (!PayrollCalendar.isScheduledDate(group, request.getScheduledDate())) {
            throw new BusinessException(PayrollRules.GROUP_PLANTA.equals(group)
                    ? "La planta cobra el 5 y el 20 de cada mes."
                    : "Administración y kioscos cobran el 15 y el último día del mes.");
        }
        var active = runRepository.findActive(group, request.getScheduledDate());
        if (active.isPresent()) {
            throw new BusinessException("Ya existe la planilla #" + active.get().getId() + " para esa fecha ("
                    + active.get().getStatus().toLowerCase() + ").");
        }
        PayrollCalendar.PayDay day = PayrollCalendar.payDay(group, request.getScheduledDate());
        LocalDate payDate = request.getPayDate() == null ? day.payDate() : validPayDate(day.scheduledDate(), request.getPayDate());
        PayrollRunEntity run = runRepository.save(newRun(day, payDate, blankToDefault(request.getBankName()),
                blankToNull(request.getNotes()), accessGuard.currentUserId()));
        return toDetail(run, calculate(run));
    }

    /**
     * Borrador que prepara la tarea programada antes del día de pago. No pide usuario y nunca vuelve a crear una
     * planilla para una fecha que ya tuvo una, aunque la hayan anulado.
     */
    public Optional<Long> createScheduledDraft(String payGroup, LocalDate scheduledDate) throws BusinessException {
        if (runRepository.existsByPayGroupAndScheduledDate(payGroup, scheduledDate)) {
            return Optional.empty();
        }
        PayrollCalendar.PayDay day = PayrollCalendar.payDay(payGroup, scheduledDate);
        PayrollRunEntity run = runRepository.save(newRun(day, day.payDate(), DEFAULT_BANK,
                "Preparada automáticamente; revísela y apruébela.", null));
        calculate(run);
        return Optional.of(run.getId());
    }

    public PayrollRunResponse recalculate(Long id) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanCreateRuns();
        PayrollRunEntity run = lock(id);
        requireStatus(run, STATUS_DRAFT, "Solo se recalcula una planilla en borrador.");
        return toDetail(run, calculate(run));
    }

    /** Cambia el día en que se paga (feria local, cierre del banco). En borrador recalcula, porque define qué cargos entran. */
    public PayrollRunResponse changePayDate(Long id, LocalDate payDate) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanViewRuns();
        PayrollRunEntity run = lock(id);
        if (STATUS_DRAFT.equals(run.getStatus())) {
            accessGuard.assertCanCreateRuns();
        } else if (STATUS_APPROVED.equals(run.getStatus())) {
            accessGuard.assertCanApproveRuns();
        } else {
            throw new BusinessException("La fecha de pago solo se cambia en una planilla en borrador o aprobada.");
        }
        run.setPayDate(validPayDate(run.getScheduledDate(), payDate));
        runRepository.save(run);
        List<PayrollRunLineEntity> lines = STATUS_DRAFT.equals(run.getStatus())
                ? calculate(run) : lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id);
        return toDetail(run, lines);
    }

    /** Fija los montos y rebaja el saldo de los cargos descontados. */
    public PayrollRunResponse approve(Long id) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveRuns();
        PayrollRunEntity run = lock(id);
        requireStatus(run, STATUS_DRAFT, "Solo se aprueba una planilla en borrador.");
        List<PayrollRunLineEntity> lines = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id);
        if (lines.isEmpty()) {
            throw new BusinessException("La planilla no tiene colaboradores.");
        }
        List<EmployeeChargeApplicationEntity> applications = applicationsOf(lines);
        // Los cargos se bloquean antes de cualquier otra lectura, para restar sobre el saldo vigente y no sobre uno
        // que otra aprobación ya cambió.
        Map<Long, EmployeeChargeEntity> charges = lockCharges(applications);
        if (isStale(run)) {
            throw new BusinessException("Cambiaron fichas, cargos o parámetros después del cálculo; recalcule la planilla antes de aprobarla.");
        }
        long withoutMethod = lines.stream().filter(line -> isBlank(line.getPaymentMethod())).count();
        if (withoutMethod > 0) {
            throw new BusinessException(people(withoutMethod) + " sin forma de pago válida en la ficha; complétela y recalcule la planilla.");
        }
        for (EmployeeChargeApplicationEntity application : applications) {
            EmployeeChargeEntity charge = charges.get(application.getChargeId());
            if (charge == null) {
                throw new BusinessException("Un cargo descontado ya no existe; recalcule la planilla.");
            }
            boolean deductible = EmployeeChargeService.SETTLEMENT_DEDUCTION.equals(charge.getSettlementMethod())
                    && ("PENDIENTE".equals(charge.getStatus()) || "PARCIAL".equals(charge.getStatus()));
            if (!deductible || charge.getBalance().compareTo(application.getAmount()) < 0) {
                throw new BusinessException("El cargo «" + charge.getDescription() + "» cambió después del cálculo; recalcule la planilla.");
            }
            charge.setBalance(charge.getBalance().subtract(application.getAmount()));
            charge.setStatus(charge.getBalance().signum() == 0 ? "PAGADO" : "PARCIAL");
            chargeRepository.save(charge);
        }
        run.setStatus(STATUS_APPROVED);
        run.setApprovedAt(GuatemalaDateTime.now());
        run.setApprovedBy(accessGuard.currentUserId());
        return toDetail(runRepository.save(run), lines);
    }

    /** Numera los cheques en orden alfabético a partir del primero de la chequera. Se puede repetir antes de pagar. */
    public PayrollRunResponse assignCheques(Long id, PayrollChequeAssignRequest request)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveRuns();
        PayrollRunEntity run = lock(id);
        requireStatus(run, STATUS_APPROVED, "Los cheques se numeran cuando la planilla está aprobada.");
        Matcher matcher = CHEQUE_NUMBER.matcher(compactCheque(request.getFirstChequeNumber()));
        if (!matcher.matches()) {
            throw new BusinessException("El número de cheque debe empezar con dígitos.");
        }
        String digits = matcher.group(1);
        String suffix = matcher.group(2);
        BigInteger next = new BigInteger(digits);
        List<PayrollRunLineEntity> lines = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id);
        Map<Long, String> numbers = new LinkedHashMap<>();
        for (PayrollRunLineEntity line : lines) {
            if (paysByCheque(line)) {
                String number = String.format("%0" + digits.length() + "d", next) + suffix;
                if (number.length() > 30) {
                    throw new BusinessException("El número de cheque es demasiado largo.");
                }
                numbers.put(line.getId(), number);
                next = next.add(BigInteger.ONE);
            }
        }
        if (!numbers.isEmpty()) {
            // Se compara por valor y sufijo: «099645530/gcf» es el mismo cheque que «99645530/GCF».
            Map<String, Object[]> used = new HashMap<>();
            for (Object[] row : lineRepository.findChequesOfOtherActiveRuns(id)) {
                used.putIfAbsent(chequeKey((String) row[0]), row);
            }
            String detail = numbers.values().stream()
                    .map(number -> used.get(chequeKey(number)))
                    .filter(Objects::nonNull)
                    .limit(5)
                    .map(row -> row[0] + " (planilla #" + row[1] + ")")
                    .collect(Collectors.joining(", "));
            if (!detail.isEmpty()) {
                throw new BusinessException("Esos números ya se usaron: " + detail + ". Empiece en otro número de la chequera.");
            }
        }
        for (PayrollRunLineEntity line : lines) {
            line.setChequeNumber(numbers.get(line.getId()));
        }
        lineRepository.saveAll(lines);
        if (request.getBankName() != null && !request.getBankName().isBlank()) {
            run.setBankName(request.getBankName().trim());
        }
        return toDetail(runRepository.save(run), lines);
    }

    /** Registra que la planilla se pagó y genera su partida contable. */
    public PayrollRunResponse pay(Long id) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveRuns();
        PayrollRunEntity run = lock(id);
        requireStatus(run, STATUS_APPROVED, "Solo se paga una planilla aprobada.");
        List<PayrollRunLineEntity> lines = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id);
        long missingCheques = lines.stream()
                .filter(line -> paysByCheque(line) && isBlank(line.getChequeNumber()))
                .count();
        if (missingCheques > 0) {
            throw new BusinessException("Faltan " + missingCheques + " números de cheque; asígnelos antes de registrar el pago.");
        }
        run.setStatus(STATUS_PAID);
        run.setPaidAt(GuatemalaDateTime.now());
        run.setPaidBy(accessGuard.currentUserId());
        runRepository.save(run);
        accountingService.postPayrollRun(run, lines);
        return toDetail(run, lines);
    }

    /** Libera lo reservado; si ya estaba aprobada, devuelve el saldo a los cargos descontados. */
    public PayrollRunResponse annul(Long id, String reason) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveRuns();
        PayrollRunEntity run = lock(id);
        if (!STATUS_DRAFT.equals(run.getStatus()) && !STATUS_APPROVED.equals(run.getStatus())) {
            throw new BusinessException(STATUS_PAID.equals(run.getStatus())
                    ? "Una planilla pagada no se anula; registre el ajuste en la siguiente."
                    : "La planilla ya está anulada.");
        }
        List<PayrollRunLineEntity> lines = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(id);
        List<EmployeeChargeApplicationEntity> applications = applicationsOf(lines);
        if (STATUS_APPROVED.equals(run.getStatus())) {
            Map<Long, EmployeeChargeEntity> charges = lockCharges(applications);
            for (EmployeeChargeApplicationEntity application : applications) {
                EmployeeChargeEntity charge = charges.get(application.getChargeId());
                if (charge == null) {
                    continue;
                }
                charge.setBalance(charge.getBalance().add(application.getAmount()));
                charge.setStatus(charge.getBalance().compareTo(charge.getAmount()) >= 0 ? "PENDIENTE" : "PARCIAL");
                chargeRepository.save(charge);
            }
        }
        if (!applications.isEmpty()) {
            applicationRepository.deleteAllInBatch(applications);
        }
        run.setStatus(STATUS_CANCELLED);
        run.setCancelledAt(GuatemalaDateTime.now());
        run.setCancelledBy(accessGuard.currentUserId());
        run.setCancellationReason(reason == null ? null : reason.trim());
        return toDetail(runRepository.save(run), lines);
    }

    /** Rehace las líneas de un borrador con la ficha, los parámetros y los cargos vigentes. */
    private List<PayrollRunLineEntity> calculate(PayrollRunEntity run) throws BusinessException {
        List<PayrollRunLineEntity> previous = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(run.getId());
        if (!previous.isEmpty()) {
            applicationRepository.deleteByRunLineIdIn(previous.stream().map(PayrollRunLineEntity::getId).toList());
            lineRepository.deleteAllInBatch(previous);
        }
        PayrollParameters parameters = parameterService.effectiveAt(run.getPeriodEnd());
        List<EmployeeEntity> employees = eligibility(run).payable();
        List<EmployeeChargeEntity> deductible = employees.isEmpty() ? List.of()
                : fifo(chargeRepository.findDeductibleForUpdate(employees.stream().map(EmployeeEntity::getId).toList(), run.getPayDate()));
        Map<Long, BigDecimal> reserved = reservedByOtherDrafts(deductible, run.getId());
        Map<Long, List<EmployeeChargeEntity>> chargesByEmployee = deductible.stream()
                .collect(Collectors.groupingBy(EmployeeChargeEntity::getEmployeeId));

        List<PayrollRunLineEntity> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (EmployeeEntity employee : employees) {
            List<EmployeeChargeEntity> charges = chargesByEmployee.getOrDefault(employee.getId(), List.of());
            List<BigDecimal> available = new ArrayList<>();
            BigDecimal pending = BigDecimal.ZERO;
            for (EmployeeChargeEntity charge : charges) {
                BigDecimal free = charge.getBalance().subtract(reserved.get(charge.getId()));
                free = free.signum() > 0 ? free : BigDecimal.ZERO;
                available.add(free);
                pending = pending.add(free);
            }
            PayrollCalculator.Result result = PayrollCalculator.calculate(new PayrollCalculator.Input(
                    employee.getEmploymentType(), employee.getSalary(), employee.getBonification(),
                    employee.getHireDate(), employee.getTerminationDate(),
                    run.getPeriodStart(), run.getPeriodEnd(), pending), parameters);
            if (result.daysPaid().signum() == 0) {
                continue;
            }
            PayrollRunLineEntity line = lineRepository.save(PayrollRunLineEntity.builder()
                    .runId(run.getId())
                    .employeeId(employee.getId())
                    .employeeName(EmployeeChargeService.fullName(employee))
                    .position(employee.getPosition())
                    .departmentId(employee.getDepartmentId())
                    .costCenterId(employee.getCostCenterId())
                    .employmentType(employee.getEmploymentType())
                    // Variantes como «Cheque» o «Efectivo» se guardan en su forma estándar; una que no se reconoce
                    // queda vacía y frena la aprobación.
                    .paymentMethod(PayrollRules.canonicalPaymentMethod(employee.getPaymentMethod()))
                    .bankAccount(employee.getBankAccount())
                    .monthlySalary(employee.getSalary())
                    .daysPaid(result.daysPaid())
                    .baseSalary(result.baseSalary())
                    .bonification(result.bonification())
                    .igssEmployee(result.igssEmployee())
                    .isr(result.isr())
                    .opiDiscount(result.opiDiscount())
                    .otherDeductions(result.otherDeductions())
                    .netPay(result.netPay())
                    .igssEmployer(result.igssEmployer())
                    .irtra(result.irtra())
                    .intecap(result.intecap())
                    .build());
            // El descuento se reparte del cargo más antiguo al más reciente.
            BigDecimal remaining = result.opiDiscount();
            for (int i = 0; i < charges.size() && remaining.signum() > 0; i++) {
                BigDecimal amount = remaining.min(available.get(i));
                if (amount.signum() <= 0) {
                    continue;
                }
                applicationRepository.save(EmployeeChargeApplicationEntity.builder()
                        .chargeId(charges.get(i).getId())
                        .runLineId(line.getId())
                        .amount(amount)
                        .build());
                remaining = remaining.subtract(amount);
            }
            lines.add(line);
            gross = gross.add(result.gross());
            deductions = deductions.add(result.deductions());
            net = net.add(result.netPay());
        }
        run.setTotalGross(gross);
        run.setTotalDeductions(deductions);
        run.setTotalNet(net);
        run.setCalculatedAt(GuatemalaDateTime.now());
        run.setCalculationHash(fingerprint(run, employees, deductible, reserved, parameters));
        runRepository.save(run);
        return lines;
    }

    private Eligibility eligibility(PayrollRunEntity run) {
        Set<Long> paidElsewhere = new HashSet<>(lineRepository.findEmployeesPaidInOtherRuns(
                run.getId(), run.getPeriodStart(), run.getPeriodEnd()));
        Set<String> closedGroups = new HashSet<>(runRepository.findGroupsClosedForPeriod(
                run.getId(), run.getPeriodStart(), run.getPeriodEnd()));
        List<EmployeeEntity> payable = new ArrayList<>();
        long skipped = 0;
        long fromOtherGroup = 0;
        for (EmployeeEntity employee : employeeRepository.findAll()) {
            if (!isPayable(employee, run.getPeriodStart(), run.getPeriodEnd())) {
                continue;
            }
            boolean ownGroup = run.getPayGroup().equals(employee.getPayGroup());
            if (!ownGroup && !closedGroups.contains(employee.getPayGroup())) {
                continue;
            }
            if (paidElsewhere.contains(employee.getId())) {
                if (ownGroup) {
                    skipped++;
                }
                continue;
            }
            if (!ownGroup) {
                fromOtherGroup++;
            }
            payable.add(employee);
        }
        payable.sort(Comparator.comparing(EmployeeChargeService::fullName));
        return new Eligibility(payable, skipped, fromOtherGroup);
    }

    /** Vuelve a leer lo que entra al cálculo y lo compara con la huella guardada al calcular. */
    private boolean isStale(PayrollRunEntity run) {
        if (run.getCalculationHash() == null) {
            return true;
        }
        PayrollParameters parameters;
        try {
            parameters = parameterService.effectiveAt(run.getPeriodEnd());
        } catch (BusinessException ex) {
            return true;
        }
        List<EmployeeEntity> employees = eligibility(run).payable();
        List<EmployeeChargeEntity> deductible = employees.isEmpty() ? List.of()
                : chargeRepository.findDeductible(employees.stream().map(EmployeeEntity::getId).toList(), run.getPayDate());
        Map<Long, BigDecimal> reserved = reservedByOtherDrafts(deductible, run.getId());
        return !run.getCalculationHash().equals(fingerprint(run, employees, deductible, reserved, parameters));
    }

    static String fingerprint(PayrollRunEntity run, List<EmployeeEntity> employees, List<EmployeeChargeEntity> charges,
                              Map<Long, BigDecimal> reserved, PayrollParameters parameters) {
        StringBuilder text = new StringBuilder()
                .append(run.getPayGroup()).append('|').append(run.getPeriodStart()).append('|')
                .append(run.getPeriodEnd()).append('|').append(run.getPayDate()).append('\n')
                .append(parameters).append('\n');
        employees.stream().sorted(Comparator.comparing(EmployeeEntity::getId)).forEach(e -> text
                .append(e.getId()).append('|').append(plain(e.getSalary())).append('|').append(plain(e.getBonification()))
                .append('|').append(e.getEmploymentType()).append('|').append(e.getPaymentMethod())
                .append('|').append(e.getBankAccount()).append('|').append(e.getHireDate())
                .append('|').append(e.getTerminationDate()).append('|').append(e.getStatus())
                .append('|').append(e.getPosition()).append('|').append(EmployeeChargeService.fullName(e))
                .append('|').append(e.getDepartmentId()).append('|').append(e.getCostCenterId()).append('\n'));
        // Lo reservado por otros borradores también cuenta: si otro borrador se anula, este puede descontar más.
        charges.stream().sorted(Comparator.comparing(EmployeeChargeEntity::getId)).forEach(c -> text
                .append(c.getId()).append('|').append(plain(c.getBalance())).append('|').append(c.getSettlementMethod())
                .append('|').append(c.getStatus()).append('|').append(c.getChargedOn())
                .append('|').append(plain(reserved.get(c.getId()))).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<Long, BigDecimal> reservedByOtherDrafts(List<EmployeeChargeEntity> charges, Long runId) {
        Map<Long, BigDecimal> reserved = new HashMap<>();
        for (EmployeeChargeEntity charge : charges) {
            reserved.put(charge.getId(), applicationRepository.reservedByOtherDrafts(charge.getId(), runId));
        }
        return reserved;
    }

    /** Los cargos se bloquean por id; el descuento sigue del más antiguo al más reciente. */
    private static List<EmployeeChargeEntity> fifo(List<EmployeeChargeEntity> charges) {
        List<EmployeeChargeEntity> ordered = new ArrayList<>(charges);
        ordered.sort(Comparator.comparing(EmployeeChargeEntity::getChargedOn).thenComparing(EmployeeChargeEntity::getId));
        return ordered;
    }

    private Map<Long, EmployeeChargeEntity> lockCharges(List<EmployeeChargeApplicationEntity> applications) {
        List<Long> ids = applications.stream().map(EmployeeChargeApplicationEntity::getChargeId).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return chargeRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(EmployeeChargeEntity::getId, Function.identity()));
    }

    /** Activo o dado de baja dentro del período, con salario y contratado antes de que termine. */
    static boolean isPayable(EmployeeEntity employee, LocalDate periodStart, LocalDate periodEnd) {
        if (employee.getSalary() == null || employee.getSalary().signum() <= 0) {
            return false;
        }
        if (employee.getHireDate() != null && employee.getHireDate().isAfter(periodEnd)) {
            return false;
        }
        LocalDate termination = employee.getTerminationDate();
        if (termination != null) {
            return !termination.isBefore(periodStart);
        }
        return "active".equalsIgnoreCase(employee.getStatus());
    }

    /** Lo que conviene revisar en la ficha antes de aprobar o pagar. */
    private List<String> warnings(PayrollRunEntity run, List<PayrollRunLineEntity> lines) {
        List<String> warnings = new ArrayList<>();
        long withoutSalary = employeeRepository.findAll().stream()
                .filter(e -> run.getPayGroup().equals(e.getPayGroup()) && "active".equalsIgnoreCase(e.getStatus()))
                .filter(e -> e.getSalary() == null || e.getSalary().signum() <= 0)
                .count();
        if (withoutSalary > 0) {
            warnings.add("Activos del grupo sin salario en la ficha, que no entran en la planilla: " + withoutSalary);
        }
        if (STATUS_DRAFT.equals(run.getStatus())) {
            Eligibility eligibility = eligibility(run);
            if (eligibility.paidElsewhere() > 0) {
                warnings.add("Ya cobraron este período en otra planilla por un cambio de grupo y no entran: " + eligibility.paidElsewhere());
            }
            if (eligibility.fromOtherGroup() > 0) {
                warnings.add("Entran de otro grupo porque su planilla de este período ya se aprobó sin ellos: " + eligibility.fromOtherGroup());
            }
        }
        long withoutMethod = lines.stream().filter(line -> isBlank(line.getPaymentMethod())).count();
        if (withoutMethod > 0) {
            warnings.add("Sin forma de pago, que impide aprobar: " + withoutMethod);
        }
        long transferWithoutAccount = lines.stream()
                .filter(line -> PayrollRules.PAYMENT_TRANSFER.equals(line.getPaymentMethod()) && isBlank(line.getBankAccount()))
                .count();
        if (transferWithoutAccount > 0) {
            warnings.add("Cobran por transferencia sin número de cuenta: " + transferWithoutAccount);
        }
        long zeroBonification = lines.stream()
                .filter(line -> PayrollRules.EMPLOYMENT_PLANILLA.equals(line.getEmploymentType()))
                .filter(line -> line.getBonification().signum() == 0)
                .count();
        if (zeroBonification > 0) {
            warnings.add("De planilla con bonificación 0 en la ficha: " + zeroBonification);
        }
        long withoutDepartment = lines.stream().filter(line -> line.getDepartmentId() == null).count();
        if (withoutDepartment > 0) {
            warnings.add("Sin departamento en la ficha: " + withoutDepartment);
        }
        // Una cuenta cambiada o una persona nueva desde la planilla anterior se revisan antes de pagar.
        runRepository.findFirstByPayGroupAndScheduledDateBeforeAndStatusNotOrderByScheduledDateDesc(
                run.getPayGroup(), run.getScheduledDate(), STATUS_CANCELLED).ifPresent(previous -> {
            Map<Long, PayrollRunLineEntity> before = lineRepository.findByRunIdOrderByEmployeeNameAscIdAsc(previous.getId())
                    .stream().collect(Collectors.toMap(PayrollRunLineEntity::getEmployeeId, Function.identity(), (a, b) -> a));
            long added = lines.stream().filter(line -> !before.containsKey(line.getEmployeeId())).count();
            long changed = lines.stream()
                    .filter(line -> before.containsKey(line.getEmployeeId()))
                    .filter(line -> {
                        PayrollRunLineEntity old = before.get(line.getEmployeeId());
                        return !sameText(line.getBankAccount(), old.getBankAccount())
                                || !sameText(line.getPaymentMethod(), old.getPaymentMethod());
                    })
                    .count();
            if (added > 0) {
                warnings.add("No estaban en la planilla anterior (#" + previous.getId() + "): " + added);
            }
            if (changed > 0) {
                warnings.add("Cambiaron de cuenta o de forma de pago desde la planilla anterior (#" + previous.getId() + "): " + changed);
            }
        });
        return warnings;
    }

    /** Sigue la numeración de la chequera a partir del cheque más alto ya usado, también en planillas anuladas. */
    private String suggestNextCheque(Long runId) {
        BigInteger best = null;
        int width = 0;
        String suffix = "";
        for (String number : lineRepository.findChequeNumbersOfAllOtherRuns(runId)) {
            Matcher matcher = CHEQUE_NUMBER.matcher(compactCheque(number));
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
        return best == null ? null : String.format("%0" + width + "d", best.add(BigInteger.ONE)) + suffix;
    }

    /** Sin espacios y en mayúsculas: «99645508 /gcf» se guarda como «99645508/GCF». */
    static String compactCheque(String number) {
        return number == null ? "" : number.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Identifica un cheque por su valor y sufijo, sin importar ceros a la izquierda ni mayúsculas. */
    static String chequeKey(String number) {
        String compact = compactCheque(number);
        Matcher matcher = CHEQUE_NUMBER.matcher(compact);
        return matcher.matches() ? new BigInteger(matcher.group(1)) + "|" + matcher.group(2) : compact;
    }

    private static PayrollRunEntity newRun(PayrollCalendar.PayDay day, LocalDate payDate, String bank, String notes, Long userId) {
        return PayrollRunEntity.builder()
                .payGroup(day.payGroup())
                .scheduledDate(day.scheduledDate())
                .payDate(payDate)
                .periodStart(day.periodStart())
                .periodEnd(day.periodEnd())
                .bankName(bank)
                .notes(notes)
                .createdBy(userId)
                .build();
    }

    static LocalDate validPayDate(LocalDate scheduledDate, LocalDate payDate) throws BusinessException {
        if (payDate == null) {
            throw new BusinessException("Indique la fecha de pago.");
        }
        if (payDate.isAfter(scheduledDate)) {
            throw new BusinessException("La fecha de pago no puede ser posterior a la fecha programada.");
        }
        if (payDate.isBefore(scheduledDate.minusDays(MAX_DAYS_EARLY))) {
            throw new BusinessException("La fecha de pago no puede adelantarse más de " + MAX_DAYS_EARLY + " días.");
        }
        return payDate;
    }

    private static boolean paysByCheque(PayrollRunLineEntity line) {
        return PayrollRules.PAYMENT_CHEQUE.equals(line.getPaymentMethod()) && line.getNetPay().signum() > 0;
    }

    private List<EmployeeChargeApplicationEntity> applicationsOf(List<PayrollRunLineEntity> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<EmployeeChargeApplicationEntity> applications = new ArrayList<>(
                applicationRepository.findByRunLineIdIn(lines.stream().map(PayrollRunLineEntity::getId).toList()));
        applications.sort(Comparator.comparing(EmployeeChargeApplicationEntity::getChargeId)
                .thenComparing(EmployeeChargeApplicationEntity::getId));
        return applications;
    }

    private PayrollRunEntity lock(Long id) throws ResourceNotFoundException {
        return runRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("PayrollRun", id));
    }

    private static void requireStatus(PayrollRunEntity run, String status, String message) throws BusinessException {
        if (!status.equals(run.getStatus())) {
            throw new BusinessException(message + " Estado actual: " + run.getStatus().toLowerCase() + ".");
        }
    }

    private static String validGroup(String payGroup) throws BusinessException {
        String group = PayrollRules.normalize(payGroup);
        if (group == null || !PayrollRules.PAY_GROUPS.contains(group)) {
            throw new BusinessException("Grupo de pago inválido. Use PLANTA o ADMINISTRACION.");
        }
        return group;
    }

    private static String people(long count) {
        return count == 1 ? "1 colaborador" : count + " colaboradores";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sameText(String a, String b) {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String blankToDefault(String bank) {
        String value = blankToNull(bank);
        return value == null ? DEFAULT_BANK : value;
    }

    private PayrollRunResponse toDetail(PayrollRunEntity run, List<PayrollRunLineEntity> lines) {
        PayrollRunResponse response = toResponse(run, lines);
        if (STATUS_DRAFT.equals(run.getStatus()) || STATUS_APPROVED.equals(run.getStatus())) {
            response.setWarnings(warnings(run, lines));
        }
        if (STATUS_DRAFT.equals(run.getStatus())) {
            response.setStale(isStale(run));
        }
        if (STATUS_APPROVED.equals(run.getStatus())) {
            response.setSuggestedFirstChequeNumber(suggestNextCheque(run.getId()));
        }
        return response;
    }

    private PayrollRunResponse toResponse(PayrollRunEntity run, List<PayrollRunLineEntity> lines) {
        return PayrollRunResponse.builder()
                .id(run.getId())
                .payGroup(run.getPayGroup())
                .scheduledDate(run.getScheduledDate())
                .payDate(run.getPayDate())
                .periodStart(run.getPeriodStart())
                .periodEnd(run.getPeriodEnd())
                .status(run.getStatus())
                .bankName(run.getBankName())
                .notes(run.getNotes())
                .totalGross(run.getTotalGross())
                .totalDeductions(run.getTotalDeductions())
                .totalNet(run.getTotalNet())
                .createdAt(run.getCreatedAt())
                .calculatedAt(run.getCalculatedAt())
                .approvedAt(run.getApprovedAt())
                .paidAt(run.getPaidAt())
                .cancelledAt(run.getCancelledAt())
                .cancellationReason(run.getCancellationReason())
                .lines(lines == null ? null : lines.stream().map(PayrollRunService::toLineResponse).toList())
                .build();
    }

    private static PayrollRunResponse.Line toLineResponse(PayrollRunLineEntity line) {
        return PayrollRunResponse.Line.builder()
                .id(line.getId())
                .employeeId(line.getEmployeeId())
                .employeeName(line.getEmployeeName())
                .position(line.getPosition())
                .departmentId(line.getDepartmentId())
                .employmentType(line.getEmploymentType())
                .paymentMethod(line.getPaymentMethod())
                .bankAccount(line.getBankAccount())
                .monthlySalary(line.getMonthlySalary())
                .daysPaid(line.getDaysPaid())
                .baseSalary(line.getBaseSalary())
                .bonification(line.getBonification())
                .igssEmployee(line.getIgssEmployee())
                .isr(line.getIsr())
                .opiDiscount(line.getOpiDiscount())
                .otherDeductions(line.getOtherDeductions())
                .netPay(line.getNetPay())
                .igssEmployer(line.getIgssEmployer())
                .irtra(line.getIrtra())
                .intecap(line.getIntecap())
                .chequeNumber(line.getChequeNumber())
                .build();
    }
}
