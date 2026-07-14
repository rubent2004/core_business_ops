import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.net.HttpURLConnection
import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

BigDecimal decimal(Object raw, BigDecimal fallback = BigDecimal.ZERO) {
    if (raw == null || raw == '') return fallback
    return new BigDecimal(raw.toString())
}

BigDecimal money(Object raw) { return decimal(raw).setScale(2, RoundingMode.HALF_UP) }

String clean(Object raw) { return raw == null ? null : raw.toString().trim() }

String sysProp(String name, String fallback = null) {
    String value = System.getProperty(name)
    if (value) return value
    String envName = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')
    return System.getenv(envName) ?: fallback
}

String resolveSecret(ExecutionContext ec, String key) {
    if (!key) return null
    if (key.startsWith('secret://')) return ec.resource.getLocationText(key, false)
    return sysProp(key)
}

int httpConnectTimeoutMs() { Integer.parseInt(sysProp('sv.dte.resilience.connect.timeout.ms', '5000')) }
int httpReadTimeoutMs() { Integer.parseInt(sysProp('sv.dte.resilience.read.timeout.ms', '8000')) }

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

Map getJson(String url, Map headers = null) {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('GET')
    conn.setConnectTimeout(httpConnectTimeoutMs())
    conn.setReadTimeout(httpReadTimeoutMs())
    conn.setRequestProperty('Accept', 'application/json')
    headers?.each { k, v -> if (v != null) conn.setRequestProperty(k as String, v as String) }
    int status = conn.responseCode
    String body = (status >= 400 ? conn.errorStream : conn.inputStream)?.getText('UTF-8')
    return [status: status, body: body]
}

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

boolean boolValue(Object raw, boolean fallback) {
    if (raw == null || raw == '') return fallback
    if (raw instanceof Boolean) return raw
    return clean(raw).equalsIgnoreCase('true') || clean(raw).equalsIgnoreCase('Y')
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

/** Montos faciales (positivos) del DTE recibido. El signo de NC se aplica al armar el libro. */
Map receivedAmounts(Map resumen) {
    return [
            montoExento     : money(resumen?.totalExenta),
            montoNoSujeto   : money(resumen?.totalNoSuj),
            montoGravado    : money(resumen?.totalGravada),
            iva             : ivaFromResumen(resumen),
            iva1pctRetenido : money(resumen?.ivaRete ?: resumen?.ivaRete1),
            iva1pctPercibido: money(resumen?.ivaPerci ?: resumen?.ivaPerci1),
            total           : totalFromResumen(resumen)
    ]
}

LocalDate parseFecEmi(Map ident) {
    String fecEmi = clean(ident?.fecEmi)
    return fecEmi ? LocalDate.parse(fecEmi.substring(0, 10)) : null
}

/**
 * import#DteRecibido — Importa el JSON de un DTE recibido de un proveedor como registro
 * independiente (DteRecepcion). Valida contra el schema oficial reutilizando
 * DteServices.validate#DteJson. Deduplica por codigoGeneracion. No crea Invoice en Mantle:
 * el libro de IVA compras lo toma como fuente propia.
 */
Object importDteRecibido() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    String jsonText = ctx('jsonText') as String
    boolean allowUnvalidated = boolValue(ctx('allowUnvalidated'), false)
    String selloParam = clean(ctx('selloRecepcion'))

    if (!organizationPartyId) { ec.message.addError('organizationPartyId requerido'); return [imported: false] }
    if (!jsonText?.trim()) { ec.message.addError('jsonText requerido'); return [imported: false] }

    Map json
    try {
        json = (Map) new JsonSlurper().parseText(jsonText)
    } catch (Exception e) {
        ec.message.addError("JSON inválido: ${e.message}")
        return [imported: false]
    }

    Map ident = (Map) json.identificacion
    String tipoDteCode = clean(ident?.tipoDte)
    String codigoGeneracion = clean(ident?.codigoGeneracion)
    String numeroControl = clean(ident?.numeroControl)
    Integer version = ident?.version == null ? null : (ident.version as Integer)
    if (!tipoDteCode || !codigoGeneracion) {
        ec.message.addError('El JSON no tiene identificacion.tipoDte o identificacion.codigoGeneracion')
        return [imported: false]
    }

    String sello = clean(json.selloRecibido) ?: clean(((Map) json.respuestaMH)?.selloRecibido) ?: selloParam

    Map vres = ec.service.sync().name('sv.localization.DteServices.validate#DteJson')
            .parameters([dteJsonText: jsonText, tipoDteCode: tipoDteCode]).call()
    boolean valid = vres?.valid == true
    List<String> errors = (List<String>) (vres?.errors ?: [])
    String schemaName = (String) vres?.schemaName

    if (!valid && !allowUnvalidated) {
        ec.message.addError("DTE recibido no pasó validación de schema (${schemaName}): ${errors.take(5).join('; ')}")
        return [imported: false, valid: false, schemaName: schemaName, errors: errors]
    }

    Map emisor = (Map) json.emisor
    Map resumen = (Map) json.resumen
    LocalDate fecha = parseFecEmi(ident)
    Map montos = receivedAmounts(resumen)

    Map fields = [
            codigoGeneracion : codigoGeneracion,
            numeroControl    : numeroControl,
            selloRecepcion   : sello,
            tipoDteCode      : tipoDteCode,
            versionDte       : version,
            schemaName       : schemaName,
            receptorPartyId  : organizationPartyId,
            emisorNit        : clean(emisor?.nit),
            emisorNrc        : clean(emisor?.nrc),
            emisorNombre     : clean(emisor?.nombre) ?: clean(emisor?.nombreComercial),
            fechaEmision     : fecha == null ? null : Timestamp.valueOf(fecha.atStartOfDay()),
            montoExento      : montos.montoExento,
            montoNoSujeto    : montos.montoNoSujeto,
            montoGravado     : montos.montoGravado,
            iva              : montos.iva,
            iva1pctRetenido  : montos.iva1pctRetenido,
            iva1pctPercibido : montos.iva1pctPercibido,
            total            : montos.total,
            validatedBySchema: valid ? 'Y' : 'N',
            schemaErrors     : errors ? errors.take(20).join('\n') : null,
            jsonOriginal     : jsonText,
            importedByUserId : ec.user.userId,
            importedDate     : ec.user.nowTimestamp
    ]

    EntityValue existing = ec.entity.find('sv.localization.dte.DteRecepcion')
            .condition('codigoGeneracion', codigoGeneracion).one()
    String recId
    boolean updated = false
    if (existing == null) {
        EntityValue rec = ec.entity.makeValue('sv.localization.dte.DteRecepcion')
        rec.dteRecepcionId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteRecepcion', null, null)
        rec.setAll(fields)
        rec.create()
        recId = rec.dteRecepcionId
    } else {
        existing.setAll(fields)
        existing.update()
        recId = existing.dteRecepcionId
        updated = true
    }

    if (!sello) {
        ec.message.addMessage("DTE recibido importado sin sello de recepción: no entrará al libro de IVA compras hasta tener sello.", 'warning')
    }

    return [imported: true, updated: updated, dteRecepcionId: recId, valid: valid, schemaName: schemaName,
            errors: errors, codigoGeneracion: codigoGeneracion, tipoDteCode: tipoDteCode,
            emisorNombre: fields.emisorNombre, total: montos.total]
}

Map consultaConfig(ExecutionContext ec) {
    boolean useLocalSim = boolValue(ctx('useLocalSim'), boolValue(sysProp('sv.dte.test.local'), true))
    EntityValue config = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
    if (useLocalSim) {
        return [useLocalSim: true, authUrl: sysProp('sv.dte.local.auth.url'),
                consultaUrl: sysProp('sv.dte.local.consulta.url'),
                userMh: sysProp('sv.dte.local.demo.user', 'demo'),
                mhPassword: sysProp('sv.dte.local.demo.password', 'hacienda')]
    }
    return [useLocalSim: false, authUrl: config?.mhAuthUrl, consultaUrl: config?.mhConsultaUrl,
            userMh: config?.defaultUserMh, mhPassword: resolveSecret(ec, (String) config?.passwordMhSecretKey)]
}

Map consultadte(ExecutionContext ec, EntityValue rec) {
    Map cfg = consultaConfig(ec)
    String consultaUrl = clean(cfg.consultaUrl)
    boolean mockFallback = boolValue(sysProp('sv.dte.mh.mock.enabled'), true)
    if (!consultaUrl && mockFallback) {
        Map mock = [estado: rec.selloRecepcion ? 'PROCESADO_MOCK' : 'SIN_SELLO',
                    codigoGeneracion: rec.codigoGeneracion, selloRecibido: rec.selloRecepcion,
                    descripcionMsg: rec.selloRecepcion ? 'Consulta mock local' : 'DTE sin sello registrado',
                    eventosRelacionados: []]
        return [status: 200, body: JsonOutput.toJson(mock), parsed: mock, mock: true]
    }
    if (!consultaUrl) throw new IllegalStateException('mhConsultaUrl ausente para consultadte')

    Map headers = [:]
    if (cfg.authUrl && cfg.userMh && cfg.mhPassword) {
        Map authResp = postForm((String) cfg.authUrl, [user: cfg.userMh, pwd: cfg.mhPassword])
        if (authResp.status < 200 || authResp.status >= 300 || !authResp.body) {
            throw new IllegalStateException("Auth MH falló para consultadte: HTTP ${authResp.status}")
        }
        Map authParsed = (Map) new JsonSlurper().parseText((String) authResp.body)
        String token = clean(authParsed?.body?.token)
        if (!token) throw new IllegalStateException("Auth MH respondió sin token: ${authResp.body}")
        headers.Authorization = token.toLowerCase(Locale.ROOT).startsWith('bearer ') ? token : "Bearer ${token}"
    }
    Map q = getJson("${consultaUrl.replaceAll('/+$', '')}/${rec.codigoGeneracion}", headers)
    Map parsed = q.body ? (Map) new JsonSlurper().parseText((String) q.body) : [:]
    return [status: q.status, body: q.body, parsed: parsed, mock: false]
}

String alertFromConsulta(EntityValue rec, Map parsed) {
    String estado = clean(parsed?.estado) ?: clean(parsed?.estadoDte)
    String sello = clean(parsed?.selloRecibido) ?: clean(parsed?.selloRecepcion) ?: clean(rec.selloRecepcion)
    Object evRaw = parsed?.eventosRelacionados ?: parsed?.eventos ?: parsed?.evento ?: []
    String evText = JsonOutput.toJson(evRaw).toLowerCase(Locale.ROOT)
    List<String> alerts = []
    if (!sello) alerts.add('sin sello válido')
    if (estado?.toUpperCase(Locale.ROOT)?.contains('INVALID')) alerts.add('invalidado observado en MH')
    if (evText.contains('retorno') || evText.contains('"18"') || evText.contains('eret')) alerts.add('retorno asociado observado en MH')
    return alerts.join('; ')
}

Object refreshDteRecibidoStatus() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteRecepcionId = clean(ctx('dteRecepcionId'))
    String codigoGeneracion = clean(ctx('codigoGeneracion'))
    EntityValue rec = dteRecepcionId ? one(ec, 'sv.localization.dte.DteRecepcion', [dteRecepcionId: dteRecepcionId])
            : (codigoGeneracion ? one(ec, 'sv.localization.dte.DteRecepcion', [codigoGeneracion: codigoGeneracion]) : null)
    if (rec == null) { ec.message.addError('DteRecepcion no encontrado'); return [updated: false] }
    try {
        Map q = consultadte(ec, rec)
        Map parsed = (Map) q.parsed
        String estado = clean(parsed?.estado) ?: clean(parsed?.estadoDte) ?: (q.status == 404 ? 'NO_ENCONTRADO' : null)
        String sello = clean(parsed?.selloRecibido) ?: clean(parsed?.selloRecepcion) ?: clean(rec.selloRecepcion)
        Object eventos = parsed?.eventosRelacionados ?: parsed?.eventos ?: parsed?.evento ?: []
        String alert = alertFromConsulta(rec, parsed)
        rec.setAll([
                consultaEstado         : estado,
                consultaSello          : sello,
                selloRecepcion         : sello ?: rec.selloRecepcion,
                consultaPayloadJson    : q.body ?: JsonOutput.toJson(parsed),
                consultaDate           : ec.user.nowTimestamp,
                eventosRelacionadosJson: JsonOutput.toJson(eventos),
                requiresReview         : alert ? 'Y' : 'N',
                reviewMessage          : alert ?: null
        ])
        rec.update()
        if (alert) ec.message.addMessage("DTE recibido ${rec.codigoGeneracion}: ${alert}", 'warning')
        return [updated: true, dteRecepcionId: rec.dteRecepcionId, codigoGeneracion: rec.codigoGeneracion,
                status: q.status, consultaEstado: estado, selloRecepcion: rec.selloRecepcion,
                requiresReview: alert ? true : false, reviewMessage: alert, mock: q.mock]
    } catch (Throwable t) {
        rec.setAll([consultaEstado: 'ERROR', consultaPayloadJson: t.message,
                    consultaDate: ec.user.nowTimestamp, requiresReview: 'Y',
                    reviewMessage: "consulta falló: ${t.message}".take(500)]).update()
        return [updated: false, dteRecepcionId: rec.dteRecepcionId, codigoGeneracion: rec.codigoGeneracion,
                status: 0, requiresReview: true, reviewMessage: rec.reviewMessage]
    }
}

Object refreshDteRecibidosStatus() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String organizationPartyId = clean(ctx('organizationPartyId'))
    String periodText = clean(ctx('periodYearMonth'))
    if (!organizationPartyId) { ec.message.addError('organizationPartyId requerido'); return [updatedCount: 0] }
    YearMonth period = periodText ? YearMonth.parse(periodText) : null
    List<EntityValue> recs = ec.entity.find('sv.localization.dte.DteRecepcion')
            .condition('receptorPartyId', organizationPartyId)
            .orderBy('-fechaEmision')
            .list()
    int updated = 0
    int failed = 0
    List<Map> results = []
    for (EntityValue rec in recs) {
        if (period != null) {
            Timestamp ts = (Timestamp) rec.fechaEmision
            LocalDate fecha = ts?.toLocalDateTime()?.toLocalDate()
            if (fecha == null || YearMonth.from(fecha) != period) continue
        }
        Map r = (Map) ec.service.sync().name('sv.localization.DteReceptionServices.refresh#DteRecibidoStatus')
                .parameters([dteRecepcionId: rec.dteRecepcionId, useLocalSim: ctx('useLocalSim')]).call()
        results.add(r)
        if (r.updated) updated++ else failed++
    }
    return [updatedCount: updated, failedCount: failed, results: results]
}
