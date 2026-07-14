import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import javax.xml.transform.stream.StreamSource

Object ctx(String name) {
    Binding b = getBinding()
    return b?.hasVariable(name) ? b.getVariable(name) : null
}

String clean(Object raw) { return raw == null ? null : raw.toString().trim() }

boolean truthy(Object raw) {
    if (raw == null) return false
    if (raw instanceof Boolean) return raw
    String s = raw.toString()
    return s.equalsIgnoreCase('true') || s.equalsIgnoreCase('Y') || s == '1'
}

EntityValue one(ExecutionContext ec, String entity, Map cond) {
    return ec.entity.find(entity).condition(cond).one()
}

Map parseJson(Object jsonText) {
    String s = clean(jsonText)
    return s ? (Map) new JsonSlurper().parseText(s) : [:]
}

String receptorPartyIdForDte(ExecutionContext ec, EntityValue dte) {
    if (!dte?.invoiceId) return null
    EntityValue invoice = one(ec, 'mantle.account.invoice.Invoice', [invoiceId: dte.invoiceId])
    return clean(invoice?.toPartyId)
}

Map fallbackInvalidacionJson(EntityValue anul, EntityValue dte, Map dteJson) {
    return [
            identificacion: [
                    version: anul?.versionInvalidacion ?: 3,
                    codigoGeneracion: anul?.codigoGeneracionInvalidacion,
                    fecEmi: clean(anul?.fechaSolicitud)
            ],
            emisor: dteJson.emisor,
            documento: dteJson.receptor ?: dteJson.sujetoExcluido,
            documentoRelacionado: [[
                    tipoDocumento: dte?.tipoDteCode,
                    codigoGeneracion: dte?.codigoGeneracion,
                    fechaEmision: dteJson?.identificacion?.fecEmi
            ]]
    ]
}

/** Resuelve emisor/receptor/evento/sello/firma para un evento de invalidación o retorno. */
Map resolveEvento(ExecutionContext ec, String eventoTipo, String dteAnulacionId, String dteFiscalEventId) {
    if (eventoTipo == 'INVALIDACION') {
        EntityValue anul = one(ec, 'sv.localization.dte.DteAnulacion', [dteAnulacionId: dteAnulacionId])
        if (anul == null) return null
        EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: anul.dteEmisionId])
        Map dteJson = parseJson(dte?.jsonOriginal)
        Map evento = anul.jsonOriginal ? parseJson(anul.jsonOriginal) : fallbackInvalidacionJson(anul, dte, dteJson)
        return [dte: dte, dteJson: dteJson, evento: evento, motivo: anul.motivo,
                jws: anul.jwsAnulacion, sello: anul.selloAnulacion,
                codGenEvento: anul.codigoGeneracionInvalidacion, titulo: 'Evento de Invalidación de DTE',
                receptorPartyId: receptorPartyIdForDte(ec, dte)]
    }

    EntityValue ev = one(ec, 'sv.localization.dte.DteFiscalEvent', [dteFiscalEventId: dteFiscalEventId])
    if (ev == null) return null
    Map eventoJson = parseJson(ev.jsonOriginal)
    String codGenOrig = clean(((List) eventoJson.documentoRelacionado)?.getAt(0)?.codigoGeneracion)
    EntityValue dte = codGenOrig ? one(ec, 'sv.localization.dte.DteEmision', [codigoGeneracion: codGenOrig]) : null
    String titulo = ev.tipoEventoCode == '17' ? 'Evento de Operaciones Especiales'
            : (ev.tipoEventoCode == '18' ? 'Evento de Retorno de DTE' : "Evento Fiscal ${ev.tipoEventoCode}")
    return [dte: dte, dteJson: parseJson(dte?.jsonOriginal), evento: eventoJson, motivo: null,
            jws: ev.jwsCompactSerialization, sello: ev.selloRecepcion,
            codGenEvento: ev.codigoGeneracion, titulo: titulo,
            receptorPartyId: receptorPartyIdForDte(ec, dte)]
}

Map renderEventPdfSafe(ExecutionContext ec, String eventoTipo, String dteAnulacionId, String dteFiscalEventId) {
    try {
        return (Map) ec.service.sync().name('sv.localization.DteEntregaServices.render#DteEventPdf')
                .parameters([eventoTipo: eventoTipo, dteAnulacionId: dteAnulacionId, dteFiscalEventId: dteFiscalEventId]).call()
    } catch (Throwable t) {
        ec.message.addMessage("No se pudo renderizar PDF del evento: ${t.message}", 'warning')
        return [rendered: false, message: t.message]
    }
}

Map renderOriginalDtePdfSafe(ExecutionContext ec, EntityValue dte, String eventoTipo) {
    if (dte == null) return [rendered: false]
    try {
        Map params = [dteEmisionId: dte.dteEmisionId]
        if (eventoTipo == 'INVALIDACION') params.estadoLeyenda = 'INVALIDADO'
        return (Map) ec.service.sync().name('sv.localization.DteServices.render#DtePdf').parameters(params).call()
    } catch (Throwable t) {
        ec.message.addMessage("No se pudo renderizar PDF del DTE original: ${t.message}", 'warning')
        return [rendered: false, message: t.message]
    }
}

int attachmentSize(Map attachment) {
    if (attachment.contentBytes != null) return ((byte[]) attachment.contentBytes).length
    if (attachment.contentText != null) return attachment.contentText.toString().getBytes('UTF-8').length
    return 0
}

Map buildDeliveryArtifacts(ExecutionContext ec, String eventoTipo, String dteAnulacionId, String dteFiscalEventId, Map resolved, Map envelope) {
    String cod = clean(resolved.codGenEvento) ?: UUID.randomUUID().toString()
    String envelopeText = JsonOutput.prettyPrint(JsonOutput.toJson(envelope))
    Map eventPdf = renderEventPdfSafe(ec, eventoTipo, dteAnulacionId, dteFiscalEventId)
    Map dtePdf = renderOriginalDtePdfSafe(ec, (EntityValue) resolved.dte, eventoTipo)

    List<Map> attachments = [[
            fileName: "evento-${cod}.json",
            contentType: 'application/json; charset=UTF-8',
            contentText: envelopeText
    ]]
    if (eventPdf.rendered && eventPdf.pdfBytes) {
        attachments.add([fileName: "evento-${cod}.pdf", contentType: 'application/pdf', contentBytes: eventPdf.pdfBytes])
    }
    if (dtePdf.rendered && dtePdf.pdfBytes) {
        String suffix = eventoTipo == 'INVALIDACION' ? 'invalidado' : 'original'
        attachments.add([fileName: "dte-${suffix}-${((EntityValue) resolved.dte)?.codigoGeneracion ?: cod}.pdf",
                         contentType: 'application/pdf', contentBytes: dtePdf.pdfBytes])
    }

    List<Map> metadata = attachments.collect { Map a ->
        [fileName: a.fileName, contentType: a.contentType, size: attachmentSize(a)]
    }
    return [attachments: attachments, attachmentsJson: JsonOutput.toJson(metadata),
            eventPdfSize: (eventPdf.pdfSize ?: 0) as Integer, dtePdfSize: (dtePdf.pdfSize ?: 0) as Integer,
            eventPdfRendered: eventPdf.rendered == true, dtePdfRendered: dtePdf.rendered == true]
}

EntityValue findExistingEntrega(ExecutionContext ec, String dteAnulacionId, String dteFiscalEventId) {
    if (dteAnulacionId) return one(ec, 'sv.localization.dte.DteEntrega', [dteAnulacionId: dteAnulacionId])
    if (dteFiscalEventId) return one(ec, 'sv.localization.dte.DteEntrega', [dteFiscalEventId: dteFiscalEventId])
    return null
}

Map createEmailDraft(ExecutionContext ec, String receptorEmail, String subject, String bodyText) {
    EntityValue email = ec.entity.makeValue('moqui.basic.email.EmailMessage')
    email.emailMessageId = ec.entity.sequencedIdPrimary('moqui.basic.email.EmailMessage', null, null)
    email.setAll([
            statusId: 'ES_DRAFT',
            subject: subject,
            bodyText: bodyText,
            contentType: 'text/plain; charset=UTF-8',
            fromAddress: System.getProperty('sv.dte.delivery.from', 'no-reply@localhost'),
            fromName: 'SARA ERP DTE',
            toAddresses: receptorEmail,
            fromUserId: ec.user.userId
    ])
    email.create()
    return [emailMessageId: email.emailMessageId]
}

Map sendEmailIfConfigured(ExecutionContext ec, String emailTemplateId, String receptorEmail, String subject, String bodyText, List<Map> attachments) {
    if (!receptorEmail) return [sent: false, error: 'Receptor sin correo']
    if (!emailTemplateId) {
        Map draft = createEmailDraft(ec, receptorEmail, subject, bodyText)
        return [sent: false, emailMessageId: draft.emailMessageId,
                error: 'No hay emailTemplateId configurado; se creó borrador de entrega']
    }
    Map sendOut = (Map) ec.service.sync().name('org.moqui.impl.EmailServices.send#EmailTemplate')
            .parameters([emailTemplateId: emailTemplateId, toAddresses: receptorEmail,
                         bodyParameters: [subject: subject, bodyText: bodyText],
                         attachments: attachments, createEmailMessage: true]).call()
    return [sent: true, emailMessageId: sendOut?.emailMessageId, messageId: sendOut?.messageId]
}

/**
 * deliver#DteEvento - Prepara la entrega al receptor de un evento de invalidación o retorno
 * (Normativa v2.0). Construye el sobre con firmaElectronica y selloRecibido, registra el
 * DteEntrega con token de acuse, genera los adjuntos PDF/JSON y, si se configura una plantilla
 * de correo, intenta enviar. Sin plantilla solo crea borrador y conserva la entrega pendiente.
 */
Object deliverDteEvento() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String eventoTipo = clean(ctx('eventoTipo'))
    String dteAnulacionId = clean(ctx('dteAnulacionId'))
    String dteFiscalEventId = clean(ctx('dteFiscalEventId'))
    boolean sendEmail = truthy(ctx('sendEmail'))
    String emailTemplateId = clean(ctx('emailTemplateId'))

    if (!(eventoTipo in ['INVALIDACION', 'RETORNO'])) {
        ec.message.addError('eventoTipo debe ser INVALIDACION o RETORNO'); return [delivered: false]
    }
    Map r = resolveEvento(ec, eventoTipo, dteAnulacionId, dteFiscalEventId)
    if (r == null) { ec.message.addError('Evento origen no encontrado'); return [delivered: false] }
    if (!r.sello) {
        ec.message.addMessage('El evento aún no tiene sello de recepción; la entrega quedará pendiente.', 'warning')
    }

    Map receptor = (Map) r.evento?.documento ?: (Map) r.evento?.receptor ?: [:]
    String receptorEmail = clean(receptor?.correo)
    String receptorNombre = clean(receptor?.nombre)
    Map envelope = [
            firmaElectronica: r.jws,
            selloRecibido: r.sello,
            evento: r.evento,
            dteOriginal: r.dteJson,
            dteOriginalInvalidado: eventoTipo == 'INVALIDACION'
    ]
    Map artifacts = buildDeliveryArtifacts(ec, eventoTipo, dteAnulacionId, dteFiscalEventId, r, envelope)

    EntityValue entrega = findExistingEntrega(ec, dteAnulacionId, dteFiscalEventId)
    boolean created = entrega == null
    if (created) {
        entrega = ec.entity.makeValue('sv.localization.dte.DteEntrega')
        entrega.dteEntregaId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteEntrega', null, null)
        entrega.entregaToken = UUID.randomUUID().toString().toUpperCase(Locale.ROOT)
        entrega.fechaPreparada = ec.user.nowTimestamp
    }

    int intentos = ((entrega.intentos ?: 0) as int) + (sendEmail ? 1 : 0)
    String currentStatus = clean(entrega.statusId)
    entrega.setAll([
            eventoTipo: eventoTipo,
            dteEmisionId: ((EntityValue) r.dte)?.dteEmisionId,
            dteAnulacionId: dteAnulacionId,
            dteFiscalEventId: dteFiscalEventId,
            codigoGeneracionEvento: r.codGenEvento,
            receptorPartyId: r.receptorPartyId,
            receptorNombre: receptorNombre,
            receptorEmail: receptorEmail,
            statusId: currentStatus == 'EntregaAcusada' ? currentStatus : 'EntregaPendiente',
            canal: sendEmail ? 'email' : 'manual',
            envelopeJson: JsonOutput.toJson(envelope),
            attachmentsJson: artifacts.attachmentsJson,
            eventPdfSize: artifacts.eventPdfSize,
            dtePdfSize: artifacts.dtePdfSize,
            intentos: intentos,
            fechaUltimoIntento: sendEmail ? ec.user.nowTimestamp : entrega.fechaUltimoIntento,
            errorMessage: null
    ])
    if (created) entrega.create() else entrega.update()

    boolean emailSent = false
    if (sendEmail) {
        try {
            String subject = "${r.titulo} - ${r.codGenEvento}"
            String bodyText = "Se prepara entrega del ${r.titulo}. Token de acuse: ${entrega.entregaToken}."
            Map email = sendEmailIfConfigured(ec, emailTemplateId, receptorEmail, subject, bodyText, (List<Map>) artifacts.attachments)
            entrega.emailMessageId = email.emailMessageId
            if (email.sent) {
                entrega.statusId = 'EntregaEnviada'
                entrega.fechaEnviada = ec.user.nowTimestamp
                emailSent = true
            } else {
                entrega.errorMessage = clean(email.error)?.take(500)
            }
            entrega.update()
            if (!email.sent && email.error) ec.message.addMessage(email.error as String, 'warning')
        } catch (Throwable t) {
            entrega.statusId = 'EntregaFallida'
            entrega.errorMessage = (clean(t.message) ?: t.class.name).take(500)
            entrega.update()
            ec.message.addMessage("No se pudo enviar el correo: ${t.message}", 'warning')
        }
    }

    return [delivered: true, created: created, emailSent: emailSent, dteEntregaId: entrega.dteEntregaId,
            entregaToken: entrega.entregaToken, statusId: entrega.statusId, receptorEmail: receptorEmail,
            envelopeJson: entrega.envelopeJson, attachmentsJson: entrega.attachmentsJson,
            eventPdfSize: entrega.eventPdfSize, dtePdfSize: entrega.dtePdfSize]
}

/**
 * render#DteEventPdf - Representación gráfica (PDF) de invalidación, retorno u operaciones especiales.
 */
Object renderDteEventPdf() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String eventoTipo = clean(ctx('eventoTipo'))
    Map r = resolveEvento(ec, eventoTipo, clean(ctx('dteAnulacionId')), clean(ctx('dteFiscalEventId')))
    if (r == null) { ec.message.addError('Evento origen no encontrado'); return [rendered: false] }

    String templateLocation = 'component://moqui-sv-localization/template/dte/DteEventPrintable.xsl-fo.ftl'
    Map context = ec.context
    List<String> keys = ['eventJson', 'eventTitle', 'selloEvento', 'motivoEvento']
    Map oldValues = keys.collectEntries { String k -> [(k): context.get(k)] }
    Map hadValues = keys.collectEntries { String k -> [(k): context.containsKey(k)] }
    try {
        context.put('eventJson', r.evento)
        context.put('eventTitle', r.titulo)
        context.put('selloEvento', r.sello)
        context.put('motivoEvento', r.motivo)
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [rendered: true, templateLocation: templateLocation, xslFoText: xslFo,
                pdfBytes: baos.toByteArray(), pdfSize: baos.size(), pageCount: pageCount, titulo: r.titulo]
    } finally {
        keys.each { String k -> if (hadValues[k]) context.put(k, oldValues[k]) else context.remove(k) }
    }
}

/**
 * record#DteAcuse - Registra el acuse de recibo del receptor por token o manualmente.
 */
Object recordDteAcuse() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEntregaId = clean(ctx('dteEntregaId'))
    String entregaToken = clean(ctx('entregaToken'))
    String acuseMetodo = clean(ctx('acuseMetodo')) ?: (entregaToken ? 'token' : 'manual')
    String acuseNota = clean(ctx('acuseNota'))

    EntityValue entrega = dteEntregaId ? one(ec, 'sv.localization.dte.DteEntrega', [dteEntregaId: dteEntregaId])
            : (entregaToken ? one(ec, 'sv.localization.dte.DteEntrega', [entregaToken: entregaToken]) : null)
    if (entrega == null) { ec.message.addError('Entrega no encontrada'); return [acknowledged: false] }
    if (entrega.statusId == 'EntregaAcusada') {
        return [acknowledged: true, alreadyAcknowledged: true, dteEntregaId: entrega.dteEntregaId]
    }
    entrega.setAll([statusId: 'EntregaAcusada', fechaAcuse: ec.user.nowTimestamp,
                    acuseMetodo: acuseMetodo, acuseNota: acuseNota, acuseByUserId: ec.user.userId])
    entrega.update()
    return [acknowledged: true, dteEntregaId: entrega.dteEntregaId, statusId: 'EntregaAcusada']
}
