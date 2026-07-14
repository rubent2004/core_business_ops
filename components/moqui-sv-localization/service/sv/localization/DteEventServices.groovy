import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.net.HttpURLConnection
import java.sql.Timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext execCtx() { (ExecutionContext) ctx('ec') }

String sysProp(String name, String fallback = null) {
    String value = System.getProperty(name)
    if (value) return value
    String envName = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')
    return System.getenv(envName) ?: fallback
}

boolean propBool(String name, boolean fallback) {
    String value = sysProp(name)
    if (value == null) return fallback
    return value.equalsIgnoreCase('true') || value.equalsIgnoreCase('Y') || value == '1'
}

String digitsOnly(Object raw) { raw == null ? null : raw.toString().replaceAll('[^0-9]', '') }

String clean(Object raw) { raw == null ? null : raw.toString().trim() }

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

String partyIdent(ExecutionContext ec, String partyId, String typeEnumId) {
    return one(ec, 'mantle.party.PartyIdentification',
            [partyId: partyId, partyIdTypeEnumId: typeEnumId])?.idValue
}

String partyDisplayName(ExecutionContext ec, String partyId) {
    EntityValue detail = one(ec, 'mantle.party.PartyDetail', [partyId: partyId])
    if (detail == null) return partyId
    String orgName = detail.getEntityDefinition().getFieldNames(true, true).contains('organizationName') ?
            (String) detail.organizationName : null
    if (orgName) return orgName
    String personName = [detail.firstName, detail.middleName, detail.lastName].findAll { it }.join(' ')
    return personName ?: partyId
}

Map organizationFiscalIdentity(ExecutionContext ec, String organizationPartyId) {
    EntityValue profile = one(ec, 'sv.localization.party.SvFiscalProfile', [partyId: organizationPartyId])
    return [
            nit               : digitsOnly(partyIdent(ec, organizationPartyId, 'PitNit')),
            nombre            : partyDisplayName(ec, organizationPartyId),
            codEstableMH      : profile?.codEstableMhCode ?: sysProp('sv.dte.cod_estable_mh.default'),
            codEstable        : profile?.codEstableCode ?: '0001',
            codPuntoVentaMH   : profile?.codPuntoVentaMhCode ?: sysProp('sv.dte.cod_punto_venta_mh.default'),
            codPuntoVenta     : profile?.codPuntoVentaCode ?: '0001'
    ]
}

Map issuerFiscalProfileForDte(ExecutionContext ec, EntityValue dte) {
    if (!dte?.invoiceId) return [:]
    EntityValue invoice = one(ec, 'mantle.account.invoice.Invoice', [invoiceId: dte.invoiceId])
    if (!invoice?.fromPartyId) return [:]
    EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', invoice.fromPartyId).one()
    return [
            codEstableMH: profile?.codEstableMhCode,
            codPuntoVentaMH: profile?.codPuntoVentaMhCode,
            codEstable: profile?.codEstableCode,
            codPuntoVenta: profile?.codPuntoVentaCode
    ]
}

Map eventDefinitions() {
    return [
            '16': [version: 1, schemaName: 'fe-eges-v1.json'],
            '17': [version: 1, schemaName: 'fe-eop-v1.json'],
            '18': [version: 1, schemaName: 'fe-eret-v1.json']
    ]
}

String schemaNameForEvent(String tipoEventoCode) {
    Map defn = (Map) eventDefinitions()[tipoEventoCode]
    if (defn == null) throw new IllegalArgumentException("Tipo evento DTE ${tipoEventoCode} no soportado")
    return defn.schemaName as String
}

Map validateAgainstSchema(String jsonText, String schemaName) {
    File schemaFile = new File("${System.getProperty('moqui.runtime')}/component/moqui-sv-localization/resource/schemas/${schemaName}")
    ObjectMapper mapper = new ObjectMapper()
    JsonNode schemaNode = mapper.readTree(schemaFile)
    JsonNode jsonNode = mapper.readTree(jsonText)
    JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode)
    Set<ValidationMessage> validationMessages = schema.validate(jsonNode)
    List<String> errors = validationMessages.collect { it.message }.sort()
    return [valid: errors.isEmpty(), schemaName: schemaName, errors: errors]
}

String resolveSecret(ExecutionContext ec, String key) {
    if (!key) return null
    if (key.startsWith('secret://')) return ec.resource.getLocationText(key, false)
    return sysProp(key)
}

String signerEndpoint() {
    return sysProp('sv.dte.firmador.url', 'http://localhost:8113').replaceAll('/+$', '') + '/firmardocumento/'
}

int httpConnectTimeoutMs() { Integer.parseInt(sysProp('sv.dte.resilience.connect.timeout.ms', '5000')) }
int httpReadTimeoutMs() { Integer.parseInt(sysProp('sv.dte.resilience.read.timeout.ms', '8000')) }

Map postJson(String url, Map payload, Map headers = null) {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('POST')
    conn.setConnectTimeout(httpConnectTimeoutMs())
    conn.setReadTimeout(httpReadTimeoutMs())
    conn.setDoOutput(true)
    conn.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
    conn.setRequestProperty('Accept', 'application/json')
    headers?.each { k, v -> if (v != null) conn.setRequestProperty(k as String, v as String) }
    conn.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(payload) }
    int status = conn.responseCode
    String body = (status >= 400 ? conn.errorStream : conn.inputStream)?.getText('UTF-8')
    return [status: status, body: body]
}

Map postForm(String url, Map params) {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('POST')
    conn.setConnectTimeout(httpConnectTimeoutMs())
    conn.setReadTimeout(httpReadTimeoutMs())
    conn.setDoOutput(true)
    conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
    conn.setRequestProperty('Accept', 'application/json')
    String enc = params.collect { k, v ->
        "${URLEncoder.encode(k as String, 'UTF-8')}=${URLEncoder.encode(v == null ? '' : v as String, 'UTF-8')}"
    }.join('&')
    conn.outputStream.withWriter('UTF-8') { it << enc }
    int status = conn.responseCode
    String body = (status >= 400 ? conn.errorStream : conn.inputStream)?.getText('UTF-8')
    return [status: status, body: body]
}

Map eventJsonFromContext(ExecutionContext ec) {
    Map eventJson = (Map) ctx('eventJson')
    String eventJsonText = (String) ctx('eventJsonText')
    if (!eventJson && eventJsonText) eventJson = (Map) new JsonSlurper().parseText(eventJsonText)
    return eventJson
}

List<Map> jsonListFrom(Object raw) {
    if (raw == null || raw == '') return []
    if (raw instanceof List) return (List<Map>) raw
    if (raw instanceof Map) return [(Map) raw]
    Object parsed = new JsonSlurper().parseText(raw.toString())
    if (parsed instanceof List) return (List<Map>) parsed
    if (parsed instanceof Map) return [(Map) parsed]
    return []
}

java.math.BigDecimal retMoney(Object raw) {
    if (raw == null || raw == '') return java.math.BigDecimal.ZERO.setScale(2)
    return new java.math.BigDecimal(raw.toString()).setScale(2, java.math.RoundingMode.HALF_UP)
}

java.math.BigDecimal retQty(Object raw) {
    if (raw == null || raw == '') return java.math.BigDecimal.ZERO
    return new java.math.BigDecimal(raw.toString())
}

java.math.BigDecimal prorateAmount(Object originalAmount, java.math.BigDecimal requestedQty, java.math.BigDecimal originalQty) {
    java.math.BigDecimal amount = retMoney(originalAmount)
    if (amount.compareTo(java.math.BigDecimal.ZERO) == 0) return amount
    if (originalQty.compareTo(java.math.BigDecimal.ZERO) <= 0) return amount
    return amount.multiply(requestedQty).divide(originalQty, 8, java.math.RoundingMode.HALF_UP).setScale(2, java.math.RoundingMode.HALF_UP)
}

java.math.BigDecimal positiveAmountFrom(Map req, List<String> keys, Map originalItem, Object originalField, java.math.BigDecimal qty, java.math.BigDecimal originalQty) {
    Object explicit = keys.collect { req[it] }.find { it != null && it != '' }
    return explicit == null ? prorateAmount(originalField, qty, originalQty) : retMoney(explicit)
}

Map findOriginalItem(List<Map> cuerpoOrig, Object requestedNumItem) {
    if (requestedNumItem == null || requestedNumItem == '') return null
    Integer num = Integer.valueOf(requestedNumItem.toString())
    return cuerpoOrig.find { Map item -> Integer.valueOf((item.numItem ?: 0).toString()) == num }
}

java.math.BigDecimal eventReturnTotal(Map resumen) {
    return retMoney(resumen?.montoTotalOperacion ?: resumen?.totalPagar ?: resumen?.total ?: resumen?.totalCompra ?: resumen?.subTotalVentas ?: resumen?.subTotal)
}

void persistEventItems(ExecutionContext ec, String dteFiscalEventId, List<Map> eventItems) {
    if (!dteFiscalEventId || !eventItems) return
    int idx = 1
    eventItems.each { Map item ->
        ec.entity.makeValue('sv.localization.dte.DteFiscalEventItem').setAll([
                dteFiscalEventId: dteFiscalEventId,
                noItem          : (item.noItem ?: idx) as Integer,
                dteEmisionId    : item.dteEmisionId,
                payloadJson     : JsonOutput.toJson(item.payload ?: item)
        ]).create()
        idx++
    }
}

Object generateDteEventJson() {
    ExecutionContext ec = execCtx()
    String tipoEventoCode = (ctx('tipoEventoCode') ?: '17') as String
    Map defn = (Map) eventDefinitions()[tipoEventoCode]
    if (defn == null) {
        ec.message.addError("Tipo evento ${tipoEventoCode} no soportado")
        return [:]
    }
    Map eventJson = eventJsonFromContext(ec)
    if (eventJson == null) {
        ec.message.addError("Evento ${tipoEventoCode} requiere eventJson/eventJsonText oficial. No se generan valores fiscales falsos.")
        return [:]
    }
    String schemaName = schemaNameForEvent(tipoEventoCode)
    Map validation = validateAgainstSchema(JsonOutput.toJson(eventJson), schemaName)
    if (!validation.valid) {
        ec.message.addError("JSON evento ${tipoEventoCode} inválido: ${validation.errors.take(5).join('; ')}")
        return [valid: false, schemaName: schemaName, errors: validation.errors]
    }
    String eventId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteFiscalEvent', null, null)
    String codigoGeneracion = (String) eventJson.identificacion?.codigoGeneracion
    ec.entity.makeValue('sv.localization.dte.DteFiscalEvent').setAll([
            dteFiscalEventId: eventId,
            tipoEventoCode: tipoEventoCode,
            versionEvento: defn.version,
            schemaName: schemaName,
            codigoGeneracion: codigoGeneracion,
            ambienteEnumId: eventJson.identificacion?.ambiente == '01' ? 'SvAmbProd' : 'SvAmbTest',
            statusId: 'DfeCreated',
            jsonOriginal: JsonOutput.prettyPrint(JsonOutput.toJson(eventJson)),
            fechaGeneracion: ec.user.nowTimestamp,
            errorCode: null,
            errorMessage: null
    ]).create()
    persistEventItems(ec, eventId, jsonListFrom(ctx('eventItems')))
    return [dteFiscalEventId: eventId, tipoEventoCode: tipoEventoCode, codigoGeneracion: codigoGeneracion,
            eventJson: eventJson, eventJsonText: JsonOutput.prettyPrint(JsonOutput.toJson(eventJson)),
            schemaName: schemaName, valid: true]
}

Object validateDteEventJson() {
    ExecutionContext ec = execCtx()
    String eventId = (String) ctx('dteFiscalEventId')
    String tipoEventoCode = (ctx('tipoEventoCode') ?: '17') as String
    String eventJsonText = (String) ctx('eventJsonText')
    Map eventJson = (Map) ctx('eventJson')
    EntityValue event = null
    if (eventId) {
        event = one(ec, 'sv.localization.dte.DteFiscalEvent', [dteFiscalEventId: eventId])
        tipoEventoCode = event?.tipoEventoCode ?: tipoEventoCode
        eventJsonText = eventJsonText ?: event?.jsonOriginal
    }
    if (!eventJsonText && eventJson != null) eventJsonText = JsonOutput.toJson(eventJson)
    String schemaName = (String) (ctx('schemaName') ?: event?.schemaName ?: schemaNameForEvent(tipoEventoCode))
    if (!eventJsonText) return [valid: false, schemaName: schemaName, errors: ['No hay JSON evento para validar']]
    Map validation = validateAgainstSchema(eventJsonText, schemaName)
    if (!validation.valid && event != null) {
        event.setAll([statusId: 'DfeError', errorCode: 'SCHEMA', errorMessage: validation.errors.take(5).join('; ')]).update()
    }
    return validation
}

Object signDteEvent() {
    ExecutionContext ec = execCtx()
    String eventId = (String) ctx('dteFiscalEventId')
    EntityValue event = one(ec, 'sv.localization.dte.DteFiscalEvent', [dteFiscalEventId: eventId])
    if (event == null) return [signed: false, message: "Evento fiscal ${eventId} no existe"]
    if (propBool('sv.dte.sign.mock.enabled', false)) {
        String jws = "MOCK-JWS-EVENT-${event.codigoGeneracion}"
        event.setAll([jwsCompactSerialization: jws, jsonFirmadoResp: JsonOutput.toJson([status: 'OK', body: jws, mock: true]),
                      statusId: 'DfeSigned', errorCode: null, errorMessage: null]).update()
        return [signed: true, jwsCompactSerialization: jws, message: 'Firma mock explícita']
    }
    try {
        Map eventJson = (Map) new JsonSlurper().parseText((String) event.jsonOriginal)
        String nit = digitsOnly(eventJson?.emisor?.nit)
        EntityValue cert = one(ec, 'sv.localization.dte.DteCertificate', [nit: nit])
        String password = resolveSecret(ec, (String) cert?.passwordSecretKey)
        if (!cert || !password) throw new IllegalStateException("Certificado/password no configurado para NIT ${nit}")
        Map response = postJson(signerEndpoint(), [nit: nit, activo: true, passwordPri: password, dteJson: eventJson])
        Map parsed = response.body ? (Map) new JsonSlurper().parseText((String) response.body) : [:]
        if (response.status < 200 || response.status >= 300 || parsed.status != 'OK' || !parsed.body) {
            throw new IllegalStateException("Firmador rechazó evento: HTTP ${response.status} ${response.body}")
        }
        event.setAll([jwsCompactSerialization: parsed.body as String, jsonFirmadoResp: response.body,
                      statusId: 'DfeSigned', errorCode: null, errorMessage: null]).update()
        return [signed: true, jwsCompactSerialization: event.jwsCompactSerialization, message: 'Firma local OK']
    } catch (Throwable t) {
        event.setAll([statusId: 'DfeError', errorCode: 'SIGN', errorMessage: t.message]).update()
        return [signed: false, message: t.message]
    }
}

Object transmitDteEvent() {
    ExecutionContext ec = execCtx()
    String eventId = (String) ctx('dteFiscalEventId')
    EntityValue event = one(ec, 'sv.localization.dte.DteFiscalEvent', [dteFiscalEventId: eventId])
    if (event == null) return [transmitted: false, message: "Evento fiscal ${eventId} no existe"]
    if (!event.jwsCompactSerialization) {
        event.setAll([statusId: 'DfeError', errorCode: 'TRANSMIT', errorMessage: 'Evento no firmado']).update()
        return [transmitted: false, statusId: 'DfeError', message: 'Evento no firmado']
    }
    if (propBool('sv.dte.mh.mock.enabled', true)) {
        String sello = "MOCK-EVENT-${event.codigoGeneracion}"
        event.setAll([statusId: 'DfeMockAccepted', selloRecepcion: sello,
                      fechaTransmision: ec.user.nowTimestamp, fechaProcesamientoMh: ec.user.nowTimestamp,
                      observacionesMh: 'Transmisión mock local; no corresponde a aceptación oficial MH.',
                      errorCode: null, errorMessage: null]).update()
        return [transmitted: true, mock: true, statusId: event.statusId, selloRecepcion: sello, message: 'Aceptado mock local']
    }
    try {
        boolean useLocalSim = propBool('sv.dte.test.local', true)
        EntityValue config = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
        String authUrl = useLocalSim ? sysProp('sv.dte.local.auth.url') : config?.mhAuthUrl
        String recepUrl = useLocalSim ? sysProp('sv.dte.local.recepcion.url') : config?.mhRecepcionUrl
        String userMh = useLocalSim ? sysProp('sv.dte.local.demo.user', 'demo') : config?.defaultUserMh
        String mhPassword = useLocalSim ? sysProp('sv.dte.local.demo.password', 'hacienda') : resolveSecret(ec, (String) config?.passwordMhSecretKey)
        if (!authUrl || !recepUrl || !userMh || !mhPassword) throw new IllegalStateException('config MH incompleta para evento fiscal')
        Map authResp = postForm(authUrl, [user: userMh, pwd: mhPassword])
        if (authResp.status < 200 || authResp.status >= 300 || !authResp.body) throw new IllegalStateException("Auth MH falló: HTTP ${authResp.status}")
        Map authParsed = (Map) new JsonSlurper().parseText((String) authResp.body)
        String token = ((String) authParsed.body.token).trim()
        String bearer = token.toLowerCase(Locale.ROOT).startsWith('bearer ') ? token : "Bearer ${token}"
        Map eventJson = (Map) new JsonSlurper().parseText((String) event.jsonOriginal)
        Map recPayload = [ambiente: eventJson.identificacion?.ambiente ?: '00',
                          idEnvio: 1,
                          version: eventJson.identificacion?.version ?: event.versionEvento,
                          tipoDte: event.tipoEventoCode,
                          documento: event.jwsCompactSerialization,
                          codigoGeneracion: event.codigoGeneracion]
        Map recResp = postJson(recepUrl, recPayload, [Authorization: bearer])
        Map mhParsed = recResp.body ? (Map) new JsonSlurper().parseText((String) recResp.body) : [:]
        event.setAll([fechaTransmision: ec.user.nowTimestamp, fechaProcesamientoMh: ec.user.nowTimestamp,
                      jsonFirmadoResp: recResp.body, intentos: ((event.intentos ?: 0) as int) + 1]).update()
        if (mhParsed.estado == 'PROCESADO' || mhParsed.estado == 'ACEPTADO') {
            event.setAll([statusId: 'DfeAccepted', selloRecepcion: mhParsed.selloRecibido,
                          observacionesMh: mhParsed.descripcionMsg, errorCode: null, errorMessage: null]).update()
            return [transmitted: true, mock: false, statusId: event.statusId, selloRecepcion: event.selloRecepcion, message: 'Aceptado por MH']
        }
        event.setAll([statusId: 'DfeRejected', errorCode: (mhParsed.codigoMsg ?: 'MH_REJECT') as String,
                      errorMessage: mhParsed.descripcionMsg ?: 'Rechazado por MH']).update()
        return [transmitted: false, mock: false, statusId: event.statusId, message: event.errorMessage]
    } catch (Throwable t) {
        event.setAll([statusId: 'DfeError', errorCode: 'MH_TX', errorMessage: t.message,
                      intentos: ((event.intentos ?: 0) as int) + 1]).update()
        return [transmitted: false, mock: false, statusId: event.statusId, message: t.message]
    }
}

Object emitDteEvent() {
    ExecutionContext ec = execCtx()
    String tipoEventoCode = (ctx('tipoEventoCode') ?: '17') as String
    Map gen = (Map) ec.service.sync().name('sv.localization.DteEventServices.generate#DteEventJson')
            .parameters([tipoEventoCode: tipoEventoCode, eventJson: ctx('eventJson'), eventJsonText: ctx('eventJsonText'),
                         eventItems: ctx('eventItems')]).call()
    String eventId = (String) gen.dteFiscalEventId
    if (!eventId) return [message: 'No se generó evento fiscal']
    Map sign = (Map) ec.service.sync().name('sv.localization.DteEventServices.sign#DteEvent')
            .parameters([dteFiscalEventId: eventId]).call()
    if (!sign.signed) return [dteFiscalEventId: eventId, statusId: 'DfeError', message: sign.message]
    Map tx = (Map) ec.service.sync().name('sv.localization.DteEventServices.transmit#DteEvent')
            .parameters([dteFiscalEventId: eventId]).call()
    Map entrega = [:]
    if (tipoEventoCode == '18' && tx.statusId in ['DfeAccepted', 'DfeMockAccepted']) {
        try {
            entrega = (Map) ec.service.sync().name('sv.localization.DteEntregaServices.deliver#DteEvento')
                    .parameters([eventoTipo: 'RETORNO', dteFiscalEventId: eventId, sendEmail: false]).call()
        } catch (Throwable t) {
            ec.message.addMessage("No se pudo preparar entrega de retorno: ${t.message}", 'warning')
        }
    }
    return [dteFiscalEventId: eventId, statusId: tx.statusId, selloRecepcion: tx.selloRecepcion,
            dteEntregaId: entrega?.dteEntregaId, entregaStatusId: entrega?.statusId, message: tx.message]
}

Map buildEopJsonFromItems(ExecutionContext ec, String organizationPartyId, String operacionEspecialCode, List<Map> items, Object fechaOperacion, String descripcionGeneral) {
    if (!organizationPartyId) { ec.message.addError('organizationPartyId requerido para EOP'); return [:] }
    if (!(operacionEspecialCode in ['02', '97'])) {
        ec.message.addError('operacionEspecialCode debe ser 02 (factura simplificada) o 97 (control interno)')
        return [:]
    }
    if (!items) { ec.message.addError('Ingrese al menos una línea para el evento EOP'); return [:] }
    Map issuer = organizationFiscalIdentity(ec, organizationPartyId)
    if (!issuer.nit || !issuer.nombre) {
        ec.message.addError('EOP requiere NIT y nombre fiscal de la organización emisora')
        return [:]
    }

    Timestamp now = ec.user.nowTimestamp
    def ldt = now.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()
    String eventDate = fechaOperacion ? fechaOperacion.toString().substring(0, 10) : ldt.toLocalDate().format(DateTimeFormatter.ISO_DATE)

    List<Map> cuerpo = []
    List<Map> eventItems = []
    java.math.BigDecimal totalGravada = java.math.BigDecimal.ZERO.setScale(2)
    java.math.BigDecimal totalExenta = java.math.BigDecimal.ZERO.setScale(2)
    java.math.BigDecimal totalNoSuj = java.math.BigDecimal.ZERO.setScale(2)
    int idx = 1
    items.each { Map req ->
        java.math.BigDecimal cantidad = retQty(req.cantidad ?: 1)
        if (cantidad.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            ec.message.addError("EOP línea ${idx}: cantidad debe ser mayor que cero")
            return
        }
        java.math.BigDecimal gravada = retMoney(req.montoGravado ?: req.ventaGravada)
        java.math.BigDecimal exenta = retMoney(req.montoExento ?: req.ventaExenta)
        java.math.BigDecimal noSuj = retMoney(req.montoNoSujeto ?: req.ventaNoSuj)
        if ([gravada, exenta, noSuj].any { it.compareTo(java.math.BigDecimal.ZERO) < 0 }) {
            ec.message.addError("EOP línea ${idx}: los montos no pueden ser negativos")
            return
        }
        java.math.BigDecimal lineTotal = gravada.add(exenta).add(noSuj).setScale(2)
        if (lineTotal.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            ec.message.addError("EOP línea ${idx}: debe tener monto gravado, exento o no sujeto mayor que cero")
            return
        }
        java.math.BigDecimal precioUni = req.precioUni == null || req.precioUni == '' ?
                lineTotal.divide(cantidad, 8, java.math.RoundingMode.HALF_UP) : retMoney(req.precioUni)
        Map line = [
                numItem            : idx,
                codigoGeneracionRef: req.codigoGeneracionRef ?: null,
                tipoDocumento      : req.tipoDocumento ?: operacionEspecialCode,
                numDocumento       : req.numDocumento ?: req.documentoDesde ?: null,
                fechaEmision       : clean(req.fechaEmision) ?: eventDate,
                cantidad           : cantidad.intValue(),
                descripcion        : clean(req.descripcion) ?: descripcionGeneral ?: (operacionEspecialCode == '02' ? 'Factura simplificada' : 'Comprobante de control interno'),
                docDel             : req.docDel ?: req.documentoDesde ?: null,
                docAl              : req.docAl ?: req.documentoHasta ?: null,
                precioUni          : precioUni,
                ventaNoSuj         : noSuj,
                ventaExenta        : exenta,
                ventaGravada       : gravada,
                tributos           : gravada.compareTo(java.math.BigDecimal.ZERO) > 0 ? ['20'] : null
        ]
        cuerpo.add(line)
        totalGravada = totalGravada.add(gravada).setScale(2)
        totalExenta = totalExenta.add(exenta).setScale(2)
        totalNoSuj = totalNoSuj.add(noSuj).setScale(2)
        eventItems.add([noItem: idx, dteEmisionId: req.dteEmisionId, payload: [input: req, cuerpoDocumento: line]])
        idx++
    }
    if (ec.message.hasError()) return [:]

    java.math.BigDecimal subTotal = totalGravada.add(totalExenta).add(totalNoSuj).setScale(2)
    java.math.BigDecimal ivaCalc = totalGravada.multiply(new java.math.BigDecimal('0.13')).setScale(2, java.math.RoundingMode.HALF_UP)
    java.math.BigDecimal total = subTotal.add(ivaCalc).setScale(2)
    Map eventJson = [
            identificacion : [
                    version         : 1,
                    ambiente        : sysProp('sv.dte.mh.ambiente.default', '00'),
                    tipoModelo      : 1,
                    tipoOperacion   : 1,
                    tipoEvento      : '17',
                    codigoGeneracion: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                    fecEmi          : eventDate,
                    horEmi          : ldt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
                    tipoMoneda      : 'USD'
            ],
            emisor         : [
                    nit   : issuer.nit,
                    nombre: issuer.nombre
            ],
            cuerpoDocumento: cuerpo,
            resumen        : [
                    totalNoSuj  : totalNoSuj,
                    totalExenta : totalExenta,
                    totalGravada: totalGravada,
                    subTotal    : subTotal,
                    tributos    : ivaCalc.compareTo(java.math.BigDecimal.ZERO) > 0 ?
                            [[codigo: '20', descripcion: 'Impuesto al Valor Agregado 13%', valor: ivaCalc]] : null,
                    total       : total,
                    totalLetras : "${total.toPlainString()} DOLARES"
            ],
            apendice       : null
    ]
    return [tipoEventoCode: '17', eventJson: eventJson,
            eventJsonText: JsonOutput.prettyPrint(JsonOutput.toJson(eventJson)), eventItems: eventItems]
}

Object buildEopOperacionEspecial() {
    ExecutionContext ec = execCtx()
    List<Map> items = jsonListFrom(ctx('eopItems') ?: ctx('eopItemsJson') ?: ctx('lineItemsJson'))
    return buildEopJsonFromItems(ec, (String) ctx('organizationPartyId'),
            (ctx('operacionEspecialCode') ?: '02') as String, items, ctx('fechaOperacion'), (String) ctx('descripcion'))
}

Object buildEopFromDteList() {
    ExecutionContext ec = execCtx()
    String operacionEspecialCode = (ctx('operacionEspecialCode') ?: '02') as String
    List<String> dteEmisionIds = (List<String>) ctx('dteEmisionIds')
    if (!dteEmisionIds) { ec.message.addError('Seleccione al menos un DTE para el evento EOP'); return [:] }

    EntityValue firstDte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionIds[0]])
    if (!firstDte) { ec.message.addError("DTE ${dteEmisionIds[0]} no encontrado"); return [:] }
    EntityValue firstInvoice = firstDte.invoiceId ? one(ec, 'mantle.account.invoice.Invoice', [invoiceId: firstDte.invoiceId]) : null
    String organizationPartyId = (String) (ctx('organizationPartyId') ?: firstInvoice?.fromPartyId)
    if (!organizationPartyId) { ec.message.addError('organizationPartyId requerido para EOP'); return [:] }

    // Plazo de operaciones especiales (Normativa v2.0, plan/13): 10 días hábiles del mes
    // siguiente a las operaciones. Advierte sin bloquear, tomando como referencia el primer DTE.
    Object selloEop = firstDte.fechaProcesamientoMh ?: firstDte.fechaGeneracion
    if (selloEop != null) {
        Map plEop = ec.service.sync().name('sv.localization.SvBusinessCalendarServices.check#DtePlazo')
                .parameters([eventType: 'operaciones_especiales', referenceDate: selloEop]).call()
        if (plEop?.withinPlazo == false && plEop?.message) ec.message.addMessage(plEop.message as String, 'warning')
    }

    List<Map> items = []
    for (String dteId in dteEmisionIds) {
        EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteId])
        if (!dte || !(dte.statusId in ['DteAccepted', 'DteMockAccepted'])) continue
        Map dteJson = (Map) new JsonSlurper().parseText((String) dte.jsonOriginal)
        Map resumenDte = (Map) dteJson.resumen
        items.add([
                dteEmisionId       : dte.dteEmisionId,
                codigoGeneracionRef: dte.codigoGeneracion,
                tipoDocumento      : dte.tipoDteCode,
                numDocumento       : dte.numeroControl,
                fechaEmision       : dteJson?.identificacion?.fecEmi,
                cantidad           : 1,
                descripcion        : "DTE ${dte.tipoDteCode} ${dte.numeroControl}",
                montoNoSujeto      : retMoney(resumenDte?.totalNoSuj),
                montoExento        : retMoney(resumenDte?.totalExenta),
                montoGravado       : retMoney(resumenDte?.totalGravada)
        ])
    }
    return buildEopJsonFromItems(ec, organizationPartyId, operacionEspecialCode, items, ctx('fechaOperacion'), (String) ctx('descripcion'))
}

java.math.BigDecimal retBigDec(Object raw) {
    if (raw == null || raw == '') return java.math.BigDecimal.ZERO
    return new java.math.BigDecimal(raw.toString())
}

/** Suma el monto de los eventos de retorno (18) ya aceptados que relacionan el DTE indicado. */
java.math.BigDecimal sumPriorRetornos(ExecutionContext ec, String codGenOriginal) {
    java.math.BigDecimal sum = java.math.BigDecimal.ZERO
    if (!codGenOriginal) return sum
    List<EntityValue> events = ec.entity.find('sv.localization.dte.DteFiscalEvent')
            .condition('tipoEventoCode', '18')
            .condition('statusId', org.moqui.entity.EntityCondition.IN, ['DfeAccepted', 'DfeMockAccepted'])
            .list()
    for (EntityValue ev in events) {
        if (!ev.jsonOriginal) continue
        Map j = (Map) new groovy.json.JsonSlurper().parseText((String) ev.jsonOriginal)
        List rel = (List) j.documentoRelacionado
        boolean matches = rel?.any { ((Map) it).codigoGeneracion == codGenOriginal }
        if (!matches) continue
        Map res = (Map) j.resumen
        sum = sum.add(retBigDec(res?.montoTotalOperacion ?: res?.totalPagar ?: res?.subTotalVentas))
    }
    return sum
}

Object buildEretFromDte() {
    ExecutionContext ec = execCtx()
    String dteEmisionId = (String) ctx('dteEmisionId')
    String motivo = (String) ctx('motivo')
    boolean strict = propBool('sv.dte.eret.strict.default', true)
    Object strictParam = ctx('strict')
    if (strictParam != null && strictParam != '') {
        strict = strictParam instanceof Boolean ? (Boolean) strictParam : strictParam.toString().equalsIgnoreCase('true') || strictParam.toString().equalsIgnoreCase('Y')
    }
    if (!dteEmisionId) { ec.message.addError('Seleccione un DTE para el evento de retorno'); return [:] }

    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    if (!dte) { ec.message.addError("DTE ${dteEmisionId} no encontrado"); return [:] }
    if (!(dte.tipoDteCode in ['01', '11', '14'])) {
        ec.message.addError("Evento de retorno solo aplica para FE (01), FEXE (11), FSEE (14); DTE es tipo ${dte.tipoDteCode}")
        return [:]
    }
    // Orden lógico v2.0: el DTE original debe estar transmitido y sellado por MH antes de
    // emitir un retorno (igual que la invalidación). Sin sello no se puede retornar.
    if (!(dte.statusId in ['DteAccepted', 'DteMockAccepted']) || !dte.selloRecepcion) {
        ec.message.addError("El DTE original debe estar aceptado y sellado por MH antes de emitir un retorno; estado actual=${dte.statusId}, sello=${dte.selloRecepcion ? 'presente' : 'ausente'}")
        return [:]
    }
    // Plazo de retorno (Normativa v2.0, plan/13): 3 meses desde el sello. Advierte sin bloquear.
    Object selloRetorno = dte.fechaProcesamientoMh ?: dte.fechaGeneracion
    if (selloRetorno != null) {
        Map plRet = ec.service.sync().name('sv.localization.SvBusinessCalendarServices.check#DtePlazo')
                .parameters([eventType: 'retorno', tipoDteCode: dte.tipoDteCode, referenceDate: selloRetorno]).call()
        if (plRet?.withinPlazo == false && plRet?.message) ec.message.addMessage(plRet.message as String, 'warning')
    }
    Map dteJson = (Map) new JsonSlurper().parseText((String) dte.jsonOriginal)
    Map emisorJson = (Map) dteJson.emisor
    Map receptorJson = (Map) dteJson.receptor
    Map profileCodes = issuerFiscalProfileForDte(ec, dte)
    String codEstableMh = (String) (emisorJson?.codEstableMH ?: profileCodes.codEstableMH ?: sysProp('sv.dte.cod_estable_mh.default'))
    String codPuntoVentaMh = (String) (emisorJson?.codPuntoVentaMH ?: profileCodes.codPuntoVentaMH ?: sysProp('sv.dte.cod_punto_venta_mh.default'))
    if (!codEstableMh || !codPuntoVentaMh) {
        ec.message.addError('Evento de retorno v1 requiere codEstableMH y codPuntoVentaMH del emisor; configure perfil fiscal o sv.dte.cod_*_mh.default')
        return [:]
    }

    Timestamp now = ec.user.nowTimestamp
    def ldt = now.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()

    List<Map> cuerpoOrig = (List<Map>) dteJson.cuerpoDocumento
    List<Map> requestedItems = jsonListFrom(ctx('returnItems') ?: ctx('returnItemsJson'))
    if (!requestedItems) {
        ec.message.addError('Evento de retorno requiere returnItems con al menos una línea parcial; no se genera retorno completo por defecto')
        return [:]
    }

    List<Map> cuerpo = []
    List<Map> eventItems = []
    java.math.BigDecimal totalGravada = java.math.BigDecimal.ZERO.setScale(2)
    java.math.BigDecimal totalExenta = java.math.BigDecimal.ZERO.setScale(2)
    java.math.BigDecimal totalNoSuj = java.math.BigDecimal.ZERO.setScale(2)
    java.math.BigDecimal totalNoGravado = java.math.BigDecimal.ZERO.setScale(2)
    int idx = 1
    for (Map req in requestedItems) {
        Object originalNum = req.numItemOriginal ?: req.numItem ?: req.originalNumItem
        Map item = findOriginalItem(cuerpoOrig, originalNum)
        if (item == null) {
            ec.message.addError("Línea original ${originalNum ?: '?'} no existe en el DTE ${dte.codigoGeneracion}")
            continue
        }
        java.math.BigDecimal originalQty = retQty(item.cantidad ?: 1)
        java.math.BigDecimal qty = retQty(req.cantidad)
        if (qty.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            ec.message.addError("Retorno línea ${originalNum}: cantidad debe ser mayor que cero")
            continue
        }
        if (strict && originalQty.compareTo(java.math.BigDecimal.ZERO) > 0 && qty.compareTo(originalQty) > 0) {
            ec.message.addError("Retorno línea ${originalNum}: cantidad ${qty} excede la cantidad original ${originalQty}")
            continue
        }
        java.math.BigDecimal gravada = positiveAmountFrom(req, ['montoGravado', 'ventaGravada'], item, item.ventaGravada, qty, originalQty)
        java.math.BigDecimal exenta = positiveAmountFrom(req, ['montoExento', 'ventaExenta'], item, item.ventaExenta, qty, originalQty)
        java.math.BigDecimal noSuj = positiveAmountFrom(req, ['montoNoSujeto', 'ventaNoSuj'], item, item.ventaNoSuj, qty, originalQty)
        java.math.BigDecimal noGravado = positiveAmountFrom(req, ['noGravado', 'montoNoGravado'], item, item.noGravado, qty, originalQty)
        if ([gravada, exenta, noSuj, noGravado].any { it.compareTo(java.math.BigDecimal.ZERO) < 0 }) {
            ec.message.addError("Retorno línea ${originalNum}: los montos no pueden ser negativos")
            continue
        }
        java.math.BigDecimal lineBase = gravada.add(exenta).add(noSuj).add(noGravado).setScale(2)
        if (lineBase.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            ec.message.addError("Retorno línea ${originalNum}: debe tener monto gravado, exento, no sujeto o no gravado mayor que cero")
            continue
        }
        if (strict) {
            java.math.BigDecimal originalBase = retMoney(item.ventaGravada).add(retMoney(item.ventaExenta))
                    .add(retMoney(item.ventaNoSuj)).add(retMoney(item.noGravado)).setScale(2)
            if (originalBase.compareTo(java.math.BigDecimal.ZERO) > 0 && lineBase.compareTo(originalBase) > 0) {
                ec.message.addError("Retorno línea ${originalNum}: monto ${lineBase} excede el monto original ${originalBase}")
                continue
            }
        }
        java.math.BigDecimal ivaItem = gravada.multiply(new java.math.BigDecimal('0.13')).setScale(2, java.math.RoundingMode.HALF_UP)
        Map retLine = [
                numItem: idx,
                tipoItem: item.tipoItem ?: 1,
                codigoGeneracion: dte.codigoGeneracion,
                cantidad: qty,
                precioUni: req.precioUni == null || req.precioUni == '' ?
                        lineBase.divide(qty, 8, java.math.RoundingMode.HALF_UP) : retMoney(req.precioUni),
                descripcion: clean(req.descripcionRetorno) ?: clean(req.descripcion) ?: item.descripcion ?: "Item ${originalNum}",
                codigo: item.codigo,
                uniMedida: item.uniMedida ?: 59,
                montoDescu: java.math.BigDecimal.ZERO.setScale(2),
                codTributo: null,
                ventaNoSuj: noSuj,
                ventaExenta: exenta,
                ventaGravada: gravada,
                compra: java.math.BigDecimal.ZERO.setScale(2),
                tributos: gravada > java.math.BigDecimal.ZERO ? ['20'] : null,
                psv: java.math.BigDecimal.ZERO.setScale(2),
                ivaItem: ivaItem,
                noGravado: noGravado,
                seguro: java.math.BigDecimal.ZERO.setScale(2),
                flete: java.math.BigDecimal.ZERO.setScale(2),
                ivaRete: java.math.BigDecimal.ZERO.setScale(2),
                reteRenta: java.math.BigDecimal.ZERO.setScale(2)
        ]
        cuerpo.add(retLine)
        eventItems.add([noItem: idx, dteEmisionId: dte.dteEmisionId,
                        payload: [numItemOriginal: originalNum, input: req, originalItem: item, cuerpoDocumento: retLine]])
        totalGravada = totalGravada.add(gravada).setScale(2)
        totalExenta = totalExenta.add(exenta).setScale(2)
        totalNoSuj = totalNoSuj.add(noSuj).setScale(2)
        totalNoGravado = totalNoGravado.add(noGravado).setScale(2)
        idx++
    }
    if (ec.message.hasError()) return [:]
    if (cuerpo.isEmpty()) { ec.message.addError('Retorno sin líneas válidas'); return [:] }

    java.math.BigDecimal ivaCalc = totalGravada.multiply(new java.math.BigDecimal('0.13')).setScale(2, java.math.RoundingMode.HALF_UP)
    java.math.BigDecimal totalOp = totalGravada.add(totalExenta).add(totalNoSuj).add(totalNoGravado).add(ivaCalc).setScale(2)
    if (totalOp.compareTo(java.math.BigDecimal.ZERO) <= 0) {
        ec.message.addError('El monto del retorno debe ser mayor que cero')
        return [:]
    }

    // Retorno acumulado (Normativa v2.0 §13.3): "el retorno no deberá exceder al valor de lo
    // vendido o comprado". Se permiten varios retornos parciales, pero el total completo debe ir
    // por invalidación y el acumulado nunca debe exceder el valor del DTE original.
    java.math.BigDecimal dteTotal = eventReturnTotal((Map) dteJson?.resumen)
    if (dteTotal.compareTo(java.math.BigDecimal.ZERO) <= 0) dteTotal = totalOp
    if (totalOp.compareTo(dteTotal) >= 0) {
        ec.message.addError("El retorno cubre el total del DTE (${totalOp}); use evento de invalidación en lugar de ERET")
        return [:]
    }
    java.math.BigDecimal acumulado = sumPriorRetornos(ec, (String) dte.codigoGeneracion).add(totalOp)
    if (acumulado.compareTo(dteTotal) > 0) {
        ec.message.addError("El retorno acumulado (${acumulado}) excede el valor del DTE original (${dteTotal})")
        return [:]
    }

    Map eventJson = [
            identificacion: [
                    version: 1,
                    ambiente: sysProp('sv.dte.mh.ambiente.default', '00'),
                    tipoModelo: 1,
                    tipoOperacion: 1,
                    tipoEvento: '18',
                    tipoContingencia: null,
                    motivoContin: null,
                    codigoGeneracion: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                    fecEmi: ldt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                    horEmi: ldt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
                    fusion: null,
                    tipoMoneda: 'USD'
            ],
            documentoRelacionado: [[
                    tipoDocumento: dte.tipoDteCode,
                    codigoGeneracion: dte.codigoGeneracion,
                    fechaEmision: dteJson?.identificacion?.fecEmi
            ]],
            emisor: [
                    nit: digitsOnly(emisorJson?.nit),
                    nombre: emisorJson?.nombre,
                    codEstableMH: codEstableMh,
                    codEstable: emisorJson?.codEstable ?: profileCodes.codEstable,
                    codPuntoVentaMH: codPuntoVentaMh,
                    codPuntoVenta: emisorJson?.codPuntoVenta ?: profileCodes.codPuntoVenta,
                    recintoFiscal: null,
                    tipoRegimen: null,
                    regimen: null,
                    tipoItemExpor: null
            ],
            documento: receptorJson ? [
                    tipoDocumento: receptorJson.tipoDocumento,
                    numDocumento: receptorJson.numDocumento ?: receptorJson.nit,
                    nombre: receptorJson.nombre,
                    codPais: null, nombrePais: null,
                    telefono: receptorJson.telefono,
                    correo: receptorJson.correo
            ] : null,
            ventaTercero: null,
            compraTercero: null,
            cuerpoDocumento: cuerpo,
            resumen: [
                    totalNoSuj: totalNoSuj,
                    totalExenta: totalExenta,
                    totalGravada: totalGravada.setScale(2),
                    totalCompraExcluidos: java.math.BigDecimal.ZERO.setScale(2),
                    subTotalVentas: totalGravada.add(totalExenta).add(totalNoSuj).setScale(2),
                    tributos: ivaCalc.compareTo(java.math.BigDecimal.ZERO) > 0 ?
                            [[codigo: '20', descripcion: 'Impuesto al Valor Agregado 13%', valor: ivaCalc]] : null,
                    totalSeguro: java.math.BigDecimal.ZERO.setScale(2),
                    totalFlete: java.math.BigDecimal.ZERO.setScale(2),
                    montoTotalOperacion: totalOp.setScale(2),
                    ivaRete: java.math.BigDecimal.ZERO.setScale(2),
                    reteRenta: java.math.BigDecimal.ZERO.setScale(2),
                    totalNoGravado: totalNoGravado,
                    totalPagar: totalOp.setScale(2),
                    totalLetras: "${totalOp.setScale(2).toPlainString()} DOLARES",
                    totalNoOnerosas: java.math.BigDecimal.ZERO.setScale(2),
                    totalIva: ivaCalc,
                    saldoFavor: java.math.BigDecimal.ZERO.setScale(2)
            ],
            apendice: null
    ]
    String jsonText = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(eventJson))
    return [tipoEventoCode: '18', eventJson: eventJson, eventJsonText: jsonText, eventItems: eventItems]
}
