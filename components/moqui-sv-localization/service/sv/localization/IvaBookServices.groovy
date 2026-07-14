import groovy.json.JsonSlurper
import groovy.transform.Field
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import javax.xml.transform.stream.StreamSource

@Field final String BOOK_SALES = 'SvBookSales'
@Field final String BOOK_PURCHASES = 'SvBookPurchases'
@Field final List<String> SUPPORTED_DTE_TYPES = ['01', '03', '05', '06']

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

String clean(Object raw) {
    return raw == null ? null : raw.toString().trim()
}

boolean boolValue(Object raw, boolean fallback) {
    if (raw == null || raw == '') return fallback
    if (raw instanceof Boolean) return raw
    return clean(raw).equalsIgnoreCase('true') || clean(raw).equalsIgnoreCase('Y')
}

YearMonth parsePeriod(Object raw, ExecutionContext ec) {
    String text = clean(raw)
    if (!text) {
        LocalDate today = ec.user.nowTimestamp.toLocalDateTime().toLocalDate()
        return YearMonth.from(today)
    }
    if (!(text ==~ /\d{4}-\d{2}/)) {
        ec.message.addError('periodYearMonth debe tener formato YYYY-MM')
        return null
    }
    return YearMonth.parse(text)
}

LocalDate parseDteDate(Map dteJson, EntityValue dte) {
    String fecEmi = clean(dteJson?.identificacion?.fecEmi)
    if (fecEmi) return LocalDate.parse(fecEmi.substring(0, 10))
    Timestamp ts = (Timestamp) (dte.fechaProcesamientoMh ?: dte.fechaGeneracion)
    return ts?.toLocalDateTime()?.toLocalDate()
}

LocalDate invoiceDate(EntityValue invoice) {
    Object raw = invoice.invoiceDate
    if (raw instanceof Timestamp) return raw.toLocalDateTime().toLocalDate()
    if (raw instanceof Date) return raw.toLocalDate()
    return raw == null ? null : LocalDate.parse(raw.toString().substring(0, 10))
}

Map parseJson(String jsonText) {
    if (!jsonText) return [:]
    return (Map) new JsonSlurper().parseText(jsonText)
}

BigDecimal ivaFromResumen(Map resumen) {
    if (resumen == null) return BigDecimal.ZERO.setScale(2)
    if (resumen.totalIva != null) return money(resumen.totalIva)
    Object tributos = resumen.tributos
    if (tributos instanceof List) {
        Map ivaTributo = ((List<Map>) tributos).find { clean(it.codigo) == '20' }
        return money(ivaTributo?.valor)
    }
    return BigDecimal.ZERO.setScale(2)
}

BigDecimal totalFromResumen(Map resumen) {
    if (resumen == null) return BigDecimal.ZERO.setScale(2)
    if (resumen.totalPagar != null) return money(resumen.totalPagar)
    if (resumen.montoTotalOperacion != null) return money(resumen.montoTotalOperacion)
    return money(decimal(resumen.totalGravada).add(ivaFromResumen(resumen)))
}

String counterPartyId(Map party) {
    return clean(party?.nrc) ?: clean(party?.nit) ?: clean(party?.numDocumento) ?: clean(party?.numeroDocumento)
}

String counterPartyName(Map party, String fallback = 'Consumidor final') {
    return clean(party?.nombre) ?: clean(party?.nombreComercial) ?: fallback
}

String partyName(ExecutionContext ec, String partyId) {
    if (!partyId) return null
    EntityValue org = ec.entity.find('mantle.party.Organization').condition('partyId', partyId).one()
    if (org?.organizationName) return org.organizationName
    EntityValue person = ec.entity.find('mantle.party.Person').condition('partyId', partyId).one()
    if (person != null) {
        return [person.firstName, person.middleName, person.lastName].findAll { it }.join(' ')
    }
    return partyId
}

String tipoDteFromInvoice(EntityValue invoice) {
    if (invoice.tipoDteCode) return invoice.tipoDteCode
    switch (invoice.invoiceTypeEnumId) {
        case 'InvSvFc': return '01'
        case 'InvSvCcf': return '03'
        case 'InvSvNc': return '05'
        case 'InvSvNd': return '06'
        default: return null
    }
}

Map invoiceResumen(ExecutionContext ec, EntityValue invoice) {
    List<EntityValue> items = ec.entity.find('mantle.account.invoice.InvoiceItem')
            .condition('invoiceId', invoice.invoiceId)
            .list()
    BigDecimal gravado = BigDecimal.ZERO
    BigDecimal iva = BigDecimal.ZERO
    items.each { EntityValue item ->
        BigDecimal qty = decimal(item.quantity, BigDecimal.ONE)
        BigDecimal lineAmount = decimal(item.amount).multiply(qty)
        String itemType = clean(item.itemTypeEnumId)
        if (itemType in ['ItemSalesTax', 'ItemVatTax']) {
            iva = iva.add(lineAmount)
        } else if (item.isAdjustment != 'Y') {
            gravado = gravado.add(lineAmount)
        }
    }

    BigDecimal total = money(invoice.invoiceTotal ?: gravado.add(iva))
    if (iva.compareTo(BigDecimal.ZERO) == 0 && total.compareTo(gravado) > 0) {
        iva = total.subtract(gravado)
    }
    return [totalNoSuj: 0.00G, totalExenta: 0.00G, totalGravada: money(gravado),
            totalIva: money(iva), ivaRete: 0.00G, ivaPerci: 0.00G, totalPagar: total]
}

Map entryFieldsFromDte(ExecutionContext ec, EntityValue dte, EntityValue invoice, String bookTypeEnumId, int correlativo, String periodText) {
    Map dteJson = parseJson(dte.jsonOriginal as String)
    Map resumen = (Map) dteJson.resumen
    // FSE (14): la emite la organización pero documenta una COMPRA a un sujeto excluido.
    // La contraparte es el receptor (sujeto excluido) y la organización es el emisor del DTE.
    boolean isFse = dte.tipoDteCode == '14'
    Map counterParty = (bookTypeEnumId == BOOK_PURCHASES && !isFse) ? (Map) dteJson.emisor : (Map) dteJson.receptor
    String fallbackPartyId = (bookTypeEnumId == BOOK_PURCHASES && !isFse) ? invoice.fromPartyId : invoice.toPartyId
    String organizationPartyId = isFse ? invoice.fromPartyId
            : (bookTypeEnumId == BOOK_PURCHASES ? invoice.toPartyId : invoice.fromPartyId)
    LocalDate fechaEmision = parseDteDate(dteJson, dte)
    BigDecimal sign = dte.tipoDteCode == '05' ? new BigDecimal('-1') : BigDecimal.ONE

    Map montos = isFse ? fseAmounts(resumen) : ivaAmounts(resumen, sign)

    return [
            bookTypeEnumId    : bookTypeEnumId,
            periodYearMonth   : periodText,
            organizationPartyId: organizationPartyId,
            invoiceId         : dte.invoiceId,
            dteEmisionId      : dte.dteEmisionId,
            dteFiscalEventId  : null,
            dteSourceStatusId : dte.statusId,
            numeroCorrelativo : correlativo,
            fechaEmision      : fechaEmision == null ? null : Date.valueOf(fechaEmision),
            tipoDteCode       : dte.tipoDteCode,
            nitNrcCounterParty: counterPartyId(counterParty) ?: fallbackPartyId,
            nombreCounterParty: counterPartyName(counterParty, partyName(ec, fallbackPartyId) ?: fallbackPartyId),
            montoExento       : montos.montoExento,
            montoNoSujeto     : montos.montoNoSujeto,
            montoGravado      : montos.montoGravado,
            iva               : montos.iva,
            iva1pctRetenido   : montos.iva1pctRetenido,
            iva1pctPercibido  : montos.iva1pctPercibido,
            total             : montos.total
    ]
}

/** Montos IVA estándar (FC/CCF/NC/ND) aplicando signo (NC resta). */
Map ivaAmounts(Map resumen, BigDecimal sign) {
    return [
            montoExento     : money(resumen?.totalExenta).multiply(sign),
            montoNoSujeto   : money(resumen?.totalNoSuj).multiply(sign),
            montoGravado    : money(resumen?.totalGravada).multiply(sign),
            iva             : ivaFromResumen(resumen).multiply(sign),
            iva1pctRetenido : money(resumen?.ivaRete ?: resumen?.ivaRete1).multiply(sign),
            iva1pctPercibido: money(resumen?.ivaPerci ?: resumen?.ivaPerci1).multiply(sign),
            total           : totalFromResumen(resumen).multiply(sign)
    ]
}

/**
 * Montos de una Factura de Sujeto Excluido (FSE 14) para el libro de COMPRAS.
 * El sujeto excluido no cobra IVA: el monto de la compra va a montoGravado sin
 * IVA acreditable. La reteRenta del FSE es retención de RENTA (no de IVA) y por
 * tanto no se refleja en las columnas IVA de este libro operativo.
 */
Map fseAmounts(Map resumen) {
    BigDecimal compra = money(resumen?.totalCompra ?: resumen?.subTotal)
    return [
            montoExento     : 0.00G,
            montoNoSujeto   : 0.00G,
            montoGravado    : compra,
            iva             : 0.00G,
            iva1pctRetenido : 0.00G,
            iva1pctPercibido: 0.00G,
            total           : compra
    ]
}

Map entryFieldsFromInvoice(ExecutionContext ec, EntityValue invoice, String bookTypeEnumId, int correlativo, String periodText) {
    Map resumen = invoiceResumen(ec, invoice)
    String tipoDteCode = tipoDteFromInvoice(invoice)
    String organizationPartyId = bookTypeEnumId == BOOK_PURCHASES ? invoice.toPartyId : invoice.fromPartyId
    String counterPartyId = bookTypeEnumId == BOOK_PURCHASES ? invoice.fromPartyId : invoice.toPartyId
    LocalDate fechaEmision = invoiceDate(invoice)
    BigDecimal sign = tipoDteCode == '05' ? new BigDecimal('-1') : BigDecimal.ONE

    return [
            bookTypeEnumId    : bookTypeEnumId,
            periodYearMonth   : periodText,
            organizationPartyId: organizationPartyId,
            invoiceId         : invoice.invoiceId,
            dteEmisionId      : null,
            dteFiscalEventId  : null,
            dteSourceStatusId : null,
            numeroCorrelativo : correlativo,
            fechaEmision      : fechaEmision == null ? null : Date.valueOf(fechaEmision),
            tipoDteCode       : tipoDteCode,
            nitNrcCounterParty: counterPartyId,
            nombreCounterParty: partyName(ec, counterPartyId) ?: counterPartyId,
            montoExento       : money(resumen.totalExenta).multiply(sign),
            montoNoSujeto     : money(resumen.totalNoSuj).multiply(sign),
            montoGravado      : money(resumen.totalGravada).multiply(sign),
            iva               : ivaFromResumen(resumen).multiply(sign),
            iva1pctRetenido   : money(resumen.ivaRete ?: resumen.ivaRete1).multiply(sign),
            iva1pctPercibido  : money(resumen.ivaPerci ?: resumen.ivaPerci1).multiply(sign),
            total             : totalFromResumen(resumen).multiply(sign)
    ]
}

/** Entrada del libro de COMPRAS desde un DTE recibido de proveedor (DteRecepcion).
 *  Los montos faciales se guardan positivos; la NC (05) resta vía signo. */
Map entryFieldsFromRecepcion(EntityValue rec, String bookTypeEnumId, int correlativo, String periodText) {
    BigDecimal sign = rec.tipoDteCode == '05' ? new BigDecimal('-1') : BigDecimal.ONE
    Timestamp fecha = (Timestamp) rec.fechaEmision
    return [
            bookTypeEnumId    : bookTypeEnumId,
            periodYearMonth   : periodText,
            organizationPartyId: rec.receptorPartyId,
            invoiceId         : null,
            dteEmisionId      : null,
            dteRecepcionId    : rec.dteRecepcionId,
            dteFiscalEventId  : null,
            dteSourceStatusId : 'DteAccepted',
            numeroCorrelativo : correlativo,
            fechaEmision      : fecha == null ? null : Date.valueOf(fecha.toLocalDateTime().toLocalDate()),
            tipoDteCode       : rec.tipoDteCode,
            nitNrcCounterParty: clean(rec.emisorNrc) ?: clean(rec.emisorNit),
            nombreCounterParty: clean(rec.emisorNombre) ?: clean(rec.emisorNit),
            montoExento       : money(rec.montoExento).multiply(sign),
            montoNoSujeto     : money(rec.montoNoSujeto).multiply(sign),
            montoGravado      : money(rec.montoGravado).multiply(sign),
            iva               : money(rec.iva).multiply(sign),
            iva1pctRetenido   : money(rec.iva1pctRetenido).multiply(sign),
            iva1pctPercibido  : money(rec.iva1pctPercibido).multiply(sign),
            total             : money(rec.total).multiply(sign)
    ]
}

Map entryFieldsFromEretEvent(ExecutionContext ec, EntityValue ev, EntityValue dte, EntityValue invoice, String bookTypeEnumId, int correlativo, String periodText) {
    Map eventJson = parseJson(ev.jsonOriginal as String)
    Map dteJson = parseJson(dte.jsonOriginal as String)
    Map resumen = (Map) eventJson.resumen
    Map receptor = (Map) eventJson.documento ?: (Map) dteJson.receptor ?: [:]
    Timestamp eventTs = (Timestamp) ev.fechaProcesamientoMh ?: (Timestamp) ev.fechaTransmision ?: (Timestamp) ev.fechaGeneracion
    LocalDate fecha = eventJson?.identificacion?.fecEmi ? LocalDate.parse(eventJson.identificacion.fecEmi.toString().substring(0, 10))
            : eventTs?.toLocalDateTime()?.toLocalDate()
    Map amounts = ivaAmounts(resumen, new BigDecimal('-1'))
    return [
            bookTypeEnumId    : bookTypeEnumId,
            periodYearMonth   : periodText,
            organizationPartyId: bookTypeEnumId == BOOK_PURCHASES ? invoice.fromPartyId : invoice.fromPartyId,
            invoiceId         : null,
            dteEmisionId      : dte.dteEmisionId,
            dteRecepcionId    : null,
            dteFiscalEventId  : ev.dteFiscalEventId,
            dteSourceStatusId : ev.statusId,
            numeroCorrelativo : correlativo,
            fechaEmision      : fecha == null ? null : Date.valueOf(fecha),
            tipoDteCode       : '18',
            nitNrcCounterParty: counterPartyId(receptor) ?: invoice.toPartyId,
            nombreCounterParty: counterPartyName(receptor, partyName(ec, invoice.toPartyId) ?: invoice.toPartyId),
            montoExento       : amounts.montoExento,
            montoNoSujeto     : amounts.montoNoSujeto,
            montoGravado      : amounts.montoGravado,
            iva               : amounts.iva,
            iva1pctRetenido   : amounts.iva1pctRetenido,
            iva1pctPercibido  : amounts.iva1pctPercibido,
            total             : amounts.total
    ]
}

List<EntityValue> listEntries(ExecutionContext ec, String organizationPartyId, String periodText, String bookTypeEnumId) {
    return ec.entity.find('sv.localization.tax.SvIvaBookEntry')
            .condition('organizationPartyId', organizationPartyId)
            .condition('bookTypeEnumId', bookTypeEnumId)
            .condition('periodYearMonth', periodText)
            .orderBy('numeroCorrelativo')
            .list()
}

Map totalsFor(List<EntityValue> entries) {
    Map totals = [
            montoExento      : BigDecimal.ZERO.setScale(2),
            montoNoSujeto    : BigDecimal.ZERO.setScale(2),
            montoGravado     : BigDecimal.ZERO.setScale(2),
            iva              : BigDecimal.ZERO.setScale(2),
            iva1pctRetenido  : BigDecimal.ZERO.setScale(2),
            iva1pctPercibido : BigDecimal.ZERO.setScale(2),
            total            : BigDecimal.ZERO.setScale(2)
    ]
    entries.each { EntityValue entry ->
        totals.keySet().each { String key -> totals[key] = money(((BigDecimal) totals[key]).add(decimal(entry[key]))) }
    }
    totals.entryCount = entries.size()
    return totals
}

List<String> acceptedStatuses(boolean includeMockAccepted) {
    return includeMockAccepted ? ['DteAccepted', 'DteMockAccepted'] : ['DteAccepted']
}

List<String> acceptedEventStatuses(boolean includeMockAccepted) {
    return includeMockAccepted ? ['DfeAccepted', 'DfeMockAccepted'] : ['DfeAccepted']
}

List<Map> candidatesForEretEvents(ExecutionContext ec, String organizationPartyId, YearMonth period, boolean includeMockAccepted, String bookTypeEnumId) {
    List<EntityValue> events = ec.entity.find('sv.localization.dte.DteFiscalEvent')
            .condition('tipoEventoCode', '18')
            .condition('statusId', EntityCondition.IN, acceptedEventStatuses(includeMockAccepted))
            .orderBy('fechaGeneracion')
            .list()
    List<Map> candidates = []
    for (EntityValue ev in events) {
        if (!ev.jsonOriginal) continue
        Map eventJson = parseJson(ev.jsonOriginal as String)
        String codGenOriginal = clean(((List) eventJson.documentoRelacionado)?.getAt(0)?.codigoGeneracion)
        if (!codGenOriginal) continue
        EntityValue dte = ec.entity.find('sv.localization.dte.DteEmision').condition('codigoGeneracion', codGenOriginal).one()
        if (dte == null || !dte.invoiceId || !dte.jsonOriginal) continue
        EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', dte.invoiceId).one()
        if (invoice == null || invoice.fromPartyId != organizationPartyId) continue
        boolean goesToPurchases = dte.tipoDteCode == '14'
        if (bookTypeEnumId == BOOK_PURCHASES && !goesToPurchases) continue
        if (bookTypeEnumId == BOOK_SALES && goesToPurchases) continue
        Timestamp eventTs = (Timestamp) ev.fechaProcesamientoMh ?: (Timestamp) ev.fechaTransmision ?: (Timestamp) ev.fechaGeneracion
        LocalDate fecha = eventJson?.identificacion?.fecEmi ? LocalDate.parse(eventJson.identificacion.fecEmi.toString().substring(0, 10))
                : eventTs?.toLocalDateTime()?.toLocalDate()
        if (fecha == null || YearMonth.from(fecha) != period) continue
        candidates.add([source: 'eret', event: ev, dte: dte, invoice: invoice, fecha: fecha, numeroControl: clean(ev.codigoGeneracion)])
    }
    return candidates
}

List<Map> candidatesForSalesPeriod(ExecutionContext ec, String organizationPartyId, YearMonth period, boolean includeMockAccepted) {
    List<EntityValue> dtes = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', EntityCondition.IN, acceptedStatuses(includeMockAccepted))
            .condition('tipoDteCode', EntityCondition.IN, SUPPORTED_DTE_TYPES)
            .orderBy('fechaGeneracion')
            .list()

    List<Map> candidates = []
    for (EntityValue dte in dtes) {
        if (!dte.invoiceId || !dte.jsonOriginal) continue
        EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', dte.invoiceId).one()
        if (invoice == null || invoice.fromPartyId != organizationPartyId) continue
        Map jsonMap = parseJson(dte.jsonOriginal as String)
        LocalDate fecha = parseDteDate(jsonMap, dte)
        if (fecha == null || YearMonth.from(fecha) != period) continue
        candidates.add([source: 'dte', dte: dte, invoice: invoice, fecha: fecha, numeroControl: clean(dte.numeroControl)])
    }
    candidates.addAll(candidatesForEretEvents(ec, organizationPartyId, period, includeMockAccepted, BOOK_SALES))
    return sortCandidates(candidates)
}

List<Map> candidatesForPurchasesPeriod(ExecutionContext ec, String organizationPartyId, YearMonth period, boolean includeMockAccepted) {
    List<EntityValue> invoices = ec.entity.find('mantle.account.invoice.Invoice')
            .condition('toPartyId', organizationPartyId)
            .condition('invoiceTypeEnumId', EntityCondition.IN, ['InvSvFc', 'InvSvCcf', 'InvSvNc', 'InvSvNd'])
            .orderBy('invoiceDate')
            .list()

    List<Map> candidates = []
    for (EntityValue invoice in invoices) {
        String tipoDteCode = tipoDteFromInvoice(invoice)
        if (!(tipoDteCode in SUPPORTED_DTE_TYPES)) continue
        EntityValue dte = ec.entity.find('sv.localization.dte.DteEmision').condition('invoiceId', invoice.invoiceId).one()
        if (dte != null) {
            if (!(dte.statusId in acceptedStatuses(includeMockAccepted)) || !dte.jsonOriginal) continue
            Map jsonMap = parseJson(dte.jsonOriginal as String)
            LocalDate fecha = parseDteDate(jsonMap, dte)
            if (fecha == null || YearMonth.from(fecha) != period) continue
            candidates.add([source: 'dte', dte: dte, invoice: invoice, fecha: fecha, numeroControl: clean(dte.numeroControl)])
        } else {
            LocalDate fecha = invoiceDate(invoice)
            if (fecha == null || YearMonth.from(fecha) != period) continue
            candidates.add([source: 'invoice', invoice: invoice, fecha: fecha, numeroControl: clean(invoice.invoiceId)])
        }
    }

    // FSE 14: emitida por la organización pero documenta una compra a sujeto excluido.
    // En Mantle la factura es de venta (fromPartyId = org), por eso no la capta la query
    // anterior (toPartyId = org). Se busca por DteEmision tipo 14 aceptado de la org.
    List<EntityValue> fseDtes = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', EntityCondition.IN, acceptedStatuses(includeMockAccepted))
            .condition('tipoDteCode', '14')
            .orderBy('fechaGeneracion')
            .list()
    for (EntityValue dte in fseDtes) {
        if (!dte.invoiceId || !dte.jsonOriginal) continue
        EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', dte.invoiceId).one()
        if (invoice == null || invoice.fromPartyId != organizationPartyId) continue
        Map jsonMap = parseJson(dte.jsonOriginal as String)
        LocalDate fecha = parseDteDate(jsonMap, dte)
        if (fecha == null || YearMonth.from(fecha) != period) continue
        candidates.add([source: 'dte', dte: dte, invoice: invoice, fecha: fecha, numeroControl: clean(dte.numeroControl)])
    }

    // DTE recibidos de proveedor (registro independiente DteRecepcion): se incluyen los
    // validados por schema y con sello, del periodo. Son evidencia real de compras.
    List<EntityValue> recibidos = ec.entity.find('sv.localization.dte.DteRecepcion')
            .condition('receptorPartyId', organizationPartyId)
            .condition('validatedBySchema', 'Y')
            .orderBy('fechaEmision')
            .list()
    for (EntityValue rec in recibidos) {
        if (!rec.selloRecepcion) continue
        Timestamp ts = (Timestamp) rec.fechaEmision
        LocalDate fecha = ts?.toLocalDateTime()?.toLocalDate()
        if (fecha == null || YearMonth.from(fecha) != period) continue
        candidates.add([source: 'recepcion', recepcion: rec, fecha: fecha, numeroControl: clean(rec.numeroControl)])
    }
    candidates.addAll(candidatesForEretEvents(ec, organizationPartyId, period, includeMockAccepted, BOOK_PURCHASES))
    return sortCandidates(candidates)
}

List<Map> sortCandidates(List<Map> candidates) {
    return candidates.sort { a, b ->
        int byDate = (a.fecha as LocalDate) <=> (b.fecha as LocalDate)
        byDate != 0 ? byDate : ((a.numeroControl ?: '') <=> (b.numeroControl ?: ''))
    }
}

Map fieldsForCandidate(ExecutionContext ec, Map candidate, String bookTypeEnumId, int correlativo, String periodText) {
    if (candidate.source == 'dte') {
        return entryFieldsFromDte(ec, (EntityValue) candidate.dte, (EntityValue) candidate.invoice, bookTypeEnumId, correlativo, periodText)
    }
    if (candidate.source == 'recepcion') {
        return entryFieldsFromRecepcion((EntityValue) candidate.recepcion, bookTypeEnumId, correlativo, periodText)
    }
    if (candidate.source == 'eret') {
        return entryFieldsFromEretEvent(ec, (EntityValue) candidate.event, (EntityValue) candidate.dte,
                (EntityValue) candidate.invoice, bookTypeEnumId, correlativo, periodText)
    }
    return entryFieldsFromInvoice(ec, (EntityValue) candidate.invoice, bookTypeEnumId, correlativo, periodText)
}

Object buildBook(String bookTypeEnumId) {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [:]

    String periodText = period.toString()
    boolean includeMockAccepted = boolValue(ctx('includeMockAccepted'), true)
    boolean rebuild = boolValue(ctx('rebuild'), true)
    int deleted = 0
    if (rebuild) {
        listEntries(ec, organizationPartyId, periodText, bookTypeEnumId).each {
            it.delete()
            deleted++
        }
    }

    List<Map> candidates = bookTypeEnumId == BOOK_PURCHASES
            ? candidatesForPurchasesPeriod(ec, organizationPartyId, period, includeMockAccepted)
            : candidatesForSalesPeriod(ec, organizationPartyId, period, includeMockAccepted)

    int created = 0
    int updated = 0
    int seq = 1
    for (Map candidate in candidates) {
        Map fields = fieldsForCandidate(ec, candidate, bookTypeEnumId, seq++, periodText)
        // Dedupe por la clave estable de la fuente: invoiceId, dteRecepcionId o dteFiscalEventId.
        def finder = ec.entity.find('sv.localization.tax.SvIvaBookEntry')
                .condition('bookTypeEnumId', bookTypeEnumId)
        if (candidate.source == 'recepcion') {
            finder.condition('dteRecepcionId', fields.dteRecepcionId)
        } else if (candidate.source == 'eret') {
            finder.condition('dteFiscalEventId', fields.dteFiscalEventId)
        } else {
            finder.condition('invoiceId', fields.invoiceId)
        }
        EntityValue existing = finder.one()
        if (existing == null) {
            existing = ec.entity.makeValue('sv.localization.tax.SvIvaBookEntry')
            existing.svIvaBookEntryId = ec.entity.sequencedIdPrimary('sv.localization.tax.SvIvaBookEntry', null, null)
            existing.setAll(fields)
            existing.create()
            created++
        } else {
            existing.setAll(fields)
            existing.update()
            updated++
        }
    }

    List<EntityValue> entries = listEntries(ec, organizationPartyId, periodText, bookTypeEnumId)
    return [createdCount: created, updatedCount: updated, deletedCount: deleted,
            entryList: entries, totals: totalsFor(entries)]
}

Object summaryForBook(String bookTypeEnumId) {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [:]
    List<EntityValue> entries = listEntries(ec, organizationPartyId, period.toString(), bookTypeEnumId)
    return [entryCount: entries.size(), totals: totalsFor(entries)]
}

String csvEscape(Object raw) {
    String text = raw == null ? '' : raw.toString()
    if (text.contains('"')) text = text.replace('"', '""')
    return (text.contains(',') || text.contains('\n') || text.contains('"')) ? "\"${text}\"" : text
}

/** Etiqueta de origen de la evidencia: REAL (sello MH), MOCK (simulador) o INTERNO (solo factura). */
String originLabel(Object dteSourceStatusId) {
    String s = clean(dteSourceStatusId)
    if (s == 'DteMockAccepted') return 'MOCK'
    if (s == 'DteAccepted') return 'REAL'
    if (s == 'DfeMockAccepted') return 'EVENTO MOCK'
    if (s == 'DfeAccepted') return 'EVENTO REAL'
    return 'INTERNO'
}

/** Serialización CSV canónica del libro (encabezados + filas + totales). Compartida por
 *  el export operativo y el snapshot inmutable para que el hash refleje lo exportado. */
String bookCsvText(List<EntityValue> entries, String bookTypeEnumId, Map totals) {
    String partyLabel = bookTypeEnumId == BOOK_PURCHASES ? 'Proveedor' : 'Receptor'
    List<String> headers = ['Correlativo', 'Fecha', 'Tipo DTE', 'Documento', partyLabel,
                            'Exento', 'No sujeto', 'Gravado', 'IVA', 'IVA retenido 1%',
                            'IVA percibido 1%', 'Total', 'Invoice ID', 'DTE Emision ID', 'Evento Fiscal ID', 'Origen']
    StringBuilder sb = new StringBuilder(headers.collect { csvEscape(it) }.join(',')).append('\n')
    entries.each { EntityValue e ->
        List row = [e.numeroCorrelativo, e.fechaEmision, e.tipoDteCode, e.nitNrcCounterParty, e.nombreCounterParty,
                    money(e.montoExento), money(e.montoNoSujeto), money(e.montoGravado), money(e.iva),
                    money(e.iva1pctRetenido), money(e.iva1pctPercibido), money(e.total),
                    e.invoiceId, e.dteEmisionId, e.dteFiscalEventId, originLabel(e.dteSourceStatusId)]
        sb.append(row.collect { csvEscape(it) }.join(',')).append('\n')
    }
    List totalRow = ['', '', '', '', 'TOTALES',
                     money(totals.montoExento), money(totals.montoNoSujeto), money(totals.montoGravado),
                     money(totals.iva), money(totals.iva1pctRetenido), money(totals.iva1pctPercibido),
                     money(totals.total), '', '', '', '']
    sb.append(totalRow.collect { csvEscape(it) }.join(',')).append('\n')
    return sb.toString()
}

Object exportBookCsv(String bookTypeEnumId) {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [:]

    List<EntityValue> entries = listEntries(ec, organizationPartyId, period.toString(), bookTypeEnumId)
    Map totals = totalsFor(entries)
    String filePrefix = bookTypeEnumId == BOOK_PURCHASES ? 'iva-compras' : 'iva-ventas'
    return [csvText: bookCsvText(entries, bookTypeEnumId, totals),
            filename: "${filePrefix}-${organizationPartyId}-${period}.csv",
            contentType: 'text/csv; charset=UTF-8', entryCount: entries.size(), totals: totals]
}

int mockCountOf(List<EntityValue> entries) {
    return entries.count { clean(it.dteSourceStatusId) == 'DteMockAccepted' } as int
}

String sha256Hex(String text) {
    byte[] digest = java.security.MessageDigest.getInstance('SHA-256')
            .digest((text ?: '').getBytes('UTF-8'))
    StringBuilder sb = new StringBuilder(digest.length * 2)
    for (byte b in digest) sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1))
    return sb.toString()
}

/**
 * snapshot#IvaBook — Congela el estado actual del libro (org, periodo, tipo) en una fila
 * inmutable SvIvaBookSnapshot: payload CSV idéntico al export, hash SHA-256, versión
 * correlativa, usuario y fecha. Append-only: nunca actualiza ni borra una versión previa.
 * No regenera el libro; congela lo que hay (registra cuántas entradas son mock para que el
 * snapshot sea transparente sobre su valor probatorio).
 */
Object snapshotBook(String bookTypeEnumId) {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [:]

    String periodText = period.toString()
    List<EntityValue> entries = listEntries(ec, organizationPartyId, periodText, bookTypeEnumId)
    Map totals = totalsFor(entries)
    String csvText = bookCsvText(entries, bookTypeEnumId, totals)
    String contentHash = sha256Hex(csvText)
    int mockCount = mockCountOf(entries)

    long priorVersions = ec.entity.find('sv.localization.tax.SvIvaBookSnapshot')
            .condition('organizationPartyId', organizationPartyId)
            .condition('bookTypeEnumId', bookTypeEnumId)
            .condition('periodYearMonth', periodText)
            .count()
    int versionNum = (priorVersions as int) + 1

    EntityValue snap = ec.entity.makeValue('sv.localization.tax.SvIvaBookSnapshot')
    snap.svIvaBookSnapshotId = ec.entity.sequencedIdPrimary('sv.localization.tax.SvIvaBookSnapshot', null, null)
    snap.setAll([
            bookTypeEnumId    : bookTypeEnumId,
            periodYearMonth   : periodText,
            organizationPartyId: organizationPartyId,
            versionNum        : versionNum,
            generatedByUserId : ec.user.userId,
            generatedDate     : ec.user.nowTimestamp,
            entryCount        : entries.size(),
            mockEntryCount    : mockCount,
            containsMock      : mockCount > 0 ? 'Y' : 'N',
            hashAlgorithm     : 'SHA-256',
            contentHash       : contentHash,
            payloadFormat     : 'CSV',
            payloadText       : csvText,
            totalGravado      : money(totals.montoGravado),
            totalIva          : money(totals.iva),
            total             : money(totals.total)
    ])
    snap.create()

    return [svIvaBookSnapshotId: snap.svIvaBookSnapshotId, versionNum: versionNum,
            contentHash: contentHash, hashAlgorithm: 'SHA-256', entryCount: entries.size(),
            mockEntryCount: mockCount, containsMock: mockCount > 0, totals: totals]
}

String templateNameForBook(String bookTypeEnumId) {
    switch (bookTypeEnumId) {
        case BOOK_SALES: return 'IvaSalesBook.xsl-fo.ftl'
        case BOOK_PURCHASES: return 'IvaPurchasesBook.xsl-fo.ftl'
        default: throw new IllegalArgumentException("Libro IVA ${bookTypeEnumId} no soportado")
    }
}

String titleForBook(String bookTypeEnumId) {
    switch (bookTypeEnumId) {
        case BOOK_SALES: return 'Libro IVA Ventas'
        case BOOK_PURCHASES: return 'Libro IVA Compras'
        default: return 'Libro IVA'
    }
}

String filenamePrefixForBook(String bookTypeEnumId) {
    return bookTypeEnumId == BOOK_PURCHASES ? 'iva-compras' : 'iva-ventas'
}

Object renderBookPdf(String bookTypeEnumId) {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [rendered: false]

    String periodText = period.toString()
    List<EntityValue> entries = listEntries(ec, organizationPartyId, periodText, bookTypeEnumId)
    Map totals = totalsFor(entries)
    String templateLocation = "component://moqui-sv-localization/template/book/${templateNameForBook(bookTypeEnumId)}"

    Map context = ec.context
    Map oldValues = [
            ivaBookEntryList : context.get('ivaBookEntryList'),
            ivaBookTotals    : context.get('ivaBookTotals'),
            ivaBookTitle     : context.get('ivaBookTitle'),
            ivaBookTypeEnumId: context.get('ivaBookTypeEnumId'),
            periodYearMonth  : context.get('periodYearMonth'),
            organizationPartyId: context.get('organizationPartyId'),
            generatedAt      : context.get('generatedAt')
    ]
    Map hadValues = oldValues.collectEntries { String key, Object value -> [(key): context.containsKey(key)] }
    try {
        context.put('ivaBookEntryList', entries)
        context.put('ivaBookTotals', totals)
        context.put('ivaBookTitle', titleForBook(bookTypeEnumId))
        context.put('ivaBookTypeEnumId', bookTypeEnumId)
        context.put('periodYearMonth', periodText)
        context.put('organizationPartyId', organizationPartyId)
        context.put('generatedAt', ec.user.nowTimestamp.toString())
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [rendered: true, templateLocation: templateLocation, xslFoText: xslFo,
                pdfBytes: baos.toByteArray(), pdfSize: baos.size(), pageCount: pageCount,
                filename: "${filenamePrefixForBook(bookTypeEnumId)}-${organizationPartyId}-${periodText}.pdf",
                contentType: 'application/pdf', entryCount: entries.size(), totals: totals]
    } finally {
        oldValues.each { String key, Object value ->
            if (hadValues[key]) context.put(key, value) else context.remove(key)
        }
    }
}

/**
 * get#CanceledDtesForPeriod — DTE emitidos por la organización que fueron anulados/invalidados
 * (DteEmision.statusId=DteCanceled) con fecha de emisión en el periodo. Sirve de control: explica
 * por qué un DTE no aparece en el libro y prepara el futuro anexo F-07 de documentos anulados.
 * No entra a los totales del libro (el libro solo incluye DteAccepted).
 */
Object getCanceledDtesForPeriod() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [canceledList: [], canceledCount: 0]

    List<EntityValue> dtes = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', 'DteCanceled')
            .orderBy('fechaGeneracion')
            .list()
    List<Map> rows = []
    for (EntityValue dte in dtes) {
        if (!dte.invoiceId) continue
        EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', dte.invoiceId).one()
        if (invoice == null || invoice.fromPartyId != organizationPartyId) continue
        Map jsonMap = parseJson(dte.jsonOriginal as String)
        LocalDate fecha = parseDteDate(jsonMap, dte)
        if (fecha == null || YearMonth.from(fecha) != period) continue
        EntityValue anul = ec.entity.find('sv.localization.dte.DteAnulacion')
                .condition('dteEmisionId', dte.dteEmisionId).one()
        rows.add([
                dteEmisionId    : dte.dteEmisionId,
                invoiceId       : dte.invoiceId,
                tipoDteCode     : dte.tipoDteCode,
                codigoGeneracion: dte.codigoGeneracion,
                numeroControl   : dte.numeroControl,
                fechaEmision    : Date.valueOf(fecha),
                selloRecepcion  : dte.selloRecepcion,
                anulStatusId    : anul?.statusId,
                selloAnulacion  : anul?.selloAnulacion,
                motivoAnulacion : anul?.motivo,
                fechaAnulacion  : anul?.fechaProcesamientoMh
        ])
    }
    return [canceledList: rows, canceledCount: rows.size()]
}

Object getFiscalEventsForPeriod() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    YearMonth period = parsePeriod(ctx('periodYearMonth'), ec)
    if (!organizationPartyId || period == null || ec.message.hasError()) return [eventList: [], eventCount: 0]
    EntityValue orgNitIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition('partyId', organizationPartyId)
            .condition('partyIdTypeEnumId', 'PitNit').one()
    String orgNit = clean(orgNitIdent?.idValue)?.replaceAll('[^0-9]', '')

    List<Map> rows = []
    List<EntityValue> events = ec.entity.find('sv.localization.dte.DteFiscalEvent')
            .condition('statusId', EntityCondition.IN, ['DfeAccepted', 'DfeMockAccepted'])
            .orderBy('fechaGeneracion')
            .list()
    for (EntityValue ev in events) {
        if (!ev.jsonOriginal) continue
        Map eventJson = parseJson(ev.jsonOriginal as String)
        Timestamp eventTs = (Timestamp) ev.fechaProcesamientoMh ?: (Timestamp) ev.fechaTransmision ?: (Timestamp) ev.fechaGeneracion
        LocalDate fecha = eventJson?.identificacion?.fecEmi ? LocalDate.parse(eventJson.identificacion.fecEmi.toString().substring(0, 10))
                : eventTs?.toLocalDateTime()?.toLocalDate()
        if (fecha == null || YearMonth.from(fecha) != period) continue
        String codGenOriginal = clean(((List) eventJson.documentoRelacionado)?.getAt(0)?.codigoGeneracion)
        EntityValue dte = codGenOriginal ? ec.entity.find('sv.localization.dte.DteEmision').condition('codigoGeneracion', codGenOriginal).one() : null
        EntityValue invoice = dte?.invoiceId ? ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', dte.invoiceId).one() : null
        if (ev.tipoEventoCode == '18') {
            if (invoice == null || invoice.fromPartyId != organizationPartyId) continue
        } else if (ev.tipoEventoCode == '17') {
            String nit = clean(eventJson?.emisor?.nit)?.replaceAll('[^0-9]', '')
            if (orgNit && nit && orgNit != nit) continue
        }
        Map resumen = (Map) eventJson.resumen
        rows.add([
                dteFiscalEventId  : ev.dteFiscalEventId,
                tipoEventoCode    : ev.tipoEventoCode,
                codigoGeneracion  : ev.codigoGeneracion,
                statusId          : ev.statusId,
                selloRecepcion    : ev.selloRecepcion,
                fechaEvento       : Date.valueOf(fecha),
                codigoGeneracionRef: codGenOriginal,
                dteEmisionId      : dte?.dteEmisionId,
                montoEvento       : totalFromResumen(resumen),
                observacionesMh   : ev.observacionesMh
        ])
    }
    return [eventList: rows, eventCount: rows.size()]
}

Object buildIvaSalesBook() { return buildBook(BOOK_SALES) }

Object getIvaSalesBookSummary() { return summaryForBook(BOOK_SALES) }

Object exportIvaSalesBookCsv() { return exportBookCsv(BOOK_SALES) }

Object renderIvaSalesBookPdf() { return renderBookPdf(BOOK_SALES) }

Object snapshotIvaSalesBook() { return snapshotBook(BOOK_SALES) }

Object buildIvaPurchasesBook() { return buildBook(BOOK_PURCHASES) }

Object getIvaPurchasesBookSummary() { return summaryForBook(BOOK_PURCHASES) }

Object exportIvaPurchasesBookCsv() { return exportBookCsv(BOOK_PURCHASES) }

Object renderIvaPurchasesBookPdf() { return renderBookPdf(BOOK_PURCHASES) }

Object snapshotIvaPurchasesBook() { return snapshotBook(BOOK_PURCHASES) }

/**
 * rebuild#CurrentPeriodIvaBooks — Job operativo invocado por ServiceJob diario.
 * Itera todas las organizaciones internas (PartyRole=OrgInternal no
 * deshabilitadas) y reconstruye los libros IVA ventas y compras del
 * periodo actual (YYYY-MM segun ec.user.nowTimestamp en TZ El Salvador).
 *
 * Es idempotente: build#IvaSalesBook/Compras hace upsert por (invoice, book).
 * Si una organizacion falla, se registra el error y se continua con la
 * siguiente — un fallo aislado no debe abortar el job completo.
 */
Object rebuildCurrentPeriodIvaBooks() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String periodOverride = clean(ctx('periodYearMonth'))
    LocalDate today = ec.user.nowTimestamp.toInstant()
            .atZone(java.time.ZoneId.of('America/El_Salvador')).toLocalDate()
    String periodText = periodOverride ?: YearMonth.from(today).toString()

    List<EntityValue> orgRoles = ec.entity.find('mantle.party.PartyRole')
            .condition('roleTypeId', 'OrgInternal').list()

    int orgsProcessed = 0
    int orgsFailed = 0
    int salesCreatedTotal = 0
    int salesUpdatedTotal = 0
    int purchasesCreatedTotal = 0
    int purchasesUpdatedTotal = 0
    List<Map> failures = []

    for (EntityValue role in orgRoles) {
        String orgPartyId = role.partyId as String
        EntityValue detail = ec.entity.find('mantle.party.PartyDetail')
                .condition('partyId', orgPartyId).one()
        if (detail?.disabled == 'Y') continue

        try {
            Map salesResult = ec.service.sync().name('sv.localization.IvaBookServices.build#IvaSalesBook')
                    .parameters([organizationPartyId: orgPartyId, periodYearMonth: periodText,
                                 includeMockAccepted: true, rebuild: true]).call()
            Map purchasesResult = ec.service.sync().name('sv.localization.IvaBookServices.build#IvaPurchasesBook')
                    .parameters([organizationPartyId: orgPartyId, periodYearMonth: periodText,
                                 includeMockAccepted: true, rebuild: true]).call()
            salesCreatedTotal += (Integer) (salesResult?.createdCount ?: 0)
            salesUpdatedTotal += (Integer) (salesResult?.updatedCount ?: 0)
            purchasesCreatedTotal += (Integer) (purchasesResult?.createdCount ?: 0)
            purchasesUpdatedTotal += (Integer) (purchasesResult?.updatedCount ?: 0)
            orgsProcessed++
        } catch (Throwable t) {
            failures.add([organizationPartyId: orgPartyId, error: t.getMessage()])
            orgsFailed++
        }
    }

    return [
            periodYearMonth      : periodText,
            orgsProcessed        : orgsProcessed,
            orgsFailed           : orgsFailed,
            salesCreatedCount    : salesCreatedTotal,
            salesUpdatedCount    : salesUpdatedTotal,
            purchasesCreatedCount: purchasesCreatedTotal,
            purchasesUpdatedCount: purchasesUpdatedTotal,
            failures             : failures
    ]
}
