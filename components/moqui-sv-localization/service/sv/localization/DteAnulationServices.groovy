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
    String v = sysProp(name)
    if (v == null) return fallback
    return v.equalsIgnoreCase('true') || v.equalsIgnoreCase('Y') || v == '1'
}

String digits(Object raw) { raw == null ? null : raw.toString().replaceAll('[^0-9]', '') }

String invalidacionSchemaName() { 'invalidacion-schema-v3.json' }
int invalidacionVersion() { 3 }

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

void addEvent(ExecutionContext ec, String dteEmisionId, String eventTypeEnumId, String comments, Object payload = null, Integer httpStatus = null) {
    Timestamp eventDate = new Timestamp(System.currentTimeMillis())
    int attempts = 0
    while (one(ec, 'sv.localization.dte.DteEmisionEvent', [dteEmisionId: dteEmisionId, eventDate: eventDate]) != null) {
        eventDate = new Timestamp(eventDate.time + 1L)
        if (++attempts > 1000) throw new IllegalStateException("No se pudo asignar eventDate unico para DTE ${dteEmisionId}")
    }
    ec.entity.makeValue('sv.localization.dte.DteEmisionEvent').setAll([
            dteEmisionId: dteEmisionId,
            eventDate: eventDate,
            eventTypeEnumId: eventTypeEnumId,
            httpStatus: httpStatus,
            responsePayload: payload == null ? null : (payload instanceof String ? payload : JsonOutput.toJson(payload)),
            userId: ec.user.userId,
            comments: comments
    ]).create()
}

String mockSafeSello(EntityValue dte) {
    String sello = dte?.selloRecepcion
    if (sello ==~ /^[A-Z0-9]{40}$/) return sello
    String code = ((String) dte?.codigoGeneracion ?: UUID.randomUUID().toString()).replaceAll('[^A-Fa-f0-9]', '').toUpperCase(Locale.ROOT)
    return ('MOCK' + code.padRight(36, '0')).substring(0, 40)
}

BigDecimal ivaFromResumen(Map resumen) {
    if (resumen?.totalIva != null) return new BigDecimal(resumen.totalIva.toString()).setScale(2)
    List tributos = (List) resumen?.tributos
    Map iva = tributos?.find { it?.codigo == '20' } as Map
    return iva?.valor != null ? new BigDecimal(iva.valor.toString()).setScale(2) : BigDecimal.ZERO.setScale(2)
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

Object validateAnulacionJson() {
    ExecutionContext ec = execCtx()
    Map anulacionJson = (Map) ctx('anulacionJson')
    String anulacionJsonText = (String) ctx('anulacionJsonText')
    if (!anulacionJsonText && anulacionJson != null) anulacionJsonText = JsonOutput.toJson(anulacionJson)
    if (!anulacionJsonText) {
        ec.message.addError('No hay JSON anulación para validar')
        return [valid: false, schemaName: invalidacionSchemaName(), errors: ['No hay JSON anulación para validar']]
    }
    return validateAgainstSchema(anulacionJsonText, invalidacionSchemaName())
}

Object generateAnulacionJson() {
    ExecutionContext ec = execCtx()
    String dteEmisionId = (String) ctx('dteEmisionId')
    String tipoAnulacion = (ctx('tipoAnulacion') ?: '2') as String
    String motivo = (String) ctx('motivo')
    String respNombre = (String) ctx('responsableNombre')
    String respTipDoc = (ctx('responsableTipDoc') ?: '13') as String
    String respNumDoc = (String) ctx('responsableNumDoc')
    String solNombre = (String) ctx('solicitanteNombre') ?: respNombre
    String solTipDoc = (ctx('solicitanteTipDoc') ?: respTipDoc) as String
    String solNumDoc = (String) ctx('solicitanteNumDoc') ?: respNumDoc

    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    if (dte == null) { ec.message.addError("DTE ${dteEmisionId} no existe"); return [:] }
    if (dte.statusId == 'DteCanceled') {
        ec.message.addError("DTE ${dteEmisionId} ya esta anulado"); return [:]
    }
    if (dte.statusId != 'DteAccepted' && dte.statusId != 'DteMockAccepted') {
        ec.message.addError("Solo DTEs aceptados pueden anularse; estado actual=${dte.statusId}"); return [:]
    }
    EntityValue existingAnul = ec.entity.find('sv.localization.dte.DteAnulacion')
            .condition('dteEmisionId', dteEmisionId).one()
    if (existingAnul != null && existingAnul.statusId in ['DanlRequested', 'DanlSigned', 'DanlAccepted']) {
        ec.message.addError("DTE ${dteEmisionId} ya tiene anulacion activa (${existingAnul.statusId})"); return [:]
    }
    if (!respNombre || !respNumDoc) {
        ec.message.addError('Anulación: responsableNombre y responsableNumDoc requeridos'); return [:]
    }
    if (tipoAnulacion == '3' && !motivo) {
        ec.message.addError('Anulación tipo 3 (Otro) requiere motivo en texto'); return [:]
    }

    // Plazo de invalidación (Normativa v2.0, plan/13): advertir si está vencido, sin bloquear.
    Object selloDate = dte.fechaProcesamientoMh ?: dte.fechaGeneracion
    if (selloDate != null) {
        Map plazo = ec.service.sync().name('sv.localization.SvBusinessCalendarServices.check#DtePlazo')
                .parameters([eventType: 'invalidacion', tipoDteCode: dte.tipoDteCode, referenceDate: selloDate]).call()
        if (plazo?.withinPlazo == false && plazo?.message) ec.message.addMessage(plazo.message as String, 'warning')
    }

    Map dteJson = dte.jsonOriginal ? (Map) new JsonSlurper().parseText((String) dte.jsonOriginal) : [:]
    Map emisorJson = (Map) dteJson.emisor
    Map receptorJson = (Map) dteJson.receptor
    String receptorTipoDoc = (String) (receptorJson?.tipoDocumento ?: (receptorJson?.nit ? '36' : null))
    String receptorNumDoc = digits(receptorJson?.numDocumento ?: receptorJson?.nit)

    Timestamp now = ec.user.nowTimestamp
    def ldt = now.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()

    Map profileCodes = issuerFiscalProfileForDte(ec, dte)
    String codEstableMh = (String) (emisorJson?.codEstableMH ?: profileCodes.codEstableMH ?: sysProp('sv.dte.cod_estable_mh.default'))
    String codPuntoVentaMh = (String) (emisorJson?.codPuntoVentaMH ?: profileCodes.codPuntoVentaMH ?: sysProp('sv.dte.cod_punto_venta_mh.default'))
    if (!codEstableMh || !codPuntoVentaMh) {
        ec.message.addError('Invalidación v3 requiere codEstableMH y codPuntoVentaMH del emisor; configure perfil fiscal o sv.dte.cod_*_mh.default')
        return [:]
    }

    Map anulacionJson = [
            identificacion: [
                    version: invalidacionVersion(),
                    ambiente: sysProp('sv.dte.mh.ambiente.default', '00'),
                    codigoGeneracion: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                    fecEmi: ldt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                    horEmi: ldt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
                    fusion: dteJson?.identificacion?.fusion ?: null
            ],
            emisor: [
                    nit: digits(emisorJson?.nit),
                    nombre: emisorJson?.nombre,
                    codEstableMH: codEstableMh,
                    codEstable: emisorJson?.codEstable ?: profileCodes.codEstable,
                    codPuntoVentaMH: codPuntoVentaMh,
                    codPuntoVenta: emisorJson?.codPuntoVenta ?: profileCodes.codPuntoVenta,
                    telefono: emisorJson?.telefono,
                    correo: emisorJson?.correo
            ],
            documento: [
                    tipoDte: dte.tipoDteCode,
                    codigoGeneracion: dte.codigoGeneracion,
                    selloRecibido: mockSafeSello(dte),
                    numeroControl: dte.numeroControl,
                    fecEmi: dteJson?.identificacion?.fecEmi,
                    // El schema exige null para tipo 2 y string para tipo 1/3.
                    codigoGeneracionR: tipoAnulacion == '2' ? null : dte.codigoGeneracion,
                    tipoDocumento: receptorTipoDoc,
                    numDocumento: receptorNumDoc,
                    nombre: receptorJson?.nombre,
                    telefono: receptorJson?.telefono,
                    correo: receptorJson?.correo
            ],
            motivo: [
                    tipoAnulacion: Integer.parseInt(tipoAnulacion),
                    motivoAnulacion: tipoAnulacion == '3' ? motivo : null,
                    nombreResponsable: respNombre,
                    tipDocResponsable: respTipDoc,
                    numDocResponsable: digits(respNumDoc),
                    nombreSolicita: solNombre,
                    tipDocSolicita: solTipDoc,
                    numDocSolicita: digits(solNumDoc)
            ]
    ]
    Map schemaValidation = validateAgainstSchema(JsonOutput.toJson(anulacionJson), invalidacionSchemaName())
    if (!schemaValidation.valid) {
        ec.message.addError("JSON anulación inválido: ${schemaValidation.errors.take(5).join('; ')}")
        return [valid: false, schemaName: schemaValidation.schemaName, errors: schemaValidation.errors]
    }

    String anulId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteAnulacion', null, null)
    ec.entity.makeValue('sv.localization.dte.DteAnulacion').setAll([
            dteAnulacionId: anulId,
            dteEmisionId: dteEmisionId,
            codigoGeneracionInvalidacion: anulacionJson.identificacion.codigoGeneracion,
            tipoAnulacionEnumId: "SvAnul${tipoAnulacion}",
            versionInvalidacion: invalidacionVersion(),
            schemaName: invalidacionSchemaName(),
            motivo: motivo,
            statusId: 'DanlRequested',
            fechaSolicitud: now,
            jsonOriginal: JsonOutput.prettyPrint(JsonOutput.toJson(anulacionJson)),
            jwsAnulacion: JsonOutput.toJson(anulacionJson) // se sobreescribirá con el JWS tras firma
    ]).create()
    addEvent(ec, dteEmisionId, 'SvEvtAnulGenerated', "JSON anulacion generado tipo ${tipoAnulacion}", anulacionJson)

    return [dteAnulacionId: anulId, anulacionJson: anulacionJson, valid: true, schemaName: schemaValidation.schemaName]
}

Map postJson(String url, Map payload, Map headers = null) {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('POST')
    conn.setConnectTimeout(Integer.parseInt(sysProp('sv.dte.resilience.connect.timeout.ms', '5000')))
    conn.setReadTimeout(Integer.parseInt(sysProp('sv.dte.resilience.read.timeout.ms', '8000')))
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
    conn.setConnectTimeout(Integer.parseInt(sysProp('sv.dte.resilience.connect.timeout.ms', '5000')))
    conn.setReadTimeout(Integer.parseInt(sysProp('sv.dte.resilience.read.timeout.ms', '8000')))
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

Object anularDte() {
    ExecutionContext ec = execCtx()
    String dteEmisionId = (String) ctx('dteEmisionId')

    // 1) Generar JSON anulación
    Map gen = (Map) ec.service.sync().name('sv.localization.DteAnulationServices.generate#AnulacionJson')
            .parameters([dteEmisionId: dteEmisionId,
                         tipoAnulacion: ctx('tipoAnulacion'),
                         motivo: ctx('motivo'),
                         responsableNombre: ctx('responsableNombre'),
                         responsableTipDoc: ctx('responsableTipDoc'),
                         responsableNumDoc: ctx('responsableNumDoc'),
                         solicitanteNombre: ctx('solicitanteNombre'),
                         solicitanteTipDoc: ctx('solicitanteTipDoc'),
                         solicitanteNumDoc: ctx('solicitanteNumDoc')]).call()
    String anulId = gen.dteAnulacionId
    Map anulacionJson = (Map) gen.anulacionJson
    if (!anulId || !anulacionJson) return [message: 'No se generó JSON anulación']

    // 2) Firmar (modo mock vs firmador real)
    EntityValue anul = one(ec, 'sv.localization.dte.DteAnulacion', [dteAnulacionId: anulId])
    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    String jws
    if (propBool('sv.dte.sign.mock.enabled', false)) {
        jws = "MOCK-JWS-ANUL-${anulacionJson.identificacion.codigoGeneracion}"
    } else {
        try {
            EntityValue cert = one(ec, 'sv.localization.dte.DteCertificate', [nit: anulacionJson.emisor.nit])
            String secretKey = cert?.passwordSecretKey
            String password = secretKey ? (secretKey.startsWith('secret://')
                    ? ec.resource.getLocationText(secretKey, false) : sysProp(secretKey)) : null
            String firmadorBase = sysProp('sv.dte.firmador.url', 'http://localhost:8113').replaceAll('/+$', '')
            Map resp = postJson("${firmadorBase}/firmardocumento/",
                    [nit: anulacionJson.emisor.nit, activo: true, passwordPri: password, dteJson: anulacionJson])
            Map parsed = resp.body ? (Map) new JsonSlurper().parseText((String) resp.body) : [:]
            if (resp.status < 200 || resp.status >= 300 || parsed.status != 'OK' || !parsed.body) {
                throw new IllegalStateException("Firmador rechazó anulación: HTTP ${resp.status} ${resp.body}")
            }
            jws = parsed.body as String
        } catch (Throwable t) {
            anul.setAll([statusId: 'DanlRejected', selloAnulacion: null]).update()
            addEvent(ec, dteEmisionId, 'SvEvtAnulSignedFail', "Firma anulacion fallida: ${t.message}")
            return [dteAnulacionId: anulId, statusId: 'DanlRejected', message: "Firma falló: ${t.message}"]
        }
    }
    anul.setAll([jwsAnulacion: jws, statusId: 'DanlSigned']).update()
    addEvent(ec, dteEmisionId, 'SvEvtAnulSignedOk', 'Firma anulacion OK')

    // 3) Transmitir al endpoint MH (mock o real / simulador)
    boolean mockMh = propBool('sv.dte.mh.mock.enabled', true)
    if (mockMh) {
        String sello = "MOCK-ANUL-${anulacionJson.identificacion.codigoGeneracion}"
        anul.setAll([statusId: 'DanlAccepted', selloAnulacion: sello,
                     fechaProcesamientoMh: ec.user.nowTimestamp]).update()
        dte.setAll([statusId: 'DteCanceled', errorCode: null, errorMessage: null]).update()
        addEvent(ec, dteEmisionId, 'SvEvtAnulAccepted', 'Anulacion mock aceptada', [selloAnulacion: sello], 200)
        Map entrega = prepareAnulacionDelivery(ec, anulId)
        return [dteAnulacionId: anulId, statusId: 'DanlAccepted', selloAnulacion: sello,
                dteEntregaId: entrega?.dteEntregaId, entregaStatusId: entrega?.statusId,
                message: 'Anulación mock aceptada']
    }

    boolean useLocalSim = propBool('sv.dte.test.local', true)
    String authUrl, anulUrl, userMh, pwdMh
    if (useLocalSim) {
        authUrl = sysProp('sv.dte.local.auth.url')
        anulUrl = sysProp('sv.dte.local.anulacion.url')
        userMh = sysProp('sv.dte.local.demo.user', 'demo')
        pwdMh = sysProp('sv.dte.local.demo.password', 'hacienda')
    } else {
        EntityValue cfg = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
        authUrl = cfg?.mhAuthUrl
        anulUrl = cfg?.mhAnulacionUrl
        userMh = cfg?.defaultUserMh
        String k = cfg?.passwordMhSecretKey
        pwdMh = k ? (k.startsWith('secret://') ? ec.resource.getLocationText(k, false) : sysProp(k)) : null
    }
    if (!authUrl || !anulUrl || !userMh || !pwdMh) {
        anul.setAll([statusId: 'DanlRejected']).update()
        addEvent(ec, dteEmisionId, 'SvEvtAnulRejected', 'config MH incompleta para anulacion')
        return [dteAnulacionId: anulId, statusId: 'DanlRejected', message: 'config MH incompleta para anulación']
    }
    try {
        Map authResp = postForm(authUrl, [user: userMh, pwd: pwdMh])
        if (authResp.status >= 300) throw new IllegalStateException("Auth MH falló: HTTP ${authResp.status}")
        Map authParsed = (Map) new JsonSlurper().parseText((String) authResp.body)
        String token = ((String) authParsed.body.token).trim()
        String bearer = token.toLowerCase(Locale.ROOT).startsWith('bearer ') ? token : "Bearer ${token}"
        Map txResp = postJson(anulUrl, [ambiente: anulacionJson.identificacion.ambiente,
                                        idEnvio: 1, version: invalidacionVersion(), documento: jws,
                                        codigoGeneracion: anulacionJson.identificacion.codigoGeneracion],
                [Authorization: bearer])
        Map mh = txResp.body ? (Map) new JsonSlurper().parseText((String) txResp.body) : [:]
        if (mh.estado == 'PROCESADO' || mh.estado == 'ACEPTADO') {
            anul.setAll([statusId: 'DanlAccepted', selloAnulacion: mh.selloRecibido,
                         fechaProcesamientoMh: ec.user.nowTimestamp]).update()
            dte.setAll([statusId: 'DteCanceled']).update()
            addEvent(ec, dteEmisionId, 'SvEvtAnulAccepted', 'Anulacion aceptada por MH', mh, txResp.status as Integer)
            Map entrega = prepareAnulacionDelivery(ec, anulId)
            return [dteAnulacionId: anulId, statusId: 'DanlAccepted',
                    selloAnulacion: mh.selloRecibido, dteEntregaId: entrega?.dteEntregaId,
                    entregaStatusId: entrega?.statusId, message: 'Anulación aceptada por MH']
        } else {
            anul.setAll([statusId: 'DanlRejected']).update()
            addEvent(ec, dteEmisionId, 'SvEvtAnulRejected', mh.descripcionMsg ?: 'Anulacion rechazada por MH', mh, txResp.status as Integer)
            return [dteAnulacionId: anulId, statusId: 'DanlRejected',
                    message: mh.descripcionMsg ?: 'Anulación rechazada por MH']
        }
    } catch (Throwable t) {
        anul.setAll([statusId: 'DanlRejected']).update()
        addEvent(ec, dteEmisionId, 'SvEvtAnulRejected', t.message)
        return [dteAnulacionId: anulId, statusId: 'DanlRejected', message: t.message]
    }
}

Map prepareAnulacionDelivery(ExecutionContext ec, String dteAnulacionId) {
    try {
        return (Map) ec.service.sync().name('sv.localization.DteEntregaServices.deliver#DteEvento')
                .parameters([eventoTipo: 'INVALIDACION', dteAnulacionId: dteAnulacionId, sendEmail: false]).call()
    } catch (Throwable t) {
        ec.message.addMessage("No se pudo preparar entrega de invalidación: ${t.message}", 'warning')
        return [:]
    }
}
