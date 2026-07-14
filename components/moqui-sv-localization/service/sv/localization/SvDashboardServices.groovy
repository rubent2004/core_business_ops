import groovy.transform.Field
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

@Field final List<String> ES_MONTHS = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
        'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre']

String periodLabelFor(YearMonth ym) {
    return "${ES_MONTHS[ym.monthValue - 1]} ${ym.year}"
}

YearMonth resolvePeriod(ExecutionContext ec, String periodYearMonth) {
    if (periodYearMonth) return YearMonth.parse(periodYearMonth)
    LocalDate today = ec.user.nowTimestamp.toInstant()
            .atZone(ZoneId.of('America/El_Salvador')).toLocalDate()
    return YearMonth.from(today)
}

Timestamp[] periodBounds(YearMonth ym) {
    LocalDate first = ym.atDay(1)
    LocalDate firstNext = ym.plusMonths(1).atDay(1)
    return [Timestamp.valueOf(first.atStartOfDay()),
            Timestamp.valueOf(firstNext.atStartOfDay())] as Timestamp[]
}

BigDecimal money(Object raw) {
    if (raw == null) return BigDecimal.ZERO.setScale(2)
    return new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_UP)
}

Map computeDteKpis(ExecutionContext ec, String organizationPartyId, Timestamp start,
                   Timestamp end, boolean includeMockAccepted) {
    List<String> acceptedStatuses = includeMockAccepted ? ['DteAccepted', 'DteMockAccepted'] : ['DteAccepted']

    def baseFind = {
        def f = ec.entity.find('sv.localization.dte.DteEmision')
                .condition('fechaGeneracion', EntityCondition.GREATER_THAN_EQUAL_TO, start)
                .condition('fechaGeneracion', EntityCondition.LESS_THAN, end)
        if (organizationPartyId) {
            // Filtrado por org vía Invoice.fromPartyId
            f.condition('invoiceId', EntityCondition.IS_NOT_NULL, null)
        }
        return f
    }

    long total = baseFind().count()
    long accepted = baseFind().condition('statusId', EntityCondition.IN, acceptedStatuses).count()
    long pending = baseFind().condition('statusId', EntityCondition.IN,
            ['DteCreated', 'DteSigned', 'DteTransmitted', 'DteContingency']).count()
    long rejected = baseFind().condition('statusId', 'DteRejected').count()
    long error = baseFind().condition('statusId', 'DteError').count()

    Map byTipo = [:]
    List<EntityValue> dtesByTipo = baseFind().selectField('tipoDteCode').list()
    dtesByTipo.each { EntityValue d ->
        String t = d.tipoDteCode ?: 'UNK'
        byTipo[t] = (byTipo[t] ?: 0L) + 1L
    }

    // Monto agregado de DTEs aceptados (lee jsonOriginal.resumen.totalPagar)
    BigDecimal totalAcceptedAmount = BigDecimal.ZERO
    List<EntityValue> acceptedDtes = baseFind()
            .condition('statusId', EntityCondition.IN, acceptedStatuses).list()
    acceptedDtes.each { EntityValue d ->
        if (organizationPartyId && d.invoiceId) {
            EntityValue inv = ec.entity.find('mantle.account.invoice.Invoice')
                    .condition('invoiceId', d.invoiceId).one()
            if (inv == null || inv.fromPartyId != organizationPartyId) return
        }
        if (d.jsonOriginal) {
            try {
                Map j = (Map) new groovy.json.JsonSlurper().parseText(d.jsonOriginal as String)
                Map resumen = (Map) j.resumen
                if (resumen?.totalPagar != null) {
                    totalAcceptedAmount = totalAcceptedAmount.add(money(resumen.totalPagar))
                } else if (resumen?.montoTotalOperacion != null) {
                    totalAcceptedAmount = totalAcceptedAmount.add(money(resumen.montoTotalOperacion))
                }
            } catch (ignored) { /* JSON malformado: ignorar contribución */ }
        }
    }

    return [
            total                : total,
            accepted             : accepted,
            pending              : pending,
            rejected             : rejected,
            error                : error,
            byTipo               : byTipo,
            totalAcceptedAmount  : money(totalAcceptedAmount),
            currencyUomId        : 'USD'
    ]
}

String healthStatusLabel(String enumId) {
    switch (enumId) {
        case 'SvHealthUp': return 'EN LÍNEA'
        case 'SvHealthDegraded': return 'DEGRADADO'
        case 'SvHealthDown': return 'FUERA DE LÍNEA'
        case 'DteHealthUp': return 'EN LÍNEA'
        case 'DteHealthDegraded': return 'DEGRADADO'
        case 'DteHealthDown': return 'FUERA DE LÍNEA'
        default: return enumId ?: 'DESCONOCIDO'
    }
}

Map computeMhHealth(ExecutionContext ec) {
    EntityValue h = ec.entity.find('sv.localization.dte.SvDteSystemHealth')
            .condition('healthId', 'DEFAULT').one()
    if (h == null) {
        h = ec.entity.find('sv.localization.dte.SvDteSystemHealth')
                .condition('healthId', 'SINGLETON').one()
    }
    if (h == null) {
        return [statusEnumId: null, statusLabel: 'SIN DATOS',
                consecutiveFailures: 0, contingencyActive: false]
    }
    return [
            statusEnumId       : h.statusEnumId,
            statusLabel        : healthStatusLabel(h.statusEnumId as String),
            consecutiveFailures: h.consecutiveFailures ?: 0,
            lastCheckAt        : h.lastCheckAt?.toString(),
            lastSuccessAt      : h.lastSuccessAt?.toString(),
            lastFailureAt      : h.lastFailureAt?.toString(),
            contingencyActive  : (h.activeContingenciaId != null),
            contingenciaId     : h.activeContingenciaId
    ]
}

Map computeCertificateStatus(ExecutionContext ec, String organizationPartyId, Timestamp now) {
    // Determinar NIT relevante: por org o el cert vigente activo
    String nit = null
    if (organizationPartyId) {
        EntityValue nitIdent = ec.entity.find('mantle.party.PartyIdentification')
                .condition([partyId: organizationPartyId, partyIdTypeEnumId: 'PitNit']).one()
        nit = nitIdent?.idValue?.toString()?.replaceAll('[^0-9]', '')
    }
    def certFind = ec.entity.find('sv.localization.dte.DteCertificate').orderBy('-fromDate')
    if (nit) certFind.condition('nit', nit)
    EntityValue cert = certFind.list().find { EntityValue c ->
        (c.fromDate == null || !((Timestamp) c.fromDate).after(now)) &&
                (c.thruDate == null || ((Timestamp) c.thruDate).after(now))
    }

    if (cert == null) {
        return [status: 'NoConfigurado', nit: nit, statusLabel: 'NO CONFIGURADO']
    }

    Long days = null
    String status = 'Vigente'
    if (cert.thruDate) {
        long ms = ((Timestamp) cert.thruDate).time - now.time
        days = (long) Math.floor((double) ms / 86_400_000L)
        if (days <= 0) status = 'Vencido'
        else if (days <= 30) status = 'PorVencer'
    }
    return [
            nit               : cert.nit,
            commonName        : cert.commonName,
            fromDate          : cert.fromDate?.toString(),
            thruDate          : cert.thruDate?.toString(),
            daysUntilExpiry   : days,
            status            : status,
            statusLabel       : status == 'Vigente' ? 'VIGENTE' :
                                status == 'PorVencer' ? "POR VENCER (${days}d)" :
                                status == 'Vencido' ? 'VENCIDO' : status.toUpperCase()
    ]
}

List<Map> computeAlerts(ExecutionContext ec, Map mhHealth, Map cert,
                        Timestamp start, Timestamp end, Timestamp now) {
    List<Map> alerts = []

    // DTEs pendientes >24h
    Timestamp limit24h = new Timestamp(now.time - 24L * 3600_000L)
    long stuck = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', EntityCondition.IN,
                    ['DteCreated', 'DteSigned', 'DteTransmitted', 'DteContingency'])
            .condition('fechaGeneracion', EntityCondition.LESS_THAN_EQUAL_TO, limit24h)
            .count()
    if (stuck > 0) alerts.add([
            level  : 'danger', key: 'dte_stuck_24h', count: stuck,
            message: "${stuck} DTE${stuck == 1L ? '' : 's'} sin transmitir/aceptar hace más de 24h"
    ])

    // Certificado por vencer
    if (cert?.status == 'PorVencer') alerts.add([
            level  : 'warning', key: 'cert_expiry',
            message: "Certificado MH vence en ${cert.daysUntilExpiry} días — renovar pronto"
    ])
    if (cert?.status == 'Vencido') alerts.add([
            level  : 'danger', key: 'cert_expired',
            message: 'Certificado MH VENCIDO — no se pueden firmar DTEs nuevos'
    ])
    if (cert?.status == 'NoConfigurado') alerts.add([
            level  : 'warning', key: 'cert_missing',
            message: 'No hay certificado MH configurado para emitir DTEs'
    ])

    // Contingencia activa
    if (mhHealth?.contingencyActive) alerts.add([
            level  : 'danger', key: 'contingency_active',
            message: "Contingencia activa: ${mhHealth.contingenciaId} — cerrar al normalizar MH"
    ])

    // MH degradado
    if (mhHealth?.consecutiveFailures && (mhHealth.consecutiveFailures as int) >= 3) alerts.add([
            level  : 'warning', key: 'mh_degraded',
            count  : mhHealth.consecutiveFailures,
            message: "MH con ${mhHealth.consecutiveFailures} fallos consecutivos — revisar conectividad"
    ])

    // DTEs rechazados en el período
    long rejectedPeriod = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', EntityCondition.IN, ['DteRejected', 'DteError'])
            .condition('fechaGeneracion', EntityCondition.GREATER_THAN_EQUAL_TO, start)
            .condition('fechaGeneracion', EntityCondition.LESS_THAN, end)
            .count()
    if (rejectedPeriod >= 5) alerts.add([
            level  : 'warning', key: 'rejection_rate', count: rejectedPeriod,
            message: "${rejectedPeriod} DTEs rechazados este mes — revisar formato/datos"
    ])

    return alerts
}

List<Map> computeRecentDtes(ExecutionContext ec, String organizationPartyId,
                            Timestamp start, Timestamp end) {
    List<EntityValue> dtes = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('fechaGeneracion', EntityCondition.GREATER_THAN_EQUAL_TO, start)
            .condition('fechaGeneracion', EntityCondition.LESS_THAN, end)
            .orderBy('-fechaGeneracion').limit(20).list()

    List<Map> out = []
    for (EntityValue d in dtes) {
        if (organizationPartyId && d.invoiceId) {
            EntityValue inv = ec.entity.find('mantle.account.invoice.Invoice')
                    .condition('invoiceId', d.invoiceId).one()
            if (inv == null || inv.fromPartyId != organizationPartyId) continue
        }
        out.add([
                dteEmisionId    : d.dteEmisionId,
                tipoDteCode     : d.tipoDteCode,
                numeroControl   : d.numeroControl,
                codigoGeneracion: d.codigoGeneracion,
                statusId        : d.statusId,
                fechaGeneracion : d.fechaGeneracion?.toString(),
                selloRecepcion  : d.selloRecepcion,
                errorMessage    : d.errorMessage
        ])
        if (out.size() >= 10) break
    }
    return out
}

Map computeCounters(ExecutionContext ec, String organizationPartyId, String periodText) {
    Map c = [:]
    c.customers = ec.entity.find('mantle.party.PartyRole')
            .condition('roleTypeId', 'Customer').count()
    c.suppliers = ec.entity.find('mantle.party.PartyRole')
            .condition('roleTypeId', 'Supplier').count()
    c.runsInProgress = ec.entity.find('mantle.work.effort.WorkEffort')
            .condition('purposeEnumId', 'WepProductionRun')
            .condition('statusId', EntityCondition.IN,
                    ['WeInPlanning', 'WeApproved', 'WeInProgress']).count()
    def payrollFind = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('statusId', EntityCondition.IN,
                    ['SvPayCreated', 'SvPayCalculated', 'SvPayApproved'])
    if (organizationPartyId) payrollFind.condition('organizationPartyId', organizationPartyId)
    c.payrollRunsPending = payrollFind.count()
    def ivaFind = ec.entity.find('sv.localization.tax.SvIvaBookEntry')
            .condition('periodYearMonth', periodText)
    if (organizationPartyId) ivaFind.condition('organizationPartyId', organizationPartyId)
    c.ivaBookEntries = ivaFind.count()
    return c
}

Object getSvDashboardData() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    YearMonth ym = resolvePeriod(ec, (String) ctx('periodYearMonth'))
    boolean includeMockAccepted = ctx('includeMockAccepted') != false
    Timestamp[] bounds = periodBounds(ym)
    Timestamp now = ec.user.nowTimestamp

    Map dteKpis = computeDteKpis(ec, organizationPartyId, bounds[0], bounds[1], includeMockAccepted)
    Map mhHealth = computeMhHealth(ec)
    Map cert = computeCertificateStatus(ec, organizationPartyId, now)
    List<Map> alerts = computeAlerts(ec, mhHealth, cert, bounds[0], bounds[1], now)
    List<Map> recentDtes = computeRecentDtes(ec, organizationPartyId, bounds[0], bounds[1])
    Map counters = computeCounters(ec, organizationPartyId, ym.toString())

    return [
            periodYearMonth     : ym.toString(),
            periodLabel         : periodLabelFor(ym),
            organizationPartyId : organizationPartyId,
            dteKpis             : dteKpis,
            mhHealth            : mhHealth,
            certificate         : cert,
            alerts              : alerts,
            recentDtes          : recentDtes,
            counters            : counters
    ]
}
