import groovy.transform.Field
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.xml.transform.stream.StreamSource

@Field final List<String> ES_MONTHS = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
        'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre']

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

BigDecimal decimal(Object raw, BigDecimal fallback = BigDecimal.ZERO) {
    if (raw == null || raw == '') return fallback
    return new BigDecimal(raw.toString())
}

BigDecimal money(Object raw) {
    return decimal(raw).setScale(2, RoundingMode.HALF_UP)
}

BigDecimal percent(Object raw) {
    return decimal(raw).divide(new BigDecimal('100'), 10, RoundingMode.HALF_UP)
}

Timestamp asTimestamp(Object raw, ExecutionContext ec) {
    if (raw == null) return ec.user.nowTimestamp
    if (raw instanceof Timestamp) return (Timestamp) raw
    if (raw instanceof java.sql.Date) return new Timestamp(((java.sql.Date) raw).time)
    if (raw instanceof Date) return new Timestamp(((Date) raw).time)
    String text = raw.toString().trim().replace('T', ' ')
    if (text.length() == 10) text = "${text} 00:00:00"
    if (text.length() > 19) text = text.substring(0, 19)
    return Timestamp.valueOf(text)
}

LocalDate asLocalDate(Object raw, ExecutionContext ec) {
    if (raw == null) return ec.user.nowTimestamp.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDate()
    if (raw instanceof Timestamp) return ((Timestamp) raw).toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDate()
    if (raw instanceof java.sql.Date) return ((java.sql.Date) raw).toLocalDate()
    if (raw instanceof Date) return ((Date) raw).toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDate()
    String text = raw.toString().trim()
    if (text.length() > 10) text = text.substring(0, 10)
    return LocalDate.parse(text)
}

boolean activeOn(EntityValue value, Timestamp asOfTs) {
    Timestamp fromDate = (Timestamp) value.fromDate
    Timestamp thruDate = (Timestamp) value.thruDate
    return (fromDate == null || !fromDate.after(asOfTs)) && (thruDate == null || thruDate.after(asOfTs))
}

EntityValue effectiveScheme(ExecutionContext ec, String schemeTypeEnumId, Timestamp asOfTs) {
    List<EntityValue> schemes = ec.entity.find('sv.localization.payroll.SvPayrollScheme')
            .condition('schemeTypeEnumId', schemeTypeEnumId)
            .orderBy('-fromDate')
            .list()
    return schemes.find { EntityValue scheme -> activeOn(scheme, asOfTs) }
}

List<EntityValue> schemeBrackets(ExecutionContext ec, String schemeId) {
    return ec.entity.find('sv.localization.payroll.SvPayrollSchemeBracket')
            .condition('svPayrollSchemeId', schemeId)
            .orderBy('bracketSeq')
            .list()
}

EntityValue bracketFor(List<EntityValue> brackets, BigDecimal amount) {
    return brackets.find { EntityValue bracket ->
        BigDecimal fromAmount = decimal(bracket.bracketFromAmount)
        BigDecimal thruAmount = bracket.bracketThruAmount == null ? null : decimal(bracket.bracketThruAmount)
        boolean aboveLower = bracket.bracketSeq == 1 ? amount.compareTo(fromAmount) >= 0 : amount.compareTo(fromAmount) > 0
        boolean belowUpper = thruAmount == null || amount.compareTo(thruAmount) <= 0
        return aboveLower && belowUpper
    } ?: brackets.last()
}

Map calculateContribution(ExecutionContext ec, String schemeTypeEnumId, BigDecimal gross, Timestamp asOfTs) {
    EntityValue scheme = effectiveScheme(ec, schemeTypeEnumId, asOfTs)
    if (scheme == null) {
        ec.message.addError("No hay esquema vigente para ${schemeTypeEnumId}")
        return [amountRaw: BigDecimal.ZERO, amount: BigDecimal.ZERO, base: BigDecimal.ZERO, scheme: null, bracket: null]
    }
    EntityValue bracket = schemeBrackets(ec, scheme.svPayrollSchemeId as String).first()
    BigDecimal cap = bracket.cap == null ? null : decimal(bracket.cap)
    BigDecimal base = cap == null ? gross : gross.min(cap)
    BigDecimal amount = base.multiply(percent(bracket.percentage ?: 0))
            .add(decimal(bracket.fixedAmount))
    return [amountRaw: amount, amount: money(amount), base: money(base), scheme: scheme, bracket: bracket]
}

Map calculateRenta(ExecutionContext ec, BigDecimal gross, BigDecimal isssEmpRaw, BigDecimal afpEmpRaw, Timestamp asOfTs) {
    EntityValue scheme = effectiveScheme(ec, 'PstRentaMensual', asOfTs)
    if (scheme == null) {
        ec.message.addError('No hay esquema vigente para PstRentaMensual')
        return [amountRaw: BigDecimal.ZERO, amount: BigDecimal.ZERO, base: BigDecimal.ZERO, taxableBase: BigDecimal.ZERO,
                scheme: null, bracket: null]
    }

    BigDecimal base = gross.subtract(isssEmpRaw).subtract(afpEmpRaw)
    if (base.compareTo(BigDecimal.ZERO) < 0) base = BigDecimal.ZERO
    List<EntityValue> brackets = schemeBrackets(ec, scheme.svPayrollSchemeId as String)
    EntityValue originalBracket = bracketFor(brackets, base)
    BigDecimal taxableBase = base

    if (originalBracket?.deductionAmount != null) {
        BigDecimal annualBase = base.multiply(new BigDecimal('12'))
        BigDecimal annualMax = originalBracket.deductionAnnualMaxAmount == null ? null : decimal(originalBracket.deductionAnnualMaxAmount)
        if (annualMax == null || annualBase.compareTo(annualMax) <= 0) {
            taxableBase = taxableBase.subtract(decimal(originalBracket.deductionAmount))
            if (taxableBase.compareTo(BigDecimal.ZERO) < 0) taxableBase = BigDecimal.ZERO
        }
    }

    EntityValue bracket = bracketFor(brackets, taxableBase)
    BigDecimal fromAmount = decimal(bracket.bracketFromAmount)
    BigDecimal excess = taxableBase.subtract(fromAmount)
    if (excess.compareTo(BigDecimal.ZERO) < 0) excess = BigDecimal.ZERO
    BigDecimal amount = decimal(bracket.fixedAmount).add(excess.multiply(percent(bracket.percentage ?: 0)))
    return [amountRaw: amount, amount: money(amount), base: money(base), taxableBase: money(taxableBase),
            scheme: scheme, bracket: bracket]
}

Map schemeSummary(String type, Map calc) {
    EntityValue scheme = (EntityValue) calc.scheme
    EntityValue bracket = (EntityValue) calc.bracket
    return [
            schemeTypeEnumId : type,
            svPayrollSchemeId: scheme?.svPayrollSchemeId,
            bracketSeq       : bracket?.bracketSeq,
            base             : calc.base,
            amount           : calc.amount
    ]
}

Object calculateMonthlyPayroll() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    BigDecimal gross = decimal(ctx('grossMonthlyAmount'))
    if (gross.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError('grossMonthlyAmount debe ser mayor a cero')
        return [:]
    }

    Timestamp asOfTs = asTimestamp(ctx('asOfDate'), ec)
    String currencyUomId = (String) (ctx('currencyUomId') ?: 'USD')
    boolean includeEmployer = ctx('includeEmployerContributions') != false

    Map isssEmp = calculateContribution(ec, 'PstIsssEmp', gross, asOfTs)
    Map afpEmp = calculateContribution(ec, 'PstAfpEmp', gross, asOfTs)
    Map renta = calculateRenta(ec, gross, (BigDecimal) isssEmp.amountRaw, (BigDecimal) afpEmp.amountRaw, asOfTs)
    Map isssEmpr = includeEmployer ? calculateContribution(ec, 'PstIsssEmpr', gross, asOfTs) :
            [amountRaw: BigDecimal.ZERO, amount: money(0), base: money(0), scheme: null, bracket: null]
    Map afpEmpr = includeEmployer ? calculateContribution(ec, 'PstAfpEmpr', gross, asOfTs) :
            [amountRaw: BigDecimal.ZERO, amount: money(0), base: money(0), scheme: null, bracket: null]

    if (ec.message.hasError()) return [:]

    BigDecimal employeeDeductionsRaw = ((BigDecimal) isssEmp.amountRaw)
            .add((BigDecimal) afpEmp.amountRaw)
            .add((BigDecimal) renta.amountRaw)
    BigDecimal employerContribRaw = ((BigDecimal) isssEmpr.amountRaw).add((BigDecimal) afpEmpr.amountRaw)

    List<Map> applied = [
            schemeSummary('PstIsssEmp', isssEmp),
            schemeSummary('PstAfpEmp', afpEmp),
            schemeSummary('PstRentaMensual', renta)
    ]
    if (includeEmployer) {
        applied.add(schemeSummary('PstIsssEmpr', isssEmpr))
        applied.add(schemeSummary('PstAfpEmpr', afpEmpr))
    }

    return [
            grossPay                  : money(gross),
            currencyUomId             : currencyUomId,
            rentaBase                 : renta.base,
            rentaTaxableBase          : renta.taxableBase,
            isssEmployee              : isssEmp.amount,
            isssEmployer              : isssEmpr.amount,
            afpEmployee               : afpEmp.amount,
            afpEmployer               : afpEmpr.amount,
            rentaMonthly              : renta.amount,
            totalEmployeeDeductions   : money(employeeDeductionsRaw),
            netPay                    : money(gross.subtract(employeeDeductionsRaw)),
            totalEmployerContributions: money(employerContribRaw),
            employerCost              : money(gross.add(employerContribRaw)),
            appliedSchemes            : applied
    ]
}

Object calculateVacationPay() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    BigDecimal monthlySalary = decimal(ctx('monthlySalary'))
    if (monthlySalary.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError('monthlySalary debe ser mayor a cero')
        return [:]
    }
    BigDecimal dailySalary = monthlySalary.divide(new BigDecimal('30'), 10, RoundingMode.HALF_UP)
    BigDecimal basePay = dailySalary.multiply(new BigDecimal('15'))
    BigDecimal bonus = basePay.multiply(new BigDecimal('0.30'))
    return [
            monthlySalary   : money(monthlySalary),
            dailySalary     : money(dailySalary),
            vacationDays    : 15,
            baseVacationPay : money(basePay),
            vacationBonus   : money(bonus),
            totalVacationPay: money(basePay.add(bonus)),
            currencyUomId   : (String) (ctx('currencyUomId') ?: 'USD')
    ]
}

Object calculateAguinaldo() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    BigDecimal monthlySalary = decimal(ctx('monthlySalary'))
    if (monthlySalary.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError('monthlySalary debe ser mayor a cero')
        return [:]
    }
    LocalDate hire = asLocalDate(ctx('hireDate'), ec)
    LocalDate asOf = asLocalDate(ctx('asOfDate'), ec)
    int years = Period.between(hire, asOf).years
    if (years < 0) {
        ec.message.addError('hireDate no puede ser posterior a asOfDate')
        return [:]
    }
    int days = years < 3 ? 15 : (years < 10 ? 19 : 21)
    BigDecimal dailySalary = monthlySalary.divide(new BigDecimal('30'), 10, RoundingMode.HALF_UP)
    BigDecimal amount = dailySalary.multiply(new BigDecimal(days))
    return [
            monthlySalary   : money(monthlySalary),
            dailySalary     : money(dailySalary),
            yearsOfService  : years,
            aguinaldoDays   : days,
            aguinaldoAmount : money(amount),
            currencyUomId   : (String) (ctx('currencyUomId') ?: 'USD')
    ]
}

// =====================================================================
// Operación de corrida de nómina: orquesta los cálculos base sobre
// empleados activos de una organización para un período YYYY-MM.
// =====================================================================

/**
 * Devuelve el timestamp del último día del mes para un período YYYY-MM.
 * Se usa como asOfDate por defecto para resolver esquemas vigentes y
 * filtrar la antigüedad de los empleados.
 */
Timestamp endOfPeriod(String periodYearMonth) {
    LocalDate endOfMonth = LocalDate.parse("${periodYearMonth}-01").plusMonths(1).minusDays(1)
    return Timestamp.valueOf(endOfMonth.atTime(23, 59, 59))
}

/**
 * create#PayrollRun — Crea cabecera de corrida vacía.
 * Validaciones:
 *  - Una sola corrida activa (no Posted) por (org, período)
 *  - Período en formato YYYY-MM (validado por el service definition)
 */
Object createPayrollRun() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    String periodYearMonth = (String) ctx('periodYearMonth')
    String currencyUomId = (String) (ctx('currencyUomId') ?: 'USD')
    String notes = (String) ctx('notes')

    EntityValue existing = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('organizationPartyId', organizationPartyId)
            .condition('periodYearMonth', periodYearMonth)
            .condition('statusId', 'not-equals', 'SvPayPosted')
            .one()
    if (existing != null) {
        ec.message.addError("Ya existe una corrida activa (${existing.svPayrollRunId}) para ${organizationPartyId} en ${periodYearMonth}")
        return [:]
    }

    String svPayrollRunId = ec.entity.sequencedIdPrimary('sv.localization.payroll.SvPayrollRun', null, null)
    EntityValue run = ec.entity.makeValue('sv.localization.payroll.SvPayrollRun').setAll([
            svPayrollRunId      : svPayrollRunId,
            organizationPartyId : organizationPartyId,
            periodYearMonth     : periodYearMonth,
            statusId            : 'SvPayCreated',
            runDate             : ec.user.nowTimestamp,
            asOfDate            : endOfPeriod(periodYearMonth),
            currencyUomId       : currencyUomId,
            createdByUserId     : ec.user.userId,
            notes               : notes
    ])
    run.create()

    return [svPayrollRunId: svPayrollRunId, statusId: 'SvPayCreated']
}

/**
 * calculate#PayrollRun — Calcula nómina para todos los empleados activos
 * de la organización en el período. Itera sobre PartyRole=Employee
 * propiedad de la organización (PartyDetail.ownerPartyId), busca su salario
 * mensual en RateAmount (rateTypeEnumId=PayMonthly) vigente, invoca
 * calculateMonthlyPayroll y persiste el snapshot.
 *
 * Empleados sin salario asignado se marcan como SvPayLineSkipped con motivo.
 *
 * Estado: SvPayCreated → SvPayCalculated.
 */
Object calculatePayrollRun() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')
    Boolean includeAguinaldo = (Boolean) (ctx('includeAguinaldo') ?: false)
    Boolean includeVacation = (Boolean) (ctx('includeVacation') ?: false)

    EntityValue run = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('svPayrollRunId', svPayrollRunId).one()
    if (run == null) {
        ec.message.addError("Corrida ${svPayrollRunId} no encontrada")
        return [:]
    }
    if (!(run.statusId in ['SvPayCreated', 'SvPayCalculated'])) {
        ec.message.addError("Corrida ${svPayrollRunId} en estado ${run.statusId} no puede recalcularse")
        return [:]
    }

    Timestamp asOfTs = (Timestamp) run.asOfDate
    String currencyUomId = (String) run.currencyUomId
    String organizationPartyId = (String) run.organizationPartyId

    // Empleados activos de la organización
    List<EntityValue> employeeRoles = ec.entity.find('mantle.party.PartyRole')
            .condition('roleTypeId', 'Employee').list()
    List<String> employeePartyIds = []
    for (EntityValue role in employeeRoles) {
        EntityValue partyDetail = ec.entity.find('mantle.party.PartyDetail')
                .condition('partyId', role.partyId).one()
        if (partyDetail == null) continue
        if (partyDetail.ownerPartyId == organizationPartyId) {
            employeePartyIds.add((String) role.partyId)
        }
    }

    // Limpiar líneas previas (recalculo)
    ec.entity.find('sv.localization.payroll.SvPayrollRunEmployee')
            .condition('svPayrollRunId', svPayrollRunId).list().each { it.delete() }

    int processedCount = 0
    int skippedCount = 0
    BigDecimal totalGross = BigDecimal.ZERO
    BigDecimal totalNet = BigDecimal.ZERO
    BigDecimal totalEmployerCost = BigDecimal.ZERO
    BigDecimal totalIsssEmp = BigDecimal.ZERO
    BigDecimal totalAfpEmp = BigDecimal.ZERO
    BigDecimal totalRenta = BigDecimal.ZERO
    BigDecimal totalIsssEmpr = BigDecimal.ZERO
    BigDecimal totalAfpEmpr = BigDecimal.ZERO

    for (String employeePartyId in employeePartyIds) {
        EntityValue rate = ec.entity.find('mantle.humanres.rate.RateAmount')
                .condition('partyId', employeePartyId)
                .condition('rateTypeEnumId', 'RatpStandard')
                .conditionDate('fromDate', 'thruDate', asOfTs)
                .orderBy('-fromDate').one()
        BigDecimal salary = rate?.rateAmount != null ? decimal(rate.rateAmount) : BigDecimal.ZERO

        if (salary <= BigDecimal.ZERO) {
            EntityValue skipped = ec.entity.makeValue('sv.localization.payroll.SvPayrollRunEmployee').setAll([
                    svPayrollRunId    : svPayrollRunId,
                    employeePartyId   : employeePartyId,
                    grossMonthlyAmount: BigDecimal.ZERO.setScale(2),
                    statusId          : 'SvPayLineSkipped',
                    skipReason        : 'Empleado sin salario base (RateAmount RatpStandard) asignado'
            ])
            skipped.create()
            skippedCount++
            continue
        }

        Map calc = ec.service.sync().name('sv.localization.PayrollSvServices.calculate#MonthlyPayroll')
                .parameters([
                        grossMonthlyAmount           : salary,
                        asOfDate                     : asOfTs,
                        currencyUomId                : currencyUomId,
                        includeEmployerContributions : true
                ]).call()

        BigDecimal vacPay = BigDecimal.ZERO
        BigDecimal vacDays = BigDecimal.ZERO
        if (includeVacation) {
            Map vacCalc = ec.service.sync().name('sv.localization.PayrollSvServices.calculate#VacationPay')
                    .parameters([monthlySalary: salary, asOfDate: asOfTs, currencyUomId: currencyUomId]).call()
            vacPay = decimal(vacCalc?.totalVacationPay)
            vacDays = decimal(vacCalc?.vacationDays)
        }

        BigDecimal aguinaldoAmt = BigDecimal.ZERO
        BigDecimal aguinaldoDays = BigDecimal.ZERO
        if (includeAguinaldo) {
            EntityValue fiscalProfile = ec.entity.find('sv.localization.party.SvFiscalProfile')
                    .condition('partyId', employeePartyId).one()
            Timestamp hireDate = (Timestamp) fiscalProfile?.fechaIngreso
            if (hireDate != null) {
                Map agCalc = ec.service.sync().name('sv.localization.PayrollSvServices.calculate#Aguinaldo')
                        .parameters([monthlySalary: salary, hireDate: hireDate,
                                     asOfDate: asOfTs, currencyUomId: currencyUomId]).call()
                aguinaldoAmt = decimal(agCalc?.aguinaldoAmount)
                aguinaldoDays = decimal(agCalc?.aguinaldoDays)
            }
        }

        EntityValue line = ec.entity.makeValue('sv.localization.payroll.SvPayrollRunEmployee').setAll([
                svPayrollRunId            : svPayrollRunId,
                employeePartyId           : employeePartyId,
                grossMonthlyAmount        : money(calc.grossPay),
                isssEmployee              : money(calc.isssEmployee),
                isssEmployer              : money(calc.isssEmployer),
                afpEmployee               : money(calc.afpEmployee),
                afpEmployer               : money(calc.afpEmployer),
                rentaBase                 : money(calc.rentaBase),
                rentaTaxableBase          : money(calc.rentaTaxableBase),
                rentaMonthly              : money(calc.rentaMonthly),
                totalEmployeeDeductions   : money(calc.totalEmployeeDeductions),
                totalEmployerContributions: money(calc.totalEmployerContributions),
                netPay                    : money(calc.netPay),
                employerCost              : money(calc.employerCost),
                vacationDays              : vacDays,
                vacationPay               : money(vacPay),
                aguinaldoDays             : aguinaldoDays,
                aguinaldoAmount           : money(aguinaldoAmt),
                statusId                  : 'SvPayLineCalculated'
        ])
        line.create()

        totalGross = totalGross.add(money(calc.grossPay))
        totalNet = totalNet.add(money(calc.netPay))
        totalEmployerCost = totalEmployerCost.add(money(calc.employerCost))
        totalIsssEmp = totalIsssEmp.add(money(calc.isssEmployee))
        totalAfpEmp = totalAfpEmp.add(money(calc.afpEmployee))
        totalRenta = totalRenta.add(money(calc.rentaMonthly))
        totalIsssEmpr = totalIsssEmpr.add(money(calc.isssEmployer))
        totalAfpEmpr = totalAfpEmpr.add(money(calc.afpEmployer))
        processedCount++
    }

    run.setAll([
            statusId            : 'SvPayCalculated',
            employeeCount       : processedCount,
            totalGross          : money(totalGross),
            totalNet            : money(totalNet),
            totalEmployerCost   : money(totalEmployerCost),
            totalIsssEmployee   : money(totalIsssEmp),
            totalAfpEmployee    : money(totalAfpEmp),
            totalRenta          : money(totalRenta),
            totalIsssEmployer   : money(totalIsssEmpr),
            totalAfpEmployer    : money(totalAfpEmpr)
    ])
    run.update()

    return [
            svPayrollRunId    : svPayrollRunId,
            employeeCount     : processedCount,
            skippedCount      : skippedCount,
            totalGross        : money(totalGross),
            totalNet          : money(totalNet),
            totalEmployerCost : money(totalEmployerCost),
            statusId          : 'SvPayCalculated'
    ]
}

/**
 * approve#PayrollRun — Aprueba una corrida calculada.
 * Pasa SvPayCalculated → SvPayApproved y marca las líneas como aprobadas.
 */
Object approvePayrollRun() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')

    EntityValue run = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('svPayrollRunId', svPayrollRunId).one()
    if (run == null) {
        ec.message.addError("Corrida ${svPayrollRunId} no encontrada")
        return [:]
    }
    if (run.statusId != 'SvPayCalculated') {
        ec.message.addError("Solo corridas en estado SvPayCalculated pueden aprobarse (actual: ${run.statusId})")
        return [:]
    }

    run.setAll([
            statusId         : 'SvPayApproved',
            approvedByUserId : ec.user.userId,
            approvedDate     : ec.user.nowTimestamp
    ])
    run.update()

    ec.entity.find('sv.localization.payroll.SvPayrollRunEmployee')
            .condition('svPayrollRunId', svPayrollRunId)
            .condition('statusId', 'SvPayLineCalculated').list().each {
        it.statusId = 'SvPayLineApproved'
        it.update()
    }

    return [svPayrollRunId: svPayrollRunId, statusId: 'SvPayApproved']
}

// =====================================================================
// ACTUALIZACION SEGURA DE ESQUEMAS Y SALARIOS MINIMOS
// =====================================================================
// Cuando el gobierno actualiza ISSS/AFP/Renta o salarios mínimos, NO
// se debe editar el esquema vigente: se crea una NUEVA VIGENCIA con
// fromDate del cambio, cerrando la anterior. Esto preserva el cálculo
// correcto de corridas históricas y permite recalculos puntuales para
// corridas anteriores al cambio.
// =====================================================================

/**
 * Restar 1 segundo a un Timestamp para usar como thruDate de cierre.
 * Esto evita que el esquema anterior y el nuevo se solapen en el segundo
 * exacto del cambio. activeOn() considera thruDate.after(asOfTs).
 */
Timestamp closeAt(Timestamp ts) {
    if (ts == null) return null
    return new Timestamp(ts.getTime())
}

/**
 * version#PayrollScheme — Crea una nueva vigencia atómicamente:
 *   1. Si existe sourceSchemeId: cierra el esquema anterior poniendo
 *      thruDate = effectiveFromDate (sin solape).
 *   2. Crea el nuevo SvPayrollScheme con fromDate = effectiveFromDate y
 *      thruDate = null (vigente indefinidamente).
 *   3. Opcionalmente copia los SvPayrollSchemeBracket del esquema anterior
 *      como punto de partida editable.
 *
 * Validaciones:
 *   - newSchemeId debe ser único
 *   - Si sourceSchemeId existe, su schemeTypeEnumId se hereda al nuevo
 *   - effectiveFromDate no puede ser anterior al fromDate del esquema fuente
 *     (eso crearía solape o invertiría la cronología)
 */
Object versionPayrollScheme() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String sourceSchemeId = (String) ctx('sourceSchemeId')
    String newSchemeId = (String) ctx('newSchemeId')
    Timestamp effectiveFromDate = asTimestamp(ctx('effectiveFromDate'), ec)
    String schemeTypeEnumId = (String) ctx('schemeTypeEnumId')
    String description = (String) ctx('description')
    String currencyUomId = (String) (ctx('currencyUomId') ?: 'USD')
    Boolean copyBrackets = ctx('copyBrackets') != false

    // Validacion: nuevo ID no debe existir
    EntityValue duplicate = ec.entity.find('sv.localization.payroll.SvPayrollScheme')
            .condition('svPayrollSchemeId', newSchemeId).one()
    if (duplicate != null) {
        ec.message.addError("Ya existe un esquema con ID ${newSchemeId}; elija otro identificador")
        return [:]
    }

    EntityValue source = null
    if (sourceSchemeId) {
        source = ec.entity.find('sv.localization.payroll.SvPayrollScheme')
                .condition('svPayrollSchemeId', sourceSchemeId).useCache(false).one()
        if (source == null) {
            ec.message.addError("Esquema fuente ${sourceSchemeId} no encontrado")
            return [:]
        }
        Timestamp sourceFrom = (Timestamp) source.fromDate
        if (sourceFrom != null && !effectiveFromDate.after(sourceFrom)) {
            ec.message.addError("effectiveFromDate (${effectiveFromDate}) debe ser posterior al fromDate del esquema fuente (${sourceFrom})")
            return [:]
        }
        if (schemeTypeEnumId && schemeTypeEnumId != source.schemeTypeEnumId) {
            ec.message.addError("schemeTypeEnumId ${schemeTypeEnumId} no coincide con el del fuente ${source.schemeTypeEnumId}")
            return [:]
        }
        schemeTypeEnumId = (String) source.schemeTypeEnumId
    } else {
        if (!schemeTypeEnumId) {
            ec.message.addError("schemeTypeEnumId es requerido cuando sourceSchemeId es null (primera vigencia)")
            return [:]
        }
    }

    // 1. Cerrar esquema anterior (si existe)
    if (source != null) {
        source.thruDate = effectiveFromDate
        source.update()
    }

    // 2. Crear nueva vigencia
    EntityValue newScheme = ec.entity.makeValue('sv.localization.payroll.SvPayrollScheme').setAll([
            svPayrollSchemeId: newSchemeId,
            schemeTypeEnumId : schemeTypeEnumId,
            description      : description ?: source?.description,
            fromDate         : effectiveFromDate,
            thruDate         : null,
            currencyUomId    : currencyUomId
    ])
    newScheme.create()

    // 3. Copiar tramos del esquema anterior (opcional)
    int bracketsCopied = 0
    if (copyBrackets && source != null) {
        List<EntityValue> sourceBrackets = ec.entity.find('sv.localization.payroll.SvPayrollSchemeBracket')
                .condition('svPayrollSchemeId', sourceSchemeId)
                .orderBy('bracketSeq').list()
        for (EntityValue srcBracket in sourceBrackets) {
            EntityValue newBracket = ec.entity.makeValue('sv.localization.payroll.SvPayrollSchemeBracket').setAll([
                    svPayrollSchemeId       : newSchemeId,
                    bracketSeq              : srcBracket.bracketSeq,
                    bracketFromAmount       : srcBracket.bracketFromAmount,
                    bracketThruAmount       : srcBracket.bracketThruAmount,
                    percentage              : srcBracket.percentage,
                    fixedAmount             : srcBracket.fixedAmount,
                    cap                     : srcBracket.cap,
                    deductionAmount         : srcBracket.deductionAmount,
                    deductionAnnualMaxAmount: srcBracket.deductionAnnualMaxAmount
            ])
            newBracket.create()
            bracketsCopied++
        }
    }

    return [
            newSchemeId    : newSchemeId,
            sourceSchemeId : sourceSchemeId,
            bracketsCopied : bracketsCopied
    ]
}

/**
 * version#MinimumWageSector — Crea una nueva vigencia de salario minimo
 * atomicamente: cierra la fila vigente del sector con thruDate y crea
 * una nueva fila con los nuevos montos.
 *
 * SvMinimumWageSector tiene PK compuesto (sectorEnumId, fromDate), por
 * eso cada vigencia es una fila distinta.
 */
Object versionMinimumWageSector() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String sectorEnumId = (String) ctx('sectorEnumId')
    Timestamp effectiveFromDate = asTimestamp(ctx('effectiveFromDate'), ec)
    BigDecimal monthlyAmount = decimal(ctx('monthlyAmount'))
    BigDecimal dailyAmount = decimal(ctx('dailyAmount'))
    String description = (String) ctx('description')

    if (monthlyAmount.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError("monthlyAmount debe ser mayor a cero")
        return [:]
    }
    if (dailyAmount.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError("dailyAmount debe ser mayor a cero")
        return [:]
    }

    // Buscar vigencia activa del sector en effectiveFromDate (useCache(false) para
    // poder mutar thruDate; las entidades estan cacheadas pero la operacion de cierre
    // requiere mutabilidad).
    List<EntityValue> sectorRows = ec.entity.find('sv.localization.payroll.SvMinimumWageSector')
            .condition('sectorEnumId', sectorEnumId).useCache(false).orderBy('-fromDate').list()
    EntityValue active = sectorRows.find { activeOn(it, effectiveFromDate) }

    Timestamp previousFromDate = null
    if (active != null) {
        previousFromDate = (Timestamp) active.fromDate
        if (previousFromDate != null && !effectiveFromDate.after(previousFromDate)) {
            ec.message.addError("effectiveFromDate (${effectiveFromDate}) debe ser posterior al fromDate de la vigencia actual (${previousFromDate})")
            return [:]
        }
        active.thruDate = effectiveFromDate
        active.update()
    }

    // Crear nueva vigencia
    EntityValue newRow = ec.entity.makeValue('sv.localization.payroll.SvMinimumWageSector').setAll([
            sectorEnumId : sectorEnumId,
            fromDate     : effectiveFromDate,
            thruDate     : null,
            monthlyAmount: money(monthlyAmount),
            dailyAmount  : money(dailyAmount),
            description  : description ?: active?.description
    ])
    newRow.create()

    return [
            sectorEnumId     : sectorEnumId,
            effectiveFromDate: effectiveFromDate,
            previousFromDate : previousFromDate
    ]
}

/**
 * update#PayrollSchemeBracketsSafe — Reemplaza atomicamente los tramos
 * de un esquema. Antes de aplicar:
 *
 *   1. Identifica corridas (SvPayrollRun) cuyo asOfDate cae dentro de la
 *      vigencia del esquema (fromDate <= asOfDate < thruDate).
 *   2. Si alguna de esas corridas está en estado SvPayCalculated o
 *      SvPayApproved (snapshot pendiente de contabilizar o ya aprobado
 *      pero no contabilizado), bloquea la operacion a menos que
 *      forceUnsafe=true.
 *   3. Estados SvPayCreated (sin calcular) y SvPayPosted (contabilizado,
 *      snapshot congelado) son seguros: en SvPayPosted el snapshot ya
 *      fue persistido y contabilizado, modificar el esquema no afecta esa
 *      corrida.
 *
 * Si forceUnsafe=true se aplica de todas maneras y se devuelve
 * affectedRunIds con la lista de corridas que conviene recalcular.
 */
Object updatePayrollSchemeBracketsSafe() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollSchemeId = (String) ctx('svPayrollSchemeId')
    List<Map> brackets = (List<Map>) ctx('brackets')
    Boolean forceUnsafe = (Boolean) (ctx('forceUnsafe') ?: false)

    EntityValue scheme = ec.entity.find('sv.localization.payroll.SvPayrollScheme')
            .condition('svPayrollSchemeId', svPayrollSchemeId).one()
    if (scheme == null) {
        ec.message.addError("Esquema ${svPayrollSchemeId} no encontrado")
        return [:]
    }
    if (brackets == null || brackets.isEmpty()) {
        ec.message.addError("Debe proporcionar al menos un tramo")
        return [:]
    }

    Timestamp from = (Timestamp) scheme.fromDate
    Timestamp thru = (Timestamp) scheme.thruDate

    // Identificar corridas que caen en la vigencia del esquema
    List<EntityValue> candidateRuns = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('statusId', 'in', ['SvPayCalculated', 'SvPayApproved'])
            .list()
    List<String> affectedRunIds = []
    for (EntityValue run in candidateRuns) {
        Timestamp asOf = (Timestamp) run.asOfDate
        if (asOf == null) continue
        boolean afterFrom = (from == null) || !asOf.before(from)
        boolean beforeThru = (thru == null) || asOf.before(thru)
        if (afterFrom && beforeThru) {
            affectedRunIds.add((String) run.svPayrollRunId)
        }
    }

    if (!affectedRunIds.isEmpty() && !forceUnsafe) {
        ec.message.addError("No se puede modificar tramos del esquema ${svPayrollSchemeId}: hay ${affectedRunIds.size()} corrida(s) calculada(s)/aprobada(s) que dependen de el (${affectedRunIds.take(5).join(', ')}). Use forceUnsafe=true para forzar o crear una nueva vigencia con version#PayrollScheme.")
        return [svPayrollSchemeId: svPayrollSchemeId, affectedRunIds: affectedRunIds]
    }

    // Reemplazar tramos: borrar todos, crear nuevos (useCache(false) para mutabilidad)
    ec.entity.find('sv.localization.payroll.SvPayrollSchemeBracket')
            .condition('svPayrollSchemeId', svPayrollSchemeId).useCache(false).list()
            .each { it.delete() }

    int bracketsReplaced = 0
    for (Map bracket in brackets) {
        EntityValue newBracket = ec.entity.makeValue('sv.localization.payroll.SvPayrollSchemeBracket').setAll([
                svPayrollSchemeId       : svPayrollSchemeId,
                bracketSeq              : bracket.bracketSeq,
                bracketFromAmount       : bracket.bracketFromAmount,
                bracketThruAmount       : bracket.bracketThruAmount,
                percentage              : bracket.percentage,
                fixedAmount             : bracket.fixedAmount,
                cap                     : bracket.cap,
                deductionAmount         : bracket.deductionAmount,
                deductionAnnualMaxAmount: bracket.deductionAnnualMaxAmount
        ])
        newBracket.create()
        bracketsReplaced++
    }

    return [
            svPayrollSchemeId: svPayrollSchemeId,
            bracketsReplaced : bracketsReplaced,
            affectedRunIds   : affectedRunIds
    ]
}

// =====================================================================
// REPORTES DE NOMINA: boleta PDF + exportes ISSS/AFP/Renta
// =====================================================================

String spanishPeriod(String periodYearMonth) {
    if (!periodYearMonth || periodYearMonth.length() < 7) return periodYearMonth
    int month = Integer.parseInt(periodYearMonth.substring(5, 7))
    String year = periodYearMonth.substring(0, 4)
    return "${ES_MONTHS[month - 1]} ${year}"
}

String employeeFullName(ExecutionContext ec, String partyId) {
    EntityValue person = ec.entity.find('mantle.party.Person').condition('partyId', partyId).one()
    if (person != null) {
        return [person.firstName, person.middleName, person.lastName]
                .findAll { it }.join(' ')
    }
    EntityValue org = ec.entity.find('mantle.party.Organization').condition('partyId', partyId).one()
    return org?.organizationName ?: partyId
}

String partyIdentifier(ExecutionContext ec, String partyId, String pitType) {
    EntityValue ident = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: pitType]).one()
    return ident?.idValue
}

EntityValue fiscalProfileFor(ExecutionContext ec, String partyId) {
    return ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', partyId).one()
}

String afpProviderLabel(ExecutionContext ec, String afpProviderEnumId) {
    if (!afpProviderEnumId) return null
    EntityValue en = ec.entity.find('moqui.basic.Enumeration')
            .condition('enumId', afpProviderEnumId).one()
    return en?.description ?: afpProviderEnumId
}

Map orgContextFor(ExecutionContext ec, String organizationPartyId) {
    EntityValue org = ec.entity.find('mantle.party.Organization')
            .condition('partyId', organizationPartyId).one()
    EntityValue fp = fiscalProfileFor(ec, organizationPartyId)
    return [
            organizationName: org?.organizationName ?: organizationPartyId,
            nit             : partyIdentifier(ec, organizationPartyId, 'PitNit'),
            nrc             : partyIdentifier(ec, organizationPartyId, 'PitNrc'),
            nombreComercial : fp?.nombreComercial,
            address         : null
    ]
}

Map buildPayrollSlipContext(ExecutionContext ec, String svPayrollRunId, String employeePartyId) {
    EntityValue run = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('svPayrollRunId', svPayrollRunId).one()
    if (run == null) {
        ec.message.addError("Corrida ${svPayrollRunId} no encontrada")
        return null
    }
    EntityValue line = ec.entity.find('sv.localization.payroll.SvPayrollRunEmployee')
            .condition([svPayrollRunId: svPayrollRunId, employeePartyId: employeePartyId]).one()
    if (line == null) {
        ec.message.addError("Empleado ${employeePartyId} no esta en la corrida ${svPayrollRunId}")
        return null
    }
    if (line.statusId == 'SvPayLineSkipped') {
        ec.message.addError("Empleado ${employeePartyId} fue omitido en la corrida: ${line.skipReason}")
        return null
    }

    EntityValue fp = fiscalProfileFor(ec, employeePartyId)
    Map empCtx = [
            partyId       : employeePartyId,
            fullName      : employeeFullName(ec, employeePartyId),
            dui           : partyIdentifier(ec, employeePartyId, 'PitDui'),
            nit           : partyIdentifier(ec, employeePartyId, 'PitNit'),
            nupIsss       : fp?.nupIsss,
            nupAfp        : fp?.nupAfp,
            afpProvider   : afpProviderLabel(ec, fp?.afpProviderEnumId as String),
            fechaIngreso  : fp?.fechaIngreso?.toString()
    ]

    Map runCtx = [
            svPayrollRunId : svPayrollRunId,
            periodYearMonth: run.periodYearMonth,
            periodLabel    : spanishPeriod(run.periodYearMonth as String),
            statusId       : run.statusId,
            runDate        : run.runDate?.toString()
    ]

    BigDecimal gross = money(line.grossMonthlyAmount).add(money(line.vacationPay)).add(money(line.aguinaldoAmount))
    BigDecimal netPay = money(line.netPay).add(money(line.vacationPay)).add(money(line.aguinaldoAmount))
    Map totalsCtx = [gross: gross, netPay: netPay]

    return [
            employee   : empCtx,
            payrollRun : runCtx,
            organization: orgContextFor(ec, run.organizationPartyId as String),
            payrollLine: line,
            totals     : totalsCtx,
            generatedAt: ec.user.nowTimestamp.toString()
    ]
}

Object getPayrollSlipContext() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    Map ctxMap = buildPayrollSlipContext(ec, (String) ctx('svPayrollRunId'), (String) ctx('employeePartyId'))
    return ctxMap ?: [:]
}

Object renderPayrollSlip() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')
    String employeePartyId = (String) ctx('employeePartyId')

    Map ctxMap = buildPayrollSlipContext(ec, svPayrollRunId, employeePartyId)
    if (ctxMap == null) return [rendered: false]

    String templateLocation = 'component://moqui-sv-localization/template/payroll/BoletaPago.xsl-fo.ftl'
    Map context = ec.context
    List<String> keys = ['employee', 'payrollRun', 'organization', 'payrollLine', 'totals', 'generatedAt']
    Map oldValues = keys.collectEntries { String k -> [(k): context.get(k)] }
    Map hadValues = keys.collectEntries { String k -> [(k): context.containsKey(k)] }
    try {
        keys.each { String k -> context.put(k, ctxMap[k]) }
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [
                rendered        : true,
                templateLocation: templateLocation,
                xslFoText       : xslFo,
                pdfBytes        : baos.toByteArray(),
                pdfSize         : baos.size(),
                pageCount       : pageCount,
                filename        : "boleta-pago-${svPayrollRunId}-${employeePartyId}.pdf",
                contentType     : 'application/pdf'
        ]
    } finally {
        keys.each { String k ->
            if (hadValues[k]) context.put(k, oldValues[k]) else context.remove(k)
        }
    }
}

String csvEscape(Object raw) {
    String text = raw == null ? '' : raw.toString()
    if (text.contains('"')) text = text.replace('"', '""')
    return (text.contains(',') || text.contains('\n') || text.contains('"')) ? "\"${text}\"" : text
}

List<EntityValue> runEmployeesForExport(ExecutionContext ec, String svPayrollRunId) {
    return ec.entity.find('sv.localization.payroll.SvPayrollRunEmployee')
            .condition('svPayrollRunId', svPayrollRunId)
            .condition('statusId', 'not-equals', 'SvPayLineSkipped')
            .orderBy('employeePartyId').list()
}

EntityValue runOrFail(ExecutionContext ec, String svPayrollRunId) {
    EntityValue run = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('svPayrollRunId', svPayrollRunId).one()
    if (run == null) {
        ec.message.addError("Corrida ${svPayrollRunId} no encontrada")
        return null
    }
    return run
}

Object exportIsssReport() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')
    EntityValue run = runOrFail(ec, svPayrollRunId)
    if (run == null) return [:]

    List<EntityValue> lines = runEmployeesForExport(ec, svPayrollRunId)
    List<String> headers = ['NUP_ISSS', 'DUI', 'Apellidos y Nombres', 'Salario Devengado',
                            'Aporte Trabajador 3%', 'Aporte Patronal 7.5%', 'Total Aporte']
    StringBuilder sb = new StringBuilder(headers.collect { csvEscape(it) }.join(',')).append('\n')
    BigDecimal totalGross = BigDecimal.ZERO
    BigDecimal totalEmp = BigDecimal.ZERO
    BigDecimal totalEmpr = BigDecimal.ZERO
    int rows = 0
    for (EntityValue line in lines) {
        String pid = line.employeePartyId as String
        EntityValue fp = fiscalProfileFor(ec, pid)
        BigDecimal emp = money(line.isssEmployee)
        BigDecimal empr = money(line.isssEmployer)
        BigDecimal grossLine = money(line.grossMonthlyAmount)
        List row = [fp?.nupIsss ?: '',
                    partyIdentifier(ec, pid, 'PitDui') ?: '',
                    employeeFullName(ec, pid),
                    grossLine, emp, empr, emp.add(empr)]
        sb.append(row.collect { csvEscape(it) }.join(',')).append('\n')
        totalGross = totalGross.add(grossLine)
        totalEmp = totalEmp.add(emp)
        totalEmpr = totalEmpr.add(empr)
        rows++
    }
    List totalRow = ['', '', 'TOTALES', money(totalGross), money(totalEmp), money(totalEmpr),
                     money(totalEmp.add(totalEmpr))]
    sb.append(totalRow.collect { csvEscape(it) }.join(',')).append('\n')
    return [
            csvText    : sb.toString(),
            filename   : "isss-planilla-${run.organizationPartyId}-${run.periodYearMonth}.csv",
            contentType: 'text/csv; charset=UTF-8',
            rowCount   : rows,
            totals     : [gross: money(totalGross), employee: money(totalEmp),
                          employer: money(totalEmpr), grand: money(totalEmp.add(totalEmpr))]
    ]
}

Object exportAfpReport() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')
    EntityValue run = runOrFail(ec, svPayrollRunId)
    if (run == null) return [:]

    List<EntityValue> lines = runEmployeesForExport(ec, svPayrollRunId)
    List<String> headers = ['NUP_AFP', 'DUI', 'AFP', 'Apellidos y Nombres', 'Salario Base',
                            'Aporte Trabajador 7.25%', 'Aporte Patronal 8.75%', 'Total Aporte']
    StringBuilder sb = new StringBuilder(headers.collect { csvEscape(it) }.join(',')).append('\n')
    BigDecimal totalGross = BigDecimal.ZERO
    BigDecimal totalEmp = BigDecimal.ZERO
    BigDecimal totalEmpr = BigDecimal.ZERO
    int rows = 0
    for (EntityValue line in lines) {
        String pid = line.employeePartyId as String
        EntityValue fp = fiscalProfileFor(ec, pid)
        BigDecimal emp = money(line.afpEmployee)
        BigDecimal empr = money(line.afpEmployer)
        BigDecimal grossLine = money(line.grossMonthlyAmount)
        List row = [fp?.nupAfp ?: '',
                    partyIdentifier(ec, pid, 'PitDui') ?: '',
                    afpProviderLabel(ec, fp?.afpProviderEnumId as String) ?: '',
                    employeeFullName(ec, pid),
                    grossLine, emp, empr, emp.add(empr)]
        sb.append(row.collect { csvEscape(it) }.join(',')).append('\n')
        totalGross = totalGross.add(grossLine)
        totalEmp = totalEmp.add(emp)
        totalEmpr = totalEmpr.add(empr)
        rows++
    }
    List totalRow = ['', '', '', 'TOTALES', money(totalGross), money(totalEmp), money(totalEmpr),
                     money(totalEmp.add(totalEmpr))]
    sb.append(totalRow.collect { csvEscape(it) }.join(',')).append('\n')
    return [
            csvText    : sb.toString(),
            filename   : "afp-planilla-${run.organizationPartyId}-${run.periodYearMonth}.csv",
            contentType: 'text/csv; charset=UTF-8',
            rowCount   : rows,
            totals     : [gross: money(totalGross), employee: money(totalEmp),
                          employer: money(totalEmpr), grand: money(totalEmp.add(totalEmpr))]
    ]
}

Object exportRentaReport() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')
    EntityValue run = runOrFail(ec, svPayrollRunId)
    if (run == null) return [:]

    List<EntityValue> lines = runEmployeesForExport(ec, svPayrollRunId)
    List<String> headers = ['NIT', 'DUI', 'Apellidos y Nombres', 'Sueldo Gravado',
                            'Renta Imponible', 'Renta Retenida']
    StringBuilder sb = new StringBuilder(headers.collect { csvEscape(it) }.join(',')).append('\n')
    BigDecimal totalBase = BigDecimal.ZERO
    BigDecimal totalTaxable = BigDecimal.ZERO
    BigDecimal totalRenta = BigDecimal.ZERO
    int rows = 0
    for (EntityValue line in lines) {
        String pid = line.employeePartyId as String
        BigDecimal base = money(line.rentaBase)
        BigDecimal taxable = money(line.rentaTaxableBase)
        BigDecimal renta = money(line.rentaMonthly)
        List row = [partyIdentifier(ec, pid, 'PitNit') ?: '',
                    partyIdentifier(ec, pid, 'PitDui') ?: '',
                    employeeFullName(ec, pid),
                    base, taxable, renta]
        sb.append(row.collect { csvEscape(it) }.join(',')).append('\n')
        totalBase = totalBase.add(base)
        totalTaxable = totalTaxable.add(taxable)
        totalRenta = totalRenta.add(renta)
        rows++
    }
    List totalRow = ['', '', 'TOTALES', money(totalBase), money(totalTaxable), money(totalRenta)]
    sb.append(totalRow.collect { csvEscape(it) }.join(',')).append('\n')
    return [
            csvText    : sb.toString(),
            filename   : "renta-planilla-${run.organizationPartyId}-${run.periodYearMonth}.csv",
            contentType: 'text/csv; charset=UTF-8',
            rowCount   : rows,
            totals     : [base: money(totalBase), taxable: money(totalTaxable), retained: money(totalRenta)]
    ]
}
