import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate

// =====================================================================
// CIERRE FISCAL MENSUAL Y CONCILIACIÓN
// =====================================================================
//
// El cierre fiscal opera sobre TimePeriod (mantle.party.time.TimePeriod)
// con timePeriodTypeId="FiscalMonth". Cuando se cierra (isClosed=Y), la
// SECA SvBlockRetroactivePosting rechaza nuevos AcctgTrans con
// transactionDate en ese período.
//
// Operación recomendada:
//   1. ensure#FiscalMonth para el mes a operar
//   2. (durante el mes) los DTEs y nómina postean automáticamente
//   3. Antes de cerrar: reconcile#DteVsGl + compute#TrialBalance
//   4. Si todo cuadra: close#FiscalPeriod
//   5. Si se detecta error post-cierre: reopen#FiscalPeriod (auditado)
// =====================================================================

Object ctx(String name) {
    Binding b = getBinding()
    return b?.hasVariable(name) ? b.getVariable(name) : null
}

BigDecimal money(Object raw) {
    if (raw == null) return BigDecimal.ZERO.setScale(2)
    return new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_UP)
}

/**
 * Devuelve el primer dia (Date) del periodo YYYY-MM.
 */
Date firstDayOf(String periodYearMonth) {
    return Date.valueOf(LocalDate.parse("${periodYearMonth}-01"))
}

/**
 * Devuelve el ultimo dia (Date) del periodo YYYY-MM.
 */
Date lastDayOf(String periodYearMonth) {
    LocalDate ld = LocalDate.parse("${periodYearMonth}-01").plusMonths(1).minusDays(1)
    return Date.valueOf(ld)
}

// =====================================================================
// ensure#FiscalMonth
// =====================================================================

Object ensureFiscalMonth() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    String periodYearMonth = (String) ctx('periodYearMonth')

    Date fromDate = firstDayOf(periodYearMonth)
    Date thruDate = lastDayOf(periodYearMonth)

    EntityValue existing = ec.entity.find('mantle.party.time.TimePeriod')
            .condition([partyId: organizationPartyId, timePeriodTypeId: 'FiscalMonth',
                        fromDate: fromDate]).useCache(false).one()
    if (existing != null) {
        return [
                timePeriodId: existing.timePeriodId,
                isClosed    : existing.isClosed,
                created     : false
        ]
    }

    String timePeriodId = ec.entity.sequencedIdPrimary('mantle.party.time.TimePeriod', null, null)
    ec.entity.makeValue('mantle.party.time.TimePeriod').setAll([
            timePeriodId    : timePeriodId,
            timePeriodTypeId: 'FiscalMonth',
            partyId         : organizationPartyId,
            periodName      : periodYearMonth,
            fromDate        : fromDate,
            thruDate        : thruDate,
            isClosed        : 'N'
    ]).create()

    return [timePeriodId: timePeriodId, isClosed: 'N', created: true]
}

// =====================================================================
// close#FiscalPeriod
// =====================================================================

Object closeFiscalPeriod() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String timePeriodId = (String) ctx('timePeriodId')

    EntityValue period = ec.entity.find('mantle.party.time.TimePeriod')
            .condition('timePeriodId', timePeriodId).useCache(false).one()
    if (period == null) {
        ec.message.addError("TimePeriod ${timePeriodId} no encontrado")
        return [:]
    }
    if (period.isClosed == 'Y') {
        return [timePeriodId: timePeriodId, alreadyClosed: true, closedDate: null]
    }

    period.isClosed = 'Y'
    period.update()

    return [timePeriodId: timePeriodId, alreadyClosed: false, closedDate: ec.user.nowTimestamp]
}

// =====================================================================
// reopen#FiscalPeriod
// =====================================================================

Object reopenFiscalPeriod() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String timePeriodId = (String) ctx('timePeriodId')
    String reason = (String) ctx('reason')

    EntityValue period = ec.entity.find('mantle.party.time.TimePeriod')
            .condition('timePeriodId', timePeriodId).useCache(false).one()
    if (period == null) {
        ec.message.addError("TimePeriod ${timePeriodId} no encontrado")
        return [:]
    }

    boolean wasOpen = period.isClosed != 'Y'
    if (wasOpen) {
        return [timePeriodId: timePeriodId, wasOpen: true]
    }

    period.isClosed = 'N'
    period.update()

    // Log auditable de la reapertura (queda en audit log de Moqui por
    // enable-audit-log="update" en el campo isClosed)
    ec.message.addMessage("TimePeriod ${timePeriodId} reabierto. Razon: ${reason} (usuario ${ec.user.userId})")

    return [timePeriodId: timePeriodId, wasOpen: false]
}

// =====================================================================
// check#TransactionDateNotInClosedPeriod
// =====================================================================

Object checkTransactionDateNotInClosedPeriod() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    Timestamp transactionDate = (Timestamp) ctx('transactionDate')

    if (organizationPartyId == null || transactionDate == null) {
        return [allowed: true, blockingTimePeriodId: null]
    }

    Date txDay = new Date(transactionDate.getTime())
    EntityValue closedPeriod = ec.entity.find('mantle.party.time.TimePeriod')
            .condition('partyId', organizationPartyId)
            .condition('timePeriodTypeId', 'FiscalMonth')
            .condition('isClosed', 'Y')
            .condition('fromDate', 'less-equals', txDay)
            .condition('thruDate', 'greater-equals', txDay)
            .useCache(false).one()
    if (closedPeriod != null) {
        return [allowed: false, blockingTimePeriodId: closedPeriod.timePeriodId]
    }
    return [allowed: true, blockingTimePeriodId: null]
}

// =====================================================================
// reconcile#DteVsGl
// =====================================================================

Object reconcileDteVsGl() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    String periodYearMonth = (String) ctx('periodYearMonth')

    Timestamp fromTs = new Timestamp(firstDayOf(periodYearMonth).getTime())
    Timestamp thruTs = new Timestamp(lastDayOf(periodYearMonth).getTime() + 86399999L) // fin del dia

    // 1. Buscar DTEs aceptados en el periodo de la organizacion
    List<EntityValue> dteList = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', 'in', ['DteAccepted', 'DteMockAccepted'])
            .condition('fechaGeneracion', 'between', [fromTs, thruTs])
            .useCache(false).list()

    // Filtrar por organizacion via Invoice.fromPartyId (las ventas)
    List<EntityValue> dteOrgList = []
    for (EntityValue dte in dteList) {
        if (dte.invoiceId == null) continue
        EntityValue inv = ec.entity.find('mantle.account.invoice.Invoice')
                .condition('invoiceId', dte.invoiceId).useCache(false).one()
        if (inv?.fromPartyId == organizationPartyId || inv?.toPartyId == organizationPartyId) {
            dteOrgList.add(dte)
        }
    }

    // 2. Buscar AcctgTrans del periodo
    List<EntityValue> acctgList = ec.entity.find('mantle.ledger.transaction.AcctgTrans')
            .condition('organizationPartyId', organizationPartyId)
            .condition('transactionDate', 'between', [fromTs, thruTs])
            .condition('isPosted', 'Y')
            .useCache(false).list()

    Set<String> acctgInvoiceIds = acctgList.findAll { it.invoiceId }.collect { (String) it.invoiceId } as Set
    Set<String> dteInvoiceIds = dteOrgList.findAll { it.invoiceId }.collect { (String) it.invoiceId } as Set

    List<Map> dtesWithoutAcctgTrans = []
    for (EntityValue dte in dteOrgList) {
        if (!acctgInvoiceIds.contains((String) dte.invoiceId)) {
            dtesWithoutAcctgTrans.add([
                    dteEmisionId : dte.dteEmisionId,
                    invoiceId    : dte.invoiceId,
                    tipoDteCode  : dte.tipoDteCode,
                    numeroControl: dte.numeroControl
            ])
        }
    }

    List<Map> acctgTransWithoutDte = []
    for (EntityValue trans in acctgList) {
        if (trans.invoiceId && !dteInvoiceIds.contains((String) trans.invoiceId)) {
            acctgTransWithoutDte.add([
                    acctgTransId: trans.acctgTransId,
                    invoiceId   : trans.invoiceId,
                    description : trans.description
            ])
        }
    }

    int totalAccepted = dteOrgList.size()
    int totalPosted = totalAccepted - dtesWithoutAcctgTrans.size()
    BigDecimal coverage = totalAccepted > 0 ?
            new BigDecimal(totalPosted).multiply(new BigDecimal('100'))
                    .divide(new BigDecimal(totalAccepted), 2, RoundingMode.HALF_UP) :
            new BigDecimal('100.00')

    return [
            periodYearMonth      : periodYearMonth,
            totalAccepted        : totalAccepted,
            totalPosted          : totalPosted,
            coverage             : coverage,
            dtesWithoutAcctgTrans: dtesWithoutAcctgTrans,
            acctgTransWithoutDte : acctgTransWithoutDte
    ]
}

// =====================================================================
// compute#TrialBalance
// =====================================================================

Object computeTrialBalance() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = (String) ctx('organizationPartyId')
    String periodYearMonth = (String) ctx('periodYearMonth')

    Timestamp fromTs = new Timestamp(firstDayOf(periodYearMonth).getTime())
    Timestamp thruTs = new Timestamp(lastDayOf(periodYearMonth).getTime() + 86399999L)

    // Traer todos los AcctgTransEntry del periodo de la org
    List<EntityValue> acctgTrans = ec.entity.find('mantle.ledger.transaction.AcctgTrans')
            .condition('organizationPartyId', organizationPartyId)
            .condition('transactionDate', 'between', [fromTs, thruTs])
            .condition('isPosted', 'Y')
            .useCache(false).list()
    Set<String> txIds = acctgTrans.collect { (String) it.acctgTransId } as Set
    if (txIds.isEmpty()) {
        return [
                periodYearMonth  : periodYearMonth,
                lines            : [],
                grandTotalDebit  : BigDecimal.ZERO.setScale(2),
                grandTotalCredit : BigDecimal.ZERO.setScale(2),
                balanced         : true
        ]
    }

    List<EntityValue> entries = ec.entity.find('mantle.ledger.transaction.AcctgTransEntry')
            .condition('acctgTransId', 'in', txIds).useCache(false).list()

    // Agrupar por glAccountId
    Map<String, Map> bucket = [:]
    for (EntityValue entry in entries) {
        String acctId = (String) entry.glAccountId
        if (acctId == null) continue
        Map row = bucket.computeIfAbsent(acctId, { String k ->
            EntityValue glAcct = ec.entity.find('mantle.ledger.account.GlAccount')
                    .condition('glAccountId', k).one()
            return [
                    glAccountId : k,
                    accountCode : glAcct?.accountCode,
                    accountName : glAcct?.accountName,
                    debitTotal  : BigDecimal.ZERO,
                    creditTotal : BigDecimal.ZERO,
                    isDebit     : glAcct?.isDebit
            ]
        })
        BigDecimal amt = money(entry.amount)
        if (entry.debitCreditFlag == 'D') {
            row.debitTotal = (row.debitTotal as BigDecimal).add(amt)
        } else if (entry.debitCreditFlag == 'C') {
            row.creditTotal = (row.creditTotal as BigDecimal).add(amt)
        }
    }

    List<Map> lines = []
    BigDecimal grandDebit = BigDecimal.ZERO
    BigDecimal grandCredit = BigDecimal.ZERO
    bucket.values().sort { it.accountCode }.each { Map row ->
        BigDecimal d = (row.debitTotal as BigDecimal).setScale(2, RoundingMode.HALF_UP)
        BigDecimal c = (row.creditTotal as BigDecimal).setScale(2, RoundingMode.HALF_UP)
        BigDecimal balance = d.subtract(c)
        lines.add([
                glAccountId: row.glAccountId,
                accountCode: row.accountCode,
                accountName: row.accountName,
                debitTotal : d,
                creditTotal: c,
                balance    : balance,
                isDebit    : row.isDebit
        ])
        grandDebit = grandDebit.add(d)
        grandCredit = grandCredit.add(c)
    }

    boolean balanced = grandDebit.compareTo(grandCredit) == 0
    return [
            periodYearMonth  : periodYearMonth,
            lines            : lines,
            grandTotalDebit  : grandDebit.setScale(2, RoundingMode.HALF_UP),
            grandTotalCredit : grandCredit.setScale(2, RoundingMode.HALF_UP),
            balanced         : balanced
    ]
}
