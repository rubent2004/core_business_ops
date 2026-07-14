import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.math.RoundingMode
import java.sql.Timestamp

// =====================================================================
// CONTABILIZACIÓN DE DTE Y NÓMINA
// =====================================================================
// post#DteAcctgTrans     — Genera asiento desde DTE aceptado
// post#PayrollAcctgTrans — Genera asiento desde corrida de nómina aprobada
//
// Principios:
//   - Idempotencia: si ya hay AcctgTrans con la misma referencia (invoiceId
//     o payrollRunId), no se crea otro.
//   - Asiento balanceado: total débitos == total créditos. Si no balancea,
//     se hace rollback y se devuelve error.
//   - Trazabilidad: cada entry tiene description con referencia al rubro
//     y al ID origen.
//   - Mapeo configurable: SvDteAccountMapping y SvPayrollAccountMapping
//     son seed editables — las cuentas pueden cambiarse sin tocar código.
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
 * Resuelve la cuenta GL mapeada para un (tipoDteCode, direction, rubro).
 * Devuelve [glAccountId, debitCreditFlag] o null si no hay mapeo.
 */
List resolveDteAccount(ExecutionContext ec, String tipoDteCode, String direction, String rubro) {
    EntityValue map = ec.entity.find('sv.localization.accounting.SvDteAccountMapping')
            .condition([tipoDteCode: tipoDteCode, direction: direction, rubro: rubro])
            .one()
    if (map == null) return null
    return [(String) map.glAccountId, (String) map.debitCreditFlag]
}

/**
 * Crea una entrada del asiento. Devuelve el monto absoluto sumado al lado correspondiente.
 */
Map createEntry(ExecutionContext ec, String acctgTransId, String seqId,
                String glAccountId, String debitCreditFlag, BigDecimal amount,
                String description) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
        return [debit: BigDecimal.ZERO, credit: BigDecimal.ZERO]
    }
    BigDecimal absAmt = amount.abs().setScale(2, RoundingMode.HALF_UP)
    ec.entity.makeValue('mantle.ledger.transaction.AcctgTransEntry').setAll([
            acctgTransId        : acctgTransId,
            acctgTransEntrySeqId: seqId,
            debitCreditFlag     : debitCreditFlag,
            amount              : absAmt,
            description         : description,
            glAccountId         : glAccountId,
            reconcileStatusId   : 'AterNot'
    ]).create()
    return debitCreditFlag == 'D' ?
            [debit: absAmt, credit: BigDecimal.ZERO] :
            [debit: BigDecimal.ZERO, credit: absAmt]
}

// =====================================================================
// post#DteAcctgTrans
// =====================================================================

Object postDteAcctgTrans() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    String direction = (String) (ctx('direction') ?: 'O')

    EntityValue dte = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('dteEmisionId', dteEmisionId).one()
    if (dte == null) {
        ec.message.addError("DTE ${dteEmisionId} no encontrado")
        return [:]
    }
    String statusId = (String) dte.statusId
    if (!(statusId in ['DteAccepted', 'DteMockAccepted'])) {
        ec.message.addError("DTE ${dteEmisionId} no esta aceptado (status ${statusId}); no se puede postear")
        return [:]
    }

    String invoiceId = (String) dte.invoiceId
    String tipoDteCode = (String) dte.tipoDteCode
    if (!(tipoDteCode in ['01', '03', '05', '06', '11', '14'])) {
        ec.message.addError("Tipo DTE ${tipoDteCode} no soportado para posting automatico")
        return [:]
    }

    EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice')
            .condition('invoiceId', invoiceId).one()
    if (invoice == null) {
        ec.message.addError("Invoice ${invoiceId} no encontrada (referenciada por DTE)")
        return [:]
    }

    String organizationPartyId = direction == 'O' ?
            (String) invoice.fromPartyId : (String) invoice.toPartyId

    // Idempotencia: ya existe AcctgTrans con invoiceId + reference al DTE?
    EntityValue existing = ec.entity.find('mantle.ledger.transaction.AcctgTrans')
            .condition('invoiceId', invoiceId)
            .condition('isPosted', 'Y').one()
    if (existing != null) {
        return [
                acctgTransId  : existing.acctgTransId,
                alreadyPosted : true,
                entriesCreated: 0,
                totalDebit    : BigDecimal.ZERO.setScale(2),
                totalCredit   : BigDecimal.ZERO.setScale(2)
        ]
    }

    // Calcular subtotales del DTE
    BigDecimal subTotal = BigDecimal.ZERO
    BigDecimal ivaAmt = BigDecimal.ZERO
    BigDecimal reteRenta = BigDecimal.ZERO
    BigDecimal totalAmt = BigDecimal.ZERO

    // Leer del JSON del DTE para tener datos confiables (no del Invoice, que
    // podría estar desincronizado)
    String jsonText = (String) dte.jsonOriginal
    if (jsonText) {
        groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()
        Map dteJson = (Map) slurper.parseText(jsonText)
        Map resumen = (Map) dteJson?.resumen
        if (resumen != null) {
            if (tipoDteCode == '14') {
                // FSE: usa totalCompra y reteRenta
                subTotal = money(resumen.totalCompra)
                reteRenta = money(resumen.reteRenta)
                totalAmt = money(resumen.totalPagar)
            } else if (tipoDteCode == '01') {
                // FC: IVA incluido en montoTotalOperacion; totalIva separado
                subTotal = money(resumen.subTotalVentas).subtract(money(resumen.totalIva))
                ivaAmt = money(resumen.totalIva)
                totalAmt = money(resumen.totalPagar)
            } else if (tipoDteCode == '11') {
                // FEX: sin IVA
                subTotal = money(resumen.subTotalVentas ?: resumen.totalGravada)
                totalAmt = money(resumen.totalPagar ?: subTotal)
            } else {
                // CCF 03 / NC 05 / ND 06: IVA explícito
                subTotal = money(resumen.subTotalVentas ?: resumen.totalGravada)
                List tributos = (List) resumen.tributos
                if (tributos != null) {
                    tributos.each { Map t -> ivaAmt = ivaAmt.add(money(t.valor)) }
                }
                if (ivaAmt.compareTo(BigDecimal.ZERO) == 0) {
                    ivaAmt = money(resumen.totalIva)
                }
                totalAmt = money(resumen.totalPagar ?: subTotal.add(ivaAmt))
            }
        }
    }
    if (totalAmt.compareTo(BigDecimal.ZERO) == 0) {
        // Fallback: usar invoiceTotal si JSON estaba vacío
        totalAmt = money(invoice.invoiceTotal)
        subTotal = totalAmt
    }

    // Crear AcctgTrans cabecera
    String acctgTransId = ec.entity.sequencedIdPrimary('mantle.ledger.transaction.AcctgTrans', null, null)
    Timestamp txDate = (Timestamp) (dte.fechaGeneracion ?: invoice.invoiceDate ?: ec.user.nowTimestamp)

    String acctgTransType = direction == 'O' ? 'AttSalesInvoice' : 'AttPurchaseInvoice'
    ec.entity.makeValue('mantle.ledger.transaction.AcctgTrans').setAll([
            acctgTransId       : acctgTransId,
            acctgTransTypeEnumId: acctgTransType,
            organizationPartyId: organizationPartyId,
            description        : "DTE ${tipoDteCode} ${dte.numeroControl} (${direction == 'O' ? 'venta' : 'compra'})",
            transactionDate    : txDate,
            isPosted           : 'Y',
            postedDate         : ec.user.nowTimestamp,
            amountUomId        : invoice.currencyUomId ?: 'USD',
            invoiceId          : invoiceId,
            otherPartyId       : direction == 'O' ? invoice.toPartyId : invoice.fromPartyId
    ]).create()

    int seqIdx = 1
    BigDecimal totalDebit = BigDecimal.ZERO
    BigDecimal totalCredit = BigDecimal.ZERO

    // Helper para crear entry con mapeo
    def postRubro = { String rubro, BigDecimal amount, String desc ->
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return
        List<String> resolved = resolveDteAccount(ec, tipoDteCode, direction, rubro)
        if (resolved == null) {
            ec.message.addError("Sin mapeo contable para tipoDte=${tipoDteCode} direction=${direction} rubro=${rubro}")
            return
        }
        Map result = createEntry(ec, acctgTransId, String.format('%05d', seqIdx),
                resolved[0], resolved[1], amount, desc)
        seqIdx++
        totalDebit = totalDebit.add((BigDecimal) result.debit)
        totalCredit = totalCredit.add((BigDecimal) result.credit)
    }

    // Por tipo DTE construir el asiento
    if (tipoDteCode in ['01', '03', '11']) {
        // FC / CCF venta / FEX: total a CxC, ventas neto y IVA por separado
        postRubro('MAIN', totalAmt, "Total DTE ${tipoDteCode}")
        postRubro('VENTAS', subTotal, "Ventas neto ${tipoDteCode}")
        if (ivaAmt.compareTo(BigDecimal.ZERO) > 0) {
            postRubro('IVA', ivaAmt, "IVA debito fiscal ${tipoDteCode}")
        }
    } else if (tipoDteCode == '05') {
        // NC venta — direcciones inversas a CCF
        postRubro('MAIN', totalAmt, "NC ${dte.numeroControl}")
        postRubro('VENTAS', subTotal, "Devolucion sobre ventas")
        if (ivaAmt.compareTo(BigDecimal.ZERO) > 0) {
            postRubro('IVA', ivaAmt, "Reversa IVA debito fiscal")
        }
    } else if (tipoDteCode == '06') {
        postRubro('MAIN', totalAmt, "ND ${dte.numeroControl}")
        postRubro('VENTAS', subTotal, "Ventas adicional (ND)")
        if (ivaAmt.compareTo(BigDecimal.ZERO) > 0) {
            postRubro('IVA', ivaAmt, "IVA debito adicional (ND)")
        }
    } else if (tipoDteCode == '03' && direction == 'I') {
        // CCF compra
        postRubro('MAIN', subTotal, "Inventario compra CCF")
        if (ivaAmt.compareTo(BigDecimal.ZERO) > 0) {
            postRubro('IVA', ivaAmt, "IVA credito fiscal")
        }
        postRubro('COMPRA', totalAmt, "Cuentas por pagar")
    } else if (tipoDteCode == '14') {
        // FSE compra
        postRubro('MAIN', subTotal, "Costo de servicio FSE")
        if (reteRenta.compareTo(BigDecimal.ZERO) > 0) {
            postRubro('RENTA_RETE', reteRenta, "Retencion renta 10% FSE")
        }
        postRubro('COMPRA', totalAmt, "Cuentas por pagar (neto)")
    }

    if (ec.message.hasError()) {
        return [:]
    }

    if (totalDebit.compareTo(totalCredit) != 0) {
        ec.message.addError("Asiento desbalanceado: debitos=${totalDebit} creditos=${totalCredit}")
        return [:]
    }

    return [
            acctgTransId  : acctgTransId,
            entriesCreated: seqIdx - 1,
            alreadyPosted : false,
            totalDebit    : totalDebit,
            totalCredit   : totalCredit
    ]
}

// =====================================================================
// post#PayrollAcctgTrans
// =====================================================================

/**
 * Resuelve la cuenta GL para un rubro de nómina.
 * Devuelve [glAccountId, debitCreditFlag] o null.
 */
List resolvePayrollAccount(ExecutionContext ec, String rubro) {
    EntityValue map = ec.entity.find('sv.localization.accounting.SvPayrollAccountMapping')
            .condition('rubro', rubro).one()
    if (map == null) return null
    return [(String) map.glAccountId, (String) map.debitCreditFlag]
}

Object postPayrollAcctgTrans() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String svPayrollRunId = (String) ctx('svPayrollRunId')

    EntityValue run = ec.entity.find('sv.localization.payroll.SvPayrollRun')
            .condition('svPayrollRunId', svPayrollRunId).useCache(false).one()
    if (run == null) {
        ec.message.addError("Corrida ${svPayrollRunId} no encontrada")
        return [:]
    }
    String statusId = (String) run.statusId
    if (statusId == 'SvPayPosted' && run.postedAcctgTransId) {
        return [
                svPayrollRunId: svPayrollRunId,
                acctgTransId  : run.postedAcctgTransId,
                alreadyPosted : true,
                entriesCreated: 0,
                statusId      : statusId
        ]
    }
    if (statusId != 'SvPayApproved') {
        ec.message.addError("Solo corridas en estado SvPayApproved pueden contabilizarse (actual: ${statusId})")
        return [:]
    }

    // Crear cabecera AcctgTrans
    String acctgTransId = ec.entity.sequencedIdPrimary('mantle.ledger.transaction.AcctgTrans', null, null)
    Timestamp txDate = (Timestamp) (run.asOfDate ?: ec.user.nowTimestamp)

    ec.entity.makeValue('mantle.ledger.transaction.AcctgTrans').setAll([
            acctgTransId       : acctgTransId,
            acctgTransTypeEnumId: 'AttInternal',
            organizationPartyId: run.organizationPartyId,
            description        : "Nomina ${run.periodYearMonth} corrida ${svPayrollRunId}",
            transactionDate    : txDate,
            isPosted           : 'Y',
            postedDate         : ec.user.nowTimestamp,
            amountUomId        : run.currencyUomId ?: 'USD'
    ]).create()

    int seqIdx = 1
    BigDecimal totalDebit = BigDecimal.ZERO
    BigDecimal totalCredit = BigDecimal.ZERO

    def postRubro = { String rubro, BigDecimal amount, String desc ->
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return
        List<String> resolved = resolvePayrollAccount(ec, rubro)
        if (resolved == null) {
            ec.message.addError("Sin mapeo contable para rubro nomina ${rubro}")
            return
        }
        Map result = createEntry(ec, acctgTransId, String.format('%05d', seqIdx),
                resolved[0], resolved[1], amount, desc)
        seqIdx++
        totalDebit = totalDebit.add((BigDecimal) result.debit)
        totalCredit = totalCredit.add((BigDecimal) result.credit)
    }

    // Construir asiento:
    //   Debe:  Sueldos gasto      (totalGross)
    //          ISSS patronal gasto (totalIsssEmployer)
    //          AFP patronal gasto  (totalAfpEmployer)
    //   Haber: ISSS empleado pasivo (totalIsssEmployee)
    //          AFP empleado pasivo  (totalAfpEmployee)
    //          Renta retenida       (totalRenta)
    //          ISSS patronal pasivo (totalIsssEmployer)
    //          AFP patronal pasivo  (totalAfpEmployer)
    //          Sueldos por pagar    (totalNet)

    BigDecimal totalGross = money(run.totalGross)
    BigDecimal isssEmp = money(run.totalIsssEmployee)
    BigDecimal afpEmp = money(run.totalAfpEmployee)
    BigDecimal renta = money(run.totalRenta)
    BigDecimal isssEmpr = money(run.totalIsssEmployer)
    BigDecimal afpEmpr = money(run.totalAfpEmployer)
    BigDecimal netPay = money(run.totalNet)

    postRubro('SUELDOS_GASTO', totalGross, "Sueldos brutos periodo ${run.periodYearMonth}")
    postRubro('ISSS_PATR_GASTO', isssEmpr, "ISSS patronal periodo ${run.periodYearMonth}")
    postRubro('AFP_PATR_GASTO', afpEmpr, "AFP patronal periodo ${run.periodYearMonth}")

    postRubro('ISSS_EMP_PASIVO', isssEmp, "ISSS empleado")
    postRubro('AFP_EMP_PASIVO', afpEmp, "AFP empleado")
    postRubro('RENTA_PASIVO', renta, "ISR retenido")
    postRubro('ISSS_PATR_PASIVO', isssEmpr, "ISSS patronal por pagar")
    postRubro('AFP_PATR_PASIVO', afpEmpr, "AFP patronal por pagar")
    postRubro('NETO_PAGAR', netPay, "Sueldos netos por pagar")

    if (ec.message.hasError()) {
        return [:]
    }
    if (totalDebit.compareTo(totalCredit) != 0) {
        ec.message.addError("Asiento de nomina desbalanceado: debitos=${totalDebit} creditos=${totalCredit}")
        return [:]
    }

    // Marcar corrida como contabilizada
    run.setAll([statusId: 'SvPayPosted', postedAcctgTransId: acctgTransId])
    run.update()

    // Marcar lineas aprobadas como pagadas (snapshot final)
    ec.entity.find('sv.localization.payroll.SvPayrollRunEmployee')
            .condition('svPayrollRunId', svPayrollRunId)
            .condition('statusId', 'SvPayLineApproved').useCache(false).list().each {
        it.statusId = 'SvPayLinePaid'; it.update()
    }

    return [
            svPayrollRunId: svPayrollRunId,
            acctgTransId  : acctgTransId,
            entriesCreated: seqIdx - 1,
            alreadyPosted : false,
            totalDebit    : totalDebit,
            totalCredit   : totalCredit,
            statusId      : 'SvPayPosted'
    ]
}
