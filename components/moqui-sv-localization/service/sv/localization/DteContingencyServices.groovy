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
    String v = System.getProperty(name)
    if (v) return v
    String envName = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')
    return System.getenv(envName) ?: fallback
}

boolean propBool(String name, boolean fallback) {
    String v = sysProp(name)
    if (v == null) return fallback
    return v.equalsIgnoreCase('true') || v.equalsIgnoreCase('Y') || v == '1'
}

String contingenciaSchemaName() { 'contingencia-schema-v4.json' }
int contingenciaVersion() { 4 }

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

Map issuerFiscalProfileForDte(ExecutionContext ec, EntityValue dte) {
    if (!dte?.invoiceId) return [:]
    EntityValue invoice = one(ec, 'mantle.account.invoice.Invoice', [invoiceId: dte.invoiceId])
    if (!invoice?.fromPartyId) return [:]
    EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', invoice.fromPartyId).one()
    return [
            tipoEstablecimiento: profile?.tipoEstablecimientoMhCode,
            codEstableMH: profile?.codEstableMhCode,
            codPuntoVentaMH: profile?.codPuntoVentaMhCode
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

Object validateContingenciaJson() {
    ExecutionContext ec = execCtx()
    Map contingenciaJson = (Map) ctx('contingenciaJson')
    String contingenciaJsonText = (String) ctx('contingenciaJsonText')
    if (!contingenciaJsonText && contingenciaJson != null) contingenciaJsonText = JsonOutput.toJson(contingenciaJson)
    if (!contingenciaJsonText) {
        ec.message.addError('No hay JSON contingencia para validar')
        return [valid: false, schemaName: contingenciaSchemaName(), errors: ['No hay JSON contingencia para validar']]
    }
    return validateAgainstSchema(contingenciaJsonText, contingenciaSchemaName())
}

Integer contTypeNumber(EntityValue cont) {
    String raw = (String) cont?.tipoContingenciaEnumId
    String num = raw?.replaceAll('[^0-9]', '')
    return num ? Integer.parseInt(num) : 1
}

Map buildContingenciaJson(ExecutionContext ec, EntityValue cont, List<EntityValue> items, String reason) {
    if (items == null || items.isEmpty()) {
        return [error: "Contingencia ${cont.dteContingenciaId} no tiene DTEs asociados; no se puede cerrar vacia"]
    }

    Timestamp now = ec.user.nowTimestamp
    Map firstDteJson = null
    EntityValue firstDte = null
    List<Map> detalleDTE = []
    int idx = 1
    for (EntityValue it in items) {
        EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: it.dteEmisionId])
        if (dte == null) continue
        if (firstDteJson == null && dte.jsonOriginal) {
            firstDteJson = (Map) new JsonSlurper().parseText((String) dte.jsonOriginal)
            firstDte = dte
        }
        detalleDTE.add([noItem: idx++, codigoGeneracion: dte.codigoGeneracion, tipoDoc: dte.tipoDteCode])
    }
    if (detalleDTE.isEmpty()) {
        return [error: "Contingencia ${cont.dteContingenciaId} no tiene DTEs validos asociados"]
    }
    if (detalleDTE.size() > 1000) {
        return [error: "Contingencia ${cont.dteContingenciaId} excede el límite oficial de 1000 DTEs"]
    }
    if (firstDteJson == null) {
        return [error: "Contingencia ${cont.dteContingenciaId} no tiene JSON de DTE para derivar emisor"]
    }

    Map emisorJson = (Map) firstDteJson.emisor
    Map profileCodes = issuerFiscalProfileForDte(ec, firstDte)
    String codEstableMh = (String) (emisorJson?.codEstableMH ?: profileCodes.codEstableMH ?: sysProp('sv.dte.cod_estable_mh.default'))
    String codPuntoVentaMh = (String) (emisorJson?.codPuntoVentaMH ?: profileCodes.codPuntoVentaMH ?: sysProp('sv.dte.cod_punto_venta_mh.default'))
    if (!codEstableMh || !codPuntoVentaMh) {
        return [error: 'Contingencia v4 requiere codEstableMH y codPuntoVentaMH del emisor; configure perfil fiscal o sv.dte.cod_*_mh.default']
    }
    def endLdt = now.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()
    Timestamp startTs = (Timestamp) (cont.fromDate ?: now)
    def startLdt = startTs.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()

    Map contJson = [
            identificacion: [
                    version: contingenciaVersion(),
                    ambiente: sysProp('sv.dte.mh.ambiente.default', '00'),
                    codigoGeneracion: cont.codigoGeneracionContingencia,
                    fTransmision: endLdt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                    hTransmision: endLdt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME)
            ],
            emisor: [
                    nit: emisorJson?.nit,
                    nombre: emisorJson?.nombre,
                    nombreResponsable: emisorJson?.nombre,
                    tipoDocResponsable: '36',
                    numeroDocResponsable: emisorJson?.nit,
                    tipoEstablecimiento: emisorJson?.tipoEstablecimiento ?: profileCodes.tipoEstablecimiento ?: sysProp('sv.dte.tipo_establecimiento.default', '02'),
                    codEstableMH: codEstableMh,
                    codPuntoVentaMH: codPuntoVentaMh,
                    telefono: emisorJson?.telefono,
                    correo: emisorJson?.correo
            ],
            detalleDTE: detalleDTE,
            motivo: [
                    fInicio: startLdt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                    fFin: endLdt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                    hInicio: startLdt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
                    hFin: endLdt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
                    tipoContingencia: contTypeNumber(cont),
                    motivoContingencia: reason ?: cont.motivoTexto ?: 'Recuperacion de servicio MH'
            ]
    ]
    return [contingenciaJson: contJson]
}

Object generateContingenciaJson() {
    ExecutionContext ec = execCtx()
    String contId = (String) ctx('dteContingenciaId')
    String reason = (String) ctx('reason')
    EntityValue cont = one(ec, 'sv.localization.dte.DteContingencia', [dteContingenciaId: contId])
    if (cont == null) return [message: "Contingencia ${contId} no existe"]
    List<EntityValue> items = ec.entity.find('sv.localization.dte.DteContingenciaItem')
            .condition('dteContingenciaId', contId).orderBy('noItem').list()
    Map built = buildContingenciaJson(ec, cont, items, reason)
    if (built.error) {
        ec.message.addError((String) built.error)
        return [valid: false, message: built.error]
    }
    Map validation = validateAgainstSchema(JsonOutput.toJson(built.contingenciaJson), contingenciaSchemaName())
    return [contingenciaJson: built.contingenciaJson, valid: validation.valid,
            schemaName: validation.schemaName, errors: validation.errors]
}

Object openContingencia() {
    ExecutionContext ec = execCtx()
    String tipo = (ctx('tipoContingencia') ?: '1') as String
    String motivo = (String) ctx('motivoTexto')
    EntityValue h = one(ec, 'sv.localization.dte.SvDteSystemHealth', [healthId: 'DEFAULT'])
    if (h?.activeContingenciaId) {
        return [dteContingenciaId: h.activeContingenciaId, message: 'Contingencia ya abierta']
    }
    String contId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteContingencia', null, null)
    ec.entity.makeValue('sv.localization.dte.DteContingencia').setAll([
            dteContingenciaId: contId,
            codigoGeneracionContingencia: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            versionContingencia: contingenciaVersion(),
            schemaName: contingenciaSchemaName(),
            tipoContingenciaEnumId: "SvCont${tipo}",
            motivoTexto: motivo,
            fromDate: ec.user.nowTimestamp,
            statusId: 'DcontOpen']).create()
    if (h != null) h.setAll([activeContingenciaId: contId, contingencyOpenedAt: ec.user.nowTimestamp]).update()
    return [dteContingenciaId: contId]
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

Object closeContingencia() {
    ExecutionContext ec = execCtx()
    String contId = (String) ctx('dteContingenciaId')
    String reason = (String) ctx('reason')
    EntityValue cont = one(ec, 'sv.localization.dte.DteContingencia', [dteContingenciaId: contId])
    if (cont == null) {
        return [statusId: null, message: "Contingencia ${contId} no existe"]
    }
    if (cont.statusId != 'DcontOpen') {
        return [statusId: cont.statusId, message: "Contingencia ${contId} no está abierta"]
    }
    // Listar DTEs en contingencia
    List<EntityValue> items = ec.entity.find('sv.localization.dte.DteContingenciaItem')
            .condition('dteContingenciaId', contId).orderBy('noItem').list()
    Map built = buildContingenciaJson(ec, cont, items, reason)
    if (built.error) {
        return [statusId: 'DcontOpen', message: built.error]
    }
    Map contJson = (Map) built.contingenciaJson
    Map validation = validateAgainstSchema(JsonOutput.toJson(contJson), contingenciaSchemaName())
    if (!validation.valid) {
        return [statusId: 'DcontOpen', message: "JSON contingencia inválido: ${validation.errors.take(5).join('; ')}",
                errors: validation.errors]
    }
    Map emisorJson = (Map) contJson.emisor

    // Firma (mock o real)
    String jws
    if (propBool('sv.dte.sign.mock.enabled', false)) {
        jws = "MOCK-JWS-CONT-${cont.codigoGeneracionContingencia}"
    } else {
        try {
            EntityValue cert = one(ec, 'sv.localization.dte.DteCertificate', [nit: emisorJson?.nit])
            String k = cert?.passwordSecretKey
            String pwd = k ? (k.startsWith('secret://') ? ec.resource.getLocationText(k, false) : sysProp(k)) : null
            String base = sysProp('sv.dte.firmador.url', 'http://localhost:8113').replaceAll('/+$', '')
            Map resp = postJson("${base}/firmardocumento/",
                    [nit: emisorJson?.nit, activo: true, passwordPri: pwd, dteJson: contJson])
            Map p = resp.body ? (Map) new JsonSlurper().parseText((String) resp.body) : [:]
            if (resp.status < 200 || resp.status >= 300 || p.status != 'OK' || !p.body) {
                throw new IllegalStateException("Firmador rechazó contingencia: HTTP ${resp.status} ${resp.body}")
            }
            jws = p.body as String
        } catch (Throwable t) {
            return [statusId: 'DcontOpen', message: "Firma contingencia falló: ${t.message}"]
        }
    }
    cont.setAll([jwsContingencia: jws]).update()

    // Transmitir reporte de contingencia
    String sello = null
    boolean mockMh = propBool('sv.dte.mh.mock.enabled', true)
    if (mockMh) {
        sello = "MOCK-CONT-${cont.codigoGeneracionContingencia}"
    } else {
        boolean useLocalSim = propBool('sv.dte.test.local', true)
        String authUrl, contUrl, userMh, pwdMh
        if (useLocalSim) {
            authUrl = sysProp('sv.dte.local.auth.url')
            contUrl = sysProp('sv.dte.local.contingencia.url')
            userMh = sysProp('sv.dte.local.demo.user', 'demo')
            pwdMh = sysProp('sv.dte.local.demo.password', 'hacienda')
        } else {
            EntityValue cfg = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
            authUrl = cfg?.mhAuthUrl
            contUrl = cfg?.mhContingenciaUrl
            userMh = cfg?.defaultUserMh
            String k = cfg?.passwordMhSecretKey
            pwdMh = k ? (k.startsWith('secret://') ? ec.resource.getLocationText(k, false) : sysProp(k)) : null
        }
        try {
            Map authResp = postForm(authUrl, [user: userMh, pwd: pwdMh])
            String token = ((Map) new JsonSlurper().parseText((String) authResp.body)).body.token
            String bearer = token.toLowerCase(Locale.ROOT).startsWith('bearer ') ? token : "Bearer ${token}"
            Map txResp = postJson(contUrl, [ambiente: contJson.identificacion.ambiente,
                                            idEnvio: 1, version: contingenciaVersion(), documento: jws,
                                            codigoGeneracion: contJson.identificacion.codigoGeneracion],
                    [Authorization: bearer])
            Map mh = txResp.body ? (Map) new JsonSlurper().parseText((String) txResp.body) : [:]
            if (mh.estado == 'PROCESADO' || mh.estado == 'ACEPTADO') {
                sello = mh.selloRecibido
            } else {
                return [statusId: 'DcontOpen', message: mh.descripcionMsg ?: 'Contingencia rechazada por MH']
            }
        } catch (Throwable t) {
            return [statusId: 'DcontOpen', message: t.message]
        }
    }

    Timestamp now = ec.user.nowTimestamp
    cont.setAll([selloContingencia: sello, statusId: 'DcontClosed', thruDate: now]).update()

    // Liberar la salud del sistema
    EntityValue h = one(ec, 'sv.localization.dte.SvDteSystemHealth', [healthId: 'DEFAULT'])
    if (h?.activeContingenciaId == contId) {
        h.setAll([activeContingenciaId: null, contingencyOpenedAt: null]).update()
    }

    // Retransmitir DTEs individuales (ya con MH UP)
    int retransmitted = 0, accepted = 0
    for (EntityValue it in items) {
        EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: it.dteEmisionId])
        if (dte == null || dte.statusId == 'DteAccepted') continue
        // Reset estado para reintento limpio
        dte.setAll([statusId: 'DteSigned', tipoTransmisionEnumId: 'SvTransNormal']).update()
        retransmitted++
        try {
            Map r = (Map) ec.service.sync().name('sv.localization.DteServices.transmit#Dte')
                    .parameters([dteEmisionId: dte.dteEmisionId]).call()
            if (r.transmitted) accepted++
        } catch (Throwable t) {
            ec.logger.warn("Retransmisión post-contingencia falló DTE ${dte.dteEmisionId}: ${t.message}")
        }
    }

    return [statusId: 'DcontClosed', selloContingencia: sello,
            dtesRetransmitidos: retransmitted, dtesAceptados: accepted,
            message: "Contingencia cerrada. ${accepted}/${retransmitted} DTEs aceptados tras retransmisión"]
}
