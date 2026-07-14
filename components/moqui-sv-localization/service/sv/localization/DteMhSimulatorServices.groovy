import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- helpers ----------

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext execCtx() { (ExecutionContext) ctx('ec') }

String header(String name) {
    def req = execCtx().web?.request
    return req == null ? null : req.getHeader(name)
}

Map readJsonBody() {
    String body = execCtx().web?.requestBodyText
    if (!body) return [:]
    try { return (Map) new JsonSlurper().parseText(body) } catch (Exception ignored) { return [:] }
}

Map allParams() {
    Map merged = [:]
    Map req = execCtx().web?.requestParameters
    if (req) merged.putAll(req)
    Map body = readJsonBody()
    if (body) merged.putAll(body)
    return merged
}

void send(int status, Map payload) {
    def web = execCtx().web
    web.response.status = status
    web.sendJsonResponse(payload)
}

String sha1Hex(String input) {
    MessageDigest md = MessageDigest.getInstance('SHA-1')
    byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8))
    return digest.collect { String.format('%02X', it & 0xFF) }.join('')
}

String nowIso() {
    return new Timestamp(System.currentTimeMillis()).toInstant()
            .atZone(ZoneId.of('America/El_Salvador'))
            .toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

Map decodeJwsPayload(String jws) {
    if (!jws) return [:]
    if (jws.startsWith('MOCK-JWS-')) {
        // El mock interno del firmador emite "MOCK-JWS-<codigoGeneracion>";
        // devolvemos un payload mínimo para que el simulador pueda extraer datos.
        String cod = jws.substring('MOCK-JWS-'.length())
        return [identificacion: [codigoGeneracion: cod, tipoDte: '01', ambiente: '00'],
                emisor: [nit: 'MOCK_JWS_NO_NIT']]
    }
    String[] parts = jws.split('\\.')
    if (parts.length < 2) return [:]
    try {
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        return (Map) new JsonSlurper().parseText(payloadJson)
    } catch (Exception ignored) {
        return [:]
    }
}

EntityValue findReceipt(ExecutionContext ec, String codigoGeneracion) {
    return ec.entity.find('sv.localization.dte.SvDteSimulatorReceipt')
            .condition('codigoGeneracion', codigoGeneracion).one()
}

void persistReceipt(ExecutionContext ec, Map data) {
    EntityValue existing = findReceipt(ec, (String) data.codigoGeneracion)
    EntityValue value = existing ?: ec.entity.makeValue('sv.localization.dte.SvDteSimulatorReceipt')
    value.setAll(data)
    value.createOrUpdate()
}

boolean nitIsAuthorized(ExecutionContext ec, String nit) {
    if (!nit) return false
    if (nit == 'MOCK_JWS_NO_NIT') return true  // permitido en modo MOCK-JWS para tests
    long ct = ec.entity.find('moqui.basic.Enumeration')
            .condition('enumTypeId', 'SvDteSimAuthorizedNit')
            .condition('enumCode', nit).count()
    return ct > 0
}

// ---------- /seguridad/auth ----------

Object authToken() {
    ExecutionContext ec = execCtx()
    Map params = allParams()
    String user = params.user ?: params.usuario ?: params.username
    String pwd = params.pwd ?: params.password ?: params.clave
    if (!user || !pwd) {
        send(401, [status: 'ERROR', body: null,
                   error: [codigo: '401', mensaje: 'Faltan credenciales user/pwd']])
        return [:]
    }
    EntityValue authUser = ec.entity.find('moqui.basic.Enumeration')
            .condition('enumTypeId', 'SvDteSimAuthUser')
            .condition('enumCode', user).one()
    if (authUser == null || authUser.description != pwd) {
        send(401, [status: 'ERROR', body: null,
                   error: [codigo: '401', mensaje: 'Usuario o clave incorrectos']])
        return [:]
    }
    String token = 'SIM-' + sha1Hex("${user}:${System.currentTimeMillis()}")
    send(200, [status: 'OK',
               body: [token: token, roles: ['EMISOR_DTE'], tokenType: 'Bearer',
                      user: user, rol: 'EMISOR_DTE', codigo: '001'],
               error: null])
    return [:]
}

// ---------- /fesv/recepciondte ----------

Map authorizationCheck() {
    String auth = header('Authorization')
    if (!auth || !auth.toLowerCase(Locale.ROOT).startsWith('bearer ')) {
        send(401, [status: 'ERROR', body: null,
                   error: [codigo: '401', mensaje: 'Authorization Bearer requerido']])
        return [ok: false]
    }
    String token = auth.substring(7).trim()
    if (!token.startsWith('SIM-')) {
        send(401, [status: 'ERROR', body: null,
                   error: [codigo: '401', mensaje: 'Token de simulador inválido']])
        return [ok: false]
    }
    return [ok: true, token: token]
}

Map simForce() {
    String forced = header('X-Sim-Force')
    return [force: forced]
}

Object processReception(String operation, String selloField, String estadoOk, Map rechazoCodigoMsg) {
    ExecutionContext ec = execCtx()
    Map authCheck = authorizationCheck()
    if (!authCheck.ok) return [:]
    Map body = readJsonBody()
    if (body.isEmpty()) {
        send(400, [estado: 'RECHAZADO', codigoMsg: '101', descripcionMsg: 'Body JSON vacío'])
        return [:]
    }
    String documento = body.documento
    Map payload = decodeJwsPayload(documento)
    String codigoGeneracion = payload?.identificacion?.codigoGeneracion ?: body.codigoGeneracion
    String tipoDte = payload?.identificacion?.tipoDte ?: body.tipoDte
    String ambiente = payload?.identificacion?.ambiente ?: body.ambiente
    String nitEmisor = payload?.emisor?.nit ?: body.nit
    if (!codigoGeneracion) {
        send(400, [estado: 'RECHAZADO', codigoMsg: '101',
                   descripcionMsg: 'codigoGeneracion no extraíble del documento'])
        return [:]
    }

    String forced = simForce().force
    if (forced == 'REJECT_DUPLICATED') {
        Map respFail = [version: 2, ambiente: ambiente, versionApp: 2,
                        estado: 'RECHAZADO', codigoGeneracion: codigoGeneracion,
                        selloRecibido: null, fhProcesamiento: nowIso(),
                        clasificaMsg: '11', codigoMsg: rechazoCodigoMsg.duplicated ?: '002',
                        descripcionMsg: 'Documento duplicado', observaciones: ['Forzado por X-Sim-Force']]
        send(200, respFail); return [:]
    }
    if (forced == 'REJECT_NIT') {
        Map respFail = [version: 2, ambiente: ambiente, versionApp: 2,
                        estado: 'RECHAZADO', codigoGeneracion: codigoGeneracion,
                        selloRecibido: null, fhProcesamiento: nowIso(),
                        clasificaMsg: '11', codigoMsg: '301',
                        descripcionMsg: 'NIT no autorizado para emitir', observaciones: ['Forzado por X-Sim-Force']]
        send(200, respFail); return [:]
    }
    if (forced == 'TIMEOUT') {
        Thread.sleep(30_000L)
        send(504, [estado: 'ERROR', codigoMsg: '504', descripcionMsg: 'Gateway timeout simulado'])
        return [:]
    }

    if (!nitIsAuthorized(ec, nitEmisor)) {
        Map respFail = [version: 2, ambiente: ambiente, versionApp: 2,
                        estado: 'RECHAZADO', codigoGeneracion: codigoGeneracion,
                        selloRecibido: null, fhProcesamiento: nowIso(),
                        clasificaMsg: '11', codigoMsg: '301',
                        descripcionMsg: "NIT no autorizado: ${nitEmisor}", observaciones: []]
        send(200, respFail); return [:]
    }

    EntityValue prior = findReceipt(ec, codigoGeneracion)
    String sello
    boolean firstTime
    if (prior != null) {
        sello = prior.selloRecibido
        firstTime = false
    } else {
        sello = sha1Hex("${codigoGeneracion}:${operation}")
        firstTime = true
    }

    Map response = [version: 2, ambiente: ambiente, versionApp: 2,
                    estado: estadoOk, codigoGeneracion: codigoGeneracion,
                    fhProcesamiento: nowIso(), clasificaMsg: '01', codigoMsg: '001',
                    descripcionMsg: 'RECIBIDO', observaciones: []]
    response[selloField] = sello

    persistReceipt(ec, [codigoGeneracion: codigoGeneracion, tipoDte: tipoDte, ambiente: ambiente,
                        selloRecibido: sello, estado: estadoOk, codigoMsg: '001',
                        descripcionMsg: 'RECIBIDO', fhProcesamiento: ec.user.nowTimestamp,
                        nitEmisor: nitEmisor, recibidoPayload: JsonOutput.toJson(body),
                        respuestaPayload: JsonOutput.toJson(response)])

    if (!firstTime) response.observaciones = ["DTE ya procesado previamente; se devuelve el sello original"]
    send(200, response)
    return [:]
}

Object recepcionDte() {
    return processReception('recepcion', 'selloRecibido', 'PROCESADO', [duplicated: '002'])
}

Object anularDte() {
    return processReception('anulacion', 'selloRecibido', 'PROCESADO', [duplicated: '002'])
}

Object contingenciaDte() {
    return processReception('contingencia', 'selloRecibido', 'PROCESADO', [duplicated: '002'])
}

// ---------- GET /fesv/recepcion/consultadte/{codigoGeneracion} ----------

Object consultarDte() {
    ExecutionContext ec = execCtx()
    String codigoGeneracion = (String) ctx('codigoGeneracion')
    if (!codigoGeneracion) {
        send(400, [estado: 'ERROR', codigoMsg: '400', descripcionMsg: 'codigoGeneracion requerido']); return [:]
    }
    EntityValue receipt = findReceipt(ec, codigoGeneracion)
    if (receipt == null) {
        send(404, [estado: 'NO_ENCONTRADO', codigoMsg: '404',
                   descripcionMsg: "DTE ${codigoGeneracion} no procesado por simulador"]); return [:]
    }
    Map response = [version: 2, ambiente: receipt.ambiente, versionApp: 2,
                    estado: receipt.estado, codigoGeneracion: receipt.codigoGeneracion,
                    selloRecibido: receipt.selloRecibido,
                    fhProcesamiento: receipt.fhProcesamiento?.toString(),
                    clasificaMsg: '01', codigoMsg: receipt.codigoMsg,
                    descripcionMsg: receipt.descripcionMsg, observaciones: []]
    send(200, response)
    return [:]
}
