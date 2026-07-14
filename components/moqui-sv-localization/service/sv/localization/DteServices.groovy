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

import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.sql.Timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.xml.transform.stream.StreamSource

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

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

BigDecimal money(Object raw) {
    BigDecimal bd = raw == null ? BigDecimal.ZERO : new BigDecimal(raw.toString())
    return bd.setScale(2, RoundingMode.HALF_UP)
}

BigDecimal qty(Object raw) {
    BigDecimal bd = raw == null ? BigDecimal.ONE : new BigDecimal(raw.toString())
    return bd == BigDecimal.ZERO ? BigDecimal.ONE : bd
}

int mhInt(Object raw, int fallback) {
    if (raw == null) return fallback
    String value = raw.toString().trim()
    if (!value) return fallback
    try {
        return Integer.parseInt(value)
    } catch (Throwable ignored) {
        return fallback
    }
}

String textOr(Object raw, String fallback) {
    String value = raw == null ? null : raw.toString().trim()
    return value ? value : fallback
}

Map dteDefinitions() {
    return [
            '01': [version: 2, schemaName: 'fe-f-v2.json', templateName: 'DteFc.xsl-fo.ftl', invoiceTypeEnumId: 'InvSvFc'],
            '03': [version: 4, schemaName: 'fe-ccf-v4.json', templateName: 'DteCcf.xsl-fo.ftl', invoiceTypeEnumId: 'InvSvCcf'],
            '04': [version: 4, schemaName: 'fe-nr-v4.json', templateName: null, invoiceTypeEnumId: 'InvSvNr'],
            '05': [version: 4, schemaName: 'fe-nc-v4.json', templateName: 'DteNc.xsl-fo.ftl', invoiceTypeEnumId: 'InvSvNc'],
            '06': [version: 4, schemaName: 'fe-nd-v4.json', templateName: 'DteNd.xsl-fo.ftl', invoiceTypeEnumId: 'InvSvNd'],
            '07': [version: 2, schemaName: 'fe-cr-v2.json', templateName: null, invoiceTypeEnumId: 'InvSvCr'],
            '08': [version: 2, schemaName: 'fe-cl-v2.json', templateName: null, invoiceTypeEnumId: 'InvSvCl'],
            '09': [version: 2, schemaName: 'fe-dcl-v2.json', templateName: null, invoiceTypeEnumId: 'InvSvDcl'],
            '11': [version: 3, schemaName: 'fe-fex-v3.json', templateName: null, invoiceTypeEnumId: 'InvSvFex'],
            '14': [version: 2, schemaName: 'fe-fse-v2.json', templateName: null, invoiceTypeEnumId: 'InvSvFse'],
            '15': [version: 2, schemaName: 'fe-cd-v2.json', templateName: null, invoiceTypeEnumId: 'InvSvCd'],
            '16': [version: 1, schemaName: 'fe-eges-v1.json', templateName: null, event: true],
            '17': [version: 1, schemaName: 'fe-eop-v1.json', templateName: null, event: true],
            '18': [version: 1, schemaName: 'fe-eret-v1.json', templateName: null, event: true]
    ]
}

Map legacySchemaByTypeVersion() {
    return [
            '01:1': 'fe-fc-v1.json',
            '03:3': 'fe-ccf-v3.json',
            '04:3': 'fe-nr-v3.json',
            '05:3': 'fe-nc-v3.json',
            '06:3': 'fe-nd-v3.json',
            '07:1': 'fe-cr-v1.json',
            '08:1': 'fe-cl-v1.json',
            '09:1': 'fe-dcl-v1.json',
            '11:1': 'fe-fex-v1.json',
            '14:1': 'fe-fse-v1.json',
            '15:1': 'fe-cd-v1.json'
    ]
}

String partyName(EntityValue partyDetail, String fallbackPartyId) {
    if (partyDetail == null) return fallbackPartyId
    // PartyDetail no expone groupName; usar organizationName cuando esté disponible.
    String orgName = partyDetail.getEntityDefinition().getFieldNames(true, true).contains('organizationName') ?
            (String) partyDetail.organizationName : null
    if (orgName) return orgName
    String personName = [partyDetail.firstName, partyDetail.middleName, partyDetail.lastName].findAll { it }.join(' ')
    return personName ?: fallbackPartyId
}

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

String partyIdent(ExecutionContext ec, String partyId, String typeEnumId) {
    EntityValue ident = one(ec, 'mantle.party.PartyIdentification',
            [partyId: partyId, partyIdTypeEnumId: typeEnumId])
    return ident?.idValue
}

EntityValue geo(ExecutionContext ec, Object geoId) {
    if (!geoId) return null
    return one(ec, 'moqui.basic.Geo', [geoId: geoId])
}

EntityValue firstPartyPostalAddress(ExecutionContext ec, String partyId) {
    List<EntityValue> pcmList = ec.entity.find('mantle.party.contact.PartyContactMech')
            .condition('partyId', partyId).condition('thruDate', null)
            .orderBy('-fromDate').list()
    for (EntityValue pcm in pcmList) {
        EntityValue pa = one(ec, 'mantle.party.contact.PostalAddress', [contactMechId: pcm.contactMechId])
        if (pa != null && (pa.countryGeoId == 'SLV' || pa.departamentoGeoId)) return pa
    }
    return null
}

String phoneForParty(ExecutionContext ec, String partyId, EntityValue postalAddress) {
    if (postalAddress?.telecomContactMechId) {
        EntityValue tn = one(ec, 'mantle.party.contact.TelecomNumber', [contactMechId: postalAddress.telecomContactMechId])
        String num = [tn?.areaCode, tn?.contactNumber].findAll { it }.join('')
        if (num) return num
    }
    List<EntityValue> pcmList = ec.entity.find('mantle.party.contact.PartyContactMech')
            .condition('partyId', partyId).condition('thruDate', null).list()
    for (EntityValue pcm in pcmList) {
        EntityValue cm = one(ec, 'mantle.party.contact.ContactMech', [contactMechId: pcm.contactMechId])
        if (cm?.contactMechTypeEnumId != 'CmtTelecomNumber') continue
        EntityValue tn = one(ec, 'mantle.party.contact.TelecomNumber', [contactMechId: pcm.contactMechId])
        String num = [tn?.areaCode, tn?.contactNumber].findAll { it }.join('')
        if (num) return num
    }
    return null
}

String emailForParty(ExecutionContext ec, String partyId, EntityValue postalAddress) {
    if (postalAddress?.emailContactMechId) {
        EntityValue cm = one(ec, 'mantle.party.contact.ContactMech', [contactMechId: postalAddress.emailContactMechId])
        if (cm?.infoString) return cm.infoString
    }
    List<EntityValue> pcmList = ec.entity.find('mantle.party.contact.PartyContactMech')
            .condition('partyId', partyId).condition('thruDate', null).list()
    for (EntityValue pcm in pcmList) {
        EntityValue cm = one(ec, 'mantle.party.contact.ContactMech', [contactMechId: pcm.contactMechId])
        if (cm?.contactMechTypeEnumId == 'CmtEmailAddress' && cm.infoString) return cm.infoString
    }
    return null
}

Map addressMap(ExecutionContext ec, EntityValue postalAddress, List<String> errors, String label, boolean required) {
    if (postalAddress == null) {
        if (required) errors.add("${label}: dirección fiscal SV requerida")
        return null
    }
    EntityValue dept = geo(ec, postalAddress.departamentoGeoId)
    EntityValue mun = geo(ec, postalAddress.municipioGeoId)
    EntityValue dist = geo(ec, postalAddress.distritoGeoId)
    String complemento = postalAddress.complemento ?: postalAddress.address1 ?: postalAddress.directions
    if (!dept?.geoCodeAlpha2) errors.add("${label}: departamento SV requerido")
    if (!mun?.geoCodeAlpha2) errors.add("${label}: municipio SV requerido")
    if (!dist?.geoCodeAlpha2) errors.add("${label}: distrito SV requerido")
    if (!complemento || complemento.size() < 5) errors.add("${label}: complemento de dirección requerido")
    return [departamento: dept?.geoCodeAlpha2, municipio: mun?.geoCodeAlpha2,
            distrito: dist?.geoCodeAlpha2, complemento: complemento]
}

Map productFiscalDefaults(ExecutionContext ec, EntityValue item) {
    EntityValue product = item.productId ? one(ec, 'mantle.product.Product', [productId: item.productId]) : null
    return [
            tipoItem: mhInt(product?.svTipoItemCode, 1),
            unidadMedida: mhInt(product?.svUnidadMedidaCode, 59),
            tributoCode: textOr(item.tributoCode ?: product?.svTributoCode, '20'),
            taxTreatmentEnumId: product?.svTaxTreatmentEnumId ?: 'SvTaxGravada',
            retencionIvaCode: product?.svRetencionIvaMhCode,
            ivaRetencionAplica: product?.svIvaRetencionAplica == 'Y',
            ivaPercepcionAplica: product?.svIvaPercepcionAplica == 'Y'
    ]
}

Map lineTaxBuckets(BigDecimal line, String treatmentEnumId) {
    BigDecimal zero = BigDecimal.ZERO.setScale(2)
    Map buckets = [ventaNoSuj: zero, ventaExenta: zero, ventaGravada: zero, noGravado: zero]
    switch (treatmentEnumId) {
        case 'SvTaxExenta':
            buckets.ventaExenta = line
            break
        case 'SvTaxNoSujeta':
            buckets.ventaNoSuj = line
            break
        case 'SvTaxNoGravada':
            buckets.noGravado = line
            break
        case 'SvTaxGravada':
        default:
            buckets.ventaGravada = line
            break
    }
    return buckets
}

Map issuerInfo(ExecutionContext ec, String partyId) {
    List<String> errors = []
    EntityValue detail = one(ec, 'mantle.party.PartyDetail', [partyId: partyId])
    EntityValue profile = one(ec, 'sv.localization.party.SvFiscalProfile', [partyId: partyId])
    EntityValue activity = profile?.actividadEconomicaId ?
            one(ec, 'sv.localization.party.SvEconomicActivity', [actividadEconomicaId: profile.actividadEconomicaId]) : null
    EntityValue address = firstPartyPostalAddress(ec, partyId)
    Map dir = addressMap(ec, address, errors, 'Emisor', true)
    String phone = phoneForParty(ec, partyId, address)
    String email = emailForParty(ec, partyId, address)
    String nit = digitsOnly(partyIdent(ec, partyId, 'PitNit'))
    String nrc = digitsOnly(partyIdent(ec, partyId, 'PitNrc') ?: profile?.nrc)
    String nombre = partyName(detail, partyId)
    String nombreComercial = profile?.nombreComercial ?: detail?.nombreComercial ?: nombre

    if (!nit || nit.size() != 14) errors.add('Emisor: NIT de 14 dígitos requerido')
    if (!nrc || nrc.size() < 2 || nrc.size() > 8) errors.add('Emisor: NRC requerido')
    if (!activity?.mhCode) errors.add('Emisor: actividad económica CAT-019 requerida')
    if (!nombre) errors.add('Emisor: nombre requerido')
    if (!nombreComercial || nombreComercial.size() < 5) errors.add('Emisor: nombre comercial requerido')
    if (!phone || phone.size() < 8) errors.add('Emisor: teléfono requerido')
    if (!email || !email.contains('@')) errors.add('Emisor: correo requerido')

    return [errors: errors, data: [
            nit: nit, nrc: nrc, nombre: nombre, codActividad: activity?.mhCode,
            descActividad: activity?.description, nombreComercial: nombreComercial,
            tipoEstablecimiento: profile?.tipoEstablecimientoMhCode ?: '02',
            modeloFacturacion: profile?.modeloFacturacionDefault,
            direccion: dir, telefono: phone, correo: email,
            codEstableMH: profile?.codEstableMhCode, codEstable: profile?.codEstableCode ?: '0001',
            codPuntoVentaMH: profile?.codPuntoVentaMhCode, codPuntoVenta: profile?.codPuntoVentaCode ?: '0001'
    ]]
}

Map receiverInfo(ExecutionContext ec, String partyId, String tipoDteCode = '01') {
    if (!partyId) return null
    EntityValue detail = one(ec, 'mantle.party.PartyDetail', [partyId: partyId])
    if (detail == null) return null
    String dui = digitsOnly(partyIdent(ec, partyId, 'PitDui'))
    String nit = digitsOnly(partyIdent(ec, partyId, 'PitNit'))
    String docType = nit ? '36' : (dui ? '13' : null)
    String docNum = nit ?: dui
    if (!docNum) return null
    EntityValue profile = one(ec, 'sv.localization.party.SvFiscalProfile', [partyId: partyId])
    EntityValue activity = profile?.actividadEconomicaId ?
            one(ec, 'sv.localization.party.SvEconomicActivity', [actividadEconomicaId: profile.actividadEconomicaId]) : null
    EntityValue address = firstPartyPostalAddress(ec, partyId)
    List<String> ignored = []
    // nombreComercial solo si efectivamente hay nombre comercial registrado;
    // FC v2 rechaza nombreComercial en receptor consumidor final.
    String detailNombreComercial = detail.getEntityDefinition().getFieldNames(true, true)
            .contains('nombreComercial') ? (String) detail.nombreComercial : null
    String nombreComercialValue = profile?.nombreComercial ?: detailNombreComercial
    // Para FC consumidor final el schema rechaza la KEY nombreComercial
    // (no solo el valor). Solo incluirla si hay valor efectivo.
    Map base = [
            tipoDocumento: docType,
            numDocumento: docNum,
            nrc: digitsOnly(partyIdent(ec, partyId, 'PitNrc') ?: profile?.nrc),
            nombre: partyName(detail, partyId),
            codActividad: activity?.mhCode,
            descActividad: activity?.description,
            direccion: address ? addressMap(ec, address, ignored, 'Receptor', false) : null,
            telefono: phoneForParty(ec, partyId, address),
            correo: emailForParty(ec, partyId, address)
    ]
    if (nombreComercialValue) base.nombreComercial = nombreComercialValue
    // NC 05 y ND 06 (schema fe-nc/nd) requieren nombreComercial en receptor.
    // Si el receptor no lo tiene registrado, fallback al nombre fiscal.
    if (tipoDteCode in ['05', '06'] && !base.nombreComercial) {
        base.nombreComercial = base.nombre
    }
    if (tipoDteCode == '14') {
        return [
                tipoDocumento: dui ? '13' : docType,
                numDocumento: dui ?: docNum,
                nombre: partyName(detail, partyId),
                codActividad: activity?.mhCode,
                descActividad: activity?.description,
                direccion: address ? addressMap(ec, address, ignored, 'Receptor', false) : null,
                telefono: phoneForParty(ec, partyId, address),
                correo: emailForParty(ec, partyId, address)
        ]
    }
    if (tipoDteCode in ['01', '05', '06']) return base
    return [
            nit: nit,
            nrc: base.nrc,
            nombre: base.nombre,
            codActividad: base.codActividad,
            descActividad: base.descActividad,
            nombreComercial: base.nombreComercial,
            direccion: base.direccion,
            telefono: base.telefono,
            correo: base.correo
    ]
}

Map buildItemsAndSummary(ExecutionContext ec, String invoiceId, String tipoDteCode) {
    boolean isFc = tipoDteCode == '01'
    List<String> errors = []
    List<Map> cuerpo = []
    BigDecimal totalNoSuj = BigDecimal.ZERO
    BigDecimal totalExenta = BigDecimal.ZERO
    BigDecimal totalGravada = BigDecimal.ZERO
    BigDecimal totalNoGravado = BigDecimal.ZERO
    int idx = 1
    List<EntityValue> items = ec.entity.find('mantle.account.invoice.InvoiceItem')
            .condition('invoiceId', invoiceId).orderBy('invoiceItemSeqId').list()
    for (EntityValue item in items) {
        if (item.isAdjustment == 'Y') continue
        if (item.itemTypeEnumId in ['ItemSalesTax', 'ItemUseTax']) continue
        BigDecimal amount = money(item.amount)
        BigDecimal quantity = qty(item.quantity)
        BigDecimal line = money(amount.multiply(quantity))
        if (line <= BigDecimal.ZERO) continue
        Map productFiscal = productFiscalDefaults(ec, item)
        String tributoCode = productFiscal.tributoCode as String
        Map buckets = lineTaxBuckets(line, productFiscal.taxTreatmentEnumId as String)
        totalNoSuj = totalNoSuj.add(buckets.ventaNoSuj as BigDecimal)
        totalExenta = totalExenta.add(buckets.ventaExenta as BigDecimal)
        totalGravada = totalGravada.add(buckets.ventaGravada as BigDecimal)
        totalNoGravado = totalNoGravado.add(buckets.noGravado as BigDecimal)
        boolean taxableLine = (buckets.ventaGravada as BigDecimal).compareTo(BigDecimal.ZERO) > 0
        Map row = [
                numItem: idx++,
                tipoItem: productFiscal.tipoItem,
                numeroDocumento: item.numeroDocumentoRelacionado,
                codigo: item.productId ?: item.invoiceItemSeqId,
                codTributo: null,
                descripcion: item.description ?: item.productId ?: "Item ${item.invoiceItemSeqId}",
                cantidad: quantity,
                uniMedida: productFiscal.unidadMedida,
                precioUni: amount,
                montoDescu: BigDecimal.ZERO.setScale(2),
                ventaNoSuj: buckets.ventaNoSuj,
                ventaExenta: buckets.ventaExenta,
                ventaGravada: buckets.ventaGravada,
                tributos: (isFc || !taxableLine) ? null : [tributoCode]]
        if (tipoDteCode in ['01', '03']) {
            row.psv = BigDecimal.ZERO.setScale(2)
            row.noGravado = buckets.noGravado
        }
        if (tipoDteCode in ['05', '06']) {
            // NC/ND v4: `totalIva`, `ivaPerci`, `ivaRete` por línea representan
            // percepciones/retenciones ESPECÍFICAS por ítem, NO el IVA generado
            // por la operación normal. El IVA 13% va en el array `tributos`
            // (codigoTributo "20") y se calcula sobre `ventaGravada`. Si no hay
            // percepción/retención específica del ítem, los 3 campos son 0.
            row.noGravado = item.noGravado != null ? money(item.noGravado) : buckets.noGravado
            row.ivaPerci = money(item.ivaPerci)
            row.ivaRete = money(item.ivaRete)
            row.totalIva = item.totalIva != null ? money(item.totalIva) : BigDecimal.ZERO.setScale(2)
        }
        if (isFc) {
            // FC (01): IVA está incluido en el precio, ivaItem se desglosa por línea
            row.ivaItem = taxableLine ?
                    money((buckets.ventaGravada as BigDecimal).multiply(new BigDecimal('13')).divide(new BigDecimal('113'), 8, RoundingMode.HALF_UP)) :
                    BigDecimal.ZERO.setScale(2)
        }
        cuerpo.add(row)
    }
    if (cuerpo.isEmpty()) errors.add("DTE ${tipoDteCode} sin líneas fiscales válidas")

    totalNoSuj = money(totalNoSuj)
    totalExenta = money(totalExenta)
    totalGravada = money(totalGravada)
    totalNoGravado = money(totalNoGravado)
    BigDecimal subTotalVentas = money(totalNoSuj.add(totalExenta).add(totalGravada))
    BigDecimal ivaCalc
    if (isFc) {
        // FC: IVA incluido en precio (factura consumidor final); se desglosa por línea
        ivaCalc = cuerpo.collect { it.ivaItem as BigDecimal }.inject(BigDecimal.ZERO) { a, b -> a.add(b) }
    } else {
        // CCF / NC / ND: IVA explícito 13% sobre el subtotal. Para NC/ND v4 este
        // ivaCalc va en el array `tributos` con codigo "20"; el campo
        // `resumen.totalIva` y `item.totalIva` son CERO (no son el IVA de la
        // operación sino percepción/retención específica).
        ivaCalc = money(totalGravada.multiply(new BigDecimal('0.13')))
    }

    BigDecimal totalOperacion = isFc ?
            money(subTotalVentas.add(totalNoGravado)) :
            money(subTotalVentas.add(ivaCalc).add(totalNoGravado))
    List<Map> ivaTributos = ivaCalc.compareTo(BigDecimal.ZERO) > 0 ?
            [[codigo: '20', descripcion: 'Impuesto al Valor Agregado 13%', valor: ivaCalc]] : null

    Map resumen = [
            totalNoSuj: totalNoSuj,
            totalExenta: totalExenta,
            totalGravada: totalGravada,
            subTotalVentas: subTotalVentas,
            descuNoSuj: BigDecimal.ZERO.setScale(2),
            descuExenta: BigDecimal.ZERO.setScale(2),
            descuGravada: BigDecimal.ZERO.setScale(2),
            totalDescu: BigDecimal.ZERO.setScale(2),
            tributos: isFc ? null : ivaTributos,
            subTotal: subTotalVentas,
            ivaRete: BigDecimal.ZERO.setScale(2),
            montoTotalOperacion: totalOperacion,
            totalLetras: "${totalOperacion.toPlainString()} DOLARES",
            condicionOperacion: 1,
            observaciones: null
    ]

    if (tipoDteCode == '01') {
        resumen.porcentajeDescuento = BigDecimal.ZERO.setScale(2)
        resumen.totalNoGravado = totalNoGravado
        resumen.totalPagar = totalOperacion
        resumen.totalIva = ivaCalc
        resumen.saldoFavor = BigDecimal.ZERO.setScale(2)
        resumen.pagos = [[codigo: '01', montoPago: totalOperacion, referencia: null, plazo: null, periodo: null]]
        resumen.numPagoElectronico = null
    } else if (tipoDteCode == '03') {
        resumen.porcentajeDescuento = BigDecimal.ZERO.setScale(2)
        resumen.ivaPerci = BigDecimal.ZERO.setScale(2)
        resumen.totalNoGravado = totalNoGravado
        resumen.totalPagar = totalOperacion
        resumen.saldoFavor = BigDecimal.ZERO.setScale(2)
        resumen.pagos = [[codigo: '01', montoPago: totalOperacion, referencia: null, plazo: null, periodo: null]]
        resumen.numPagoElectronico = null
    } else if (tipoDteCode == '05') {
        resumen.remove('subTotal')
        resumen.remove('descuNoSuj')
        resumen.remove('descuExenta')
        resumen.remove('descuGravada')
        resumen.remove('porcentajeDescuento')
        resumen.remove('saldoFavor')
        resumen.remove('pagos')
        resumen.remove('numPagoElectronico')
        resumen.ivaPerci = BigDecimal.ZERO.setScale(2)
        // NC v4: `totalIva` es el IVA percibido/retenido NETO, NO el IVA generado
        // por la operación (ese va en `tributos`). MH valida `totalIva` contra el
        // CCF referenciado, no contra el cálculo aritmético de la NC. Si no hay
        // percepción/retención específica, debe ser 0 (no `totalGravada * 0.13`).
        resumen.totalIva = BigDecimal.ZERO.setScale(2)
        resumen.totalNoGravado = totalNoGravado
        resumen.totalPagar = totalOperacion
        resumen.codigoRetencionMH = null
    } else if (tipoDteCode == '06') {
        resumen.remove('subTotal')
        resumen.remove('descuNoSuj')
        resumen.remove('descuExenta')
        resumen.remove('descuGravada')
        resumen.remove('porcentajeDescuento')
        resumen.remove('saldoFavor')
        resumen.remove('pagos')
        resumen.ivaPerci = BigDecimal.ZERO.setScale(2)
        // ND v4: misma regla que NC — totalIva es IVA percibido/retenido neto,
        // no el IVA generado (ese va en `tributos`). El schema publicado en
        // svfe-json-schemas.zip trae una clave vacía para ND, pero apitest MH
        // 2026-05-29 rechaza esa clave y exige `tributos`.
        resumen.totalIva = BigDecimal.ZERO.setScale(2)
        resumen.totalNoGravado = totalNoGravado
        resumen.totalPagar = totalOperacion
        resumen.numPagoElectronico = null
        resumen.codigoRetencionMH = null
    }
    if (tipoDteCode == '14') {
        // FSE schema resumen: totalCompra, descu, totalDescu, subTotal, reteRenta, totalPagar,
        // totalLetras, condicionOperacion, pagos, observaciones
        BigDecimal totalCompra = money(subTotalVentas)
        BigDecimal reteRenta = money(totalCompra.multiply(new BigDecimal('0.10')))
        BigDecimal totalPagarFse = money(totalCompra.subtract(reteRenta))
        resumen = [
                totalCompra: totalCompra,
                descu: BigDecimal.ZERO.setScale(2),
                totalDescu: BigDecimal.ZERO.setScale(2),
                subTotal: totalCompra,
                reteRenta: reteRenta,
                totalPagar: totalPagarFse,
                totalLetras: "${totalPagarFse.toPlainString()} DOLARES",
                condicionOperacion: 1,
                pagos: [[codigo: '01', montoPago: totalPagarFse, referencia: null, plazo: null, periodo: null]],
                observaciones: null
        ]
        // Reduce FSE line items to schema fields only: numItem, tipoItem, cantidad, codigo,
        // uniMedida, descripcion, precioUni, montoDescu, compra
        cuerpo = cuerpo.collect { Map row ->
            BigDecimal lineTotal = money(((row.precioUni ?: 0.0) as BigDecimal).multiply((row.cantidad ?: 0.0) as BigDecimal))
            return [
                    numItem: row.numItem,
                    tipoItem: row.tipoItem,
                    cantidad: row.cantidad,
                    codigo: row.codigo,
                    uniMedida: row.uniMedida,
                    descripcion: row.descripcion,
                    precioUni: row.precioUni,
                    montoDescu: row.montoDescu,
                    compra: lineTotal
            ]
        }
    }
    return [errors: errors, cuerpo: cuerpo, resumen: resumen, totalIva: ivaCalc]
}

String resolveTipoDte(EntityValue invoice) {
    if (invoice.tipoDteCode) return invoice.tipoDteCode
    String typeEnum = invoice.invoiceTypeEnumId as String
    switch (typeEnum) {
        case 'InvSvCcf': return '03'
        case 'InvSvNr': return '04'
        case 'InvSvNc': return '05'
        case 'InvSvNd': return '06'
        case 'InvSvCr': return '07'
        case 'InvSvCl': return '08'
        case 'InvSvDcl': return '09'
        case 'InvSvFex': return '11'
        case 'InvSvFse': return '14'
        case 'InvSvCd': return '15'
        case 'InvSvFc':
        default: return '01'
    }
}

int dteVersionFor(String tipoDteCode) {
    Map defn = (Map) dteDefinitions()[tipoDteCode]
    if (defn == null || defn.event) throw new IllegalArgumentException("Tipo DTE ${tipoDteCode} no soportado como factura")
    return defn.version as int
}

String nextNumeroControl(ExecutionContext ec, String tipoDteCode) {
    long count = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('tipoDteCode', tipoDteCode).count() + 1L
    String establishment = sysProp('sv.dte.numero_control.establishment', 'M001P001')
    establishment = establishment.replaceAll('[^A-Za-z0-9]', '').toUpperCase(Locale.ROOT).padRight(8, '0').substring(0, 8)
    return "DTE-${tipoDteCode}-${establishment}-${String.format('%015d', count)}"
}

List<Map> buildDocumentoRelacionado(ExecutionContext ec, EntityValue invoice, List<String> errors) {
    String refId = invoice.dteReferenceEmisionId
    if (!refId) {
        errors.add('NC/ND requieren dteReferenceEmisionId apuntando al DTE original')
        return null
    }
    EntityValue ref = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: refId])
    if (ref == null) {
        errors.add("DTE referenciado ${refId} no existe")
        return null
    }
    if (ref.tipoDteCode != '03') {
        errors.add("NC/ND solo pueden referenciar CCF tipo 03; DTE ${refId} es tipo ${ref.tipoDteCode}")
        return null
    }
    if (!(ref.statusId in ['DteAccepted', 'DteMockAccepted'])) {
        errors.add("DTE referenciado ${refId} debe estar aceptado; estado actual=${ref.statusId}")
        return null
    }
    if (!ref.codigoGeneracion) {
        errors.add("DTE referenciado ${refId} no tiene codigoGeneracion")
        return null
    }
    java.sql.Date refDate = null
    if (ref.fechaGeneracion) {
        refDate = new java.sql.Date(((Timestamp) ref.fechaGeneracion).time)
    }
    if (refDate == null) {
        errors.add("DTE referenciado ${refId} no tiene fechaGeneracion")
        return null
    }
    return [[
            tipoDocumento: '03',
            tipoGeneracion: 2, // 1=físico, 2=electrónico
            numeroDocumento: ref.codigoGeneracion,
            fechaEmision: refDate?.toString()
    ]]
}

void addEvent(ExecutionContext ec, String dteEmisionId, String eventTypeEnumId, String comments, Object payload = null, Integer httpStatus = null) {
    Timestamp eventDate = new Timestamp(System.currentTimeMillis())
    int attempts = 0
    while (one(ec, 'sv.localization.dte.DteEmisionEvent', [dteEmisionId: dteEmisionId, eventDate: eventDate]) != null) {
        eventDate = new Timestamp(eventDate.time + 1L)
        if (++attempts > 1000) throw new IllegalStateException("No se pudo asignar eventDate único para DTE ${dteEmisionId}")
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

String schemaNameFor(String tipoDteCode, Object version = null) {
    if (version != null) {
        String legacy = (String) legacySchemaByTypeVersion()["${tipoDteCode}:${version}"]
        if (legacy) return legacy
    }
    Map defn = (Map) dteDefinitions()[tipoDteCode]
    if (defn == null) throw new IllegalArgumentException("Tipo DTE ${tipoDteCode} no soportado")
    return defn.schemaName as String
}

Map loadDteJson(ExecutionContext ec, EntityValue dte) {
    if (!dte?.jsonOriginal) return null
    return (Map) new JsonSlurper().parseText((String) dte.jsonOriginal)
}

String templateNameFor(String tipoDteCode) {
    Map defn = (Map) dteDefinitions()[tipoDteCode]
    if (!defn?.templateName) return 'DteGeneric.xsl-fo.ftl'
    return defn.templateName as String
}

/**
 * Genera el QR de verificación pública MH para un DTE como data: URI base64.
 *
 * URL formato oficial MH configurable con `sv.dte.verification.public.url`:
 *   {baseUrl}?ambiente={amb}&codGen={UUID}&fechaEmi={YYYY-MM-DD}
 *
 * El QR se genera con barcode4j (ya en classpath) y se devuelve como
 * `data:image/png;base64,…` para embeber directo en el XSL-FO sin escribir
 * a disco.
 */
Map buildVerificationQr(Map dteJson) {
    Map id = (Map) (dteJson?.identificacion ?: [:])
    String ambiente = id.ambiente?.toString() ?: '00'
    String codGen = id.codigoGeneracion?.toString() ?: ''
    String fechaEmi = id.fecEmi?.toString() ?: ''
    if (!codGen) return [url: null, dataUri: null]
    String baseUrl = sysProp('sv.dte.verification.public.url', 'https://admin.factura.gob.sv/consultaPublica')
    String separator = baseUrl.contains('?') ? '&' : '?'
    String url = "${baseUrl}${separator}ambiente=${urlParam(ambiente)}&codGen=${urlParam(codGen)}&fechaEmi=${urlParam(fechaEmi)}"
    try {
        Class writerClass = Class.forName('com.google.zxing.qrcode.QRCodeWriter')
        Class barcodeFormatClass = Class.forName('com.google.zxing.BarcodeFormat')
        Object qrFormat = barcodeFormatClass.getField('QR_CODE').get(null)
        Object matrix = writerClass.getDeclaredConstructor().newInstance()
                .encode(url, qrFormat, 220, 220)
        int width = (Integer) matrix.getClass().getMethod('getWidth').invoke(matrix)
        int height = (Integer) matrix.getClass().getMethod('getHeight').invoke(matrix)
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean black = (Boolean) matrix.getClass().getMethod('get', int.class, int.class).invoke(matrix, x, y)
                img.setRGB(x, y, black ? 0x000000 : 0xFFFFFF)
            }
        }
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, 'png', pngOut)
        String base64 = Base64.getEncoder().encodeToString(pngOut.toByteArray())
        return [url: url, dataUri: "data:image/png;base64,${base64}".toString()]
    } catch (Throwable zxingFailure) {
        // Fallback defensivo para runtimes sin ZXing; Barcode4J no siempre trae QR.
    }
    try {
        Class qrBeanClass = Class.forName('org.krysalis.barcode4j.impl.qr.QRCodeBean')
        Object qr = qrBeanClass.getDeclaredConstructor().newInstance()
        qrBeanClass.getMethod('setModuleWidth', double.class).invoke(qr, 0.6d)
        try {
            Class ecClass = Class.forName('org.krysalis.barcode4j.impl.qr.QRConstants')
            Object levelL = ecClass.getField('ERROR_CORRECTION_LEVEL_L').get(null)
            qrBeanClass.getMethod('setErrorCorrectionLevel', levelL.getClass()).invoke(qr, levelL)
        } catch (Throwable ignored) { /* default level L */ }
        Class canvasClass = Class.forName('org.krysalis.barcode4j.output.bitmap.BitmapCanvasProvider')
        Object canvas = canvasClass.getConstructor(int.class, int.class, boolean.class, int.class)
                .newInstance(220, java.awt.image.BufferedImage.TYPE_BYTE_BINARY, false, 0)
        qrBeanClass.getMethod('generateBarcode', Class.forName('org.krysalis.barcode4j.output.CanvasProvider'), String.class)
                .invoke(qr, canvas, url)
        canvasClass.getMethod('finish').invoke(canvas)
        java.awt.image.BufferedImage img = (java.awt.image.BufferedImage) canvasClass.getMethod('getBufferedImage').invoke(canvas)
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, 'png', pngOut)
        String base64 = Base64.getEncoder().encodeToString(pngOut.toByteArray())
        return [url: url, dataUri: "data:image/png;base64,${base64}".toString()]
    } catch (Throwable t) {
        // Si barcode4j no está disponible o falla, devolver solo la URL
        return [url: url, dataUri: null, error: t.message]
    }
}

String urlParam(Object raw) {
    return URLEncoder.encode(raw == null ? '' : raw.toString(), 'UTF-8')
}

Map buildNumeroControlBarcode(Map dteJson) {
    Map id = (Map) (dteJson?.identificacion ?: [:])
    String numeroControl = id.numeroControl?.toString()
    if (!numeroControl) return [dataUri: null]
    try {
        Class beanClass = Class.forName('org.krysalis.barcode4j.impl.code128.Code128Bean')
        Object bean = beanClass.getDeclaredConstructor().newInstance()
        beanClass.getMethod('setModuleWidth', double.class).invoke(bean, 0.28d)
        beanClass.getMethod('setHeight', double.class).invoke(bean, 10.0d)
        beanClass.getMethod('doQuietZone', boolean.class).invoke(bean, true)
        Class canvasClass = Class.forName('org.krysalis.barcode4j.output.bitmap.BitmapCanvasProvider')
        Object canvas = canvasClass.getConstructor(int.class, int.class, boolean.class, int.class)
                .newInstance(220, java.awt.image.BufferedImage.TYPE_BYTE_BINARY, false, 0)
        beanClass.getMethod('generateBarcode', Class.forName('org.krysalis.barcode4j.output.CanvasProvider'), String.class)
                .invoke(bean, canvas, numeroControl)
        canvasClass.getMethod('finish').invoke(canvas)
        java.awt.image.BufferedImage img = (java.awt.image.BufferedImage) canvasClass.getMethod('getBufferedImage').invoke(canvas)
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, 'png', pngOut)
        String base64 = Base64.getEncoder().encodeToString(pngOut.toByteArray())
        return [dataUri: "data:image/png;base64,${base64}".toString()]
    } catch (Throwable t) {
        return [dataUri: null, error: t.message]
    }
}

String writeDataUriPngToTempFileUrl(String dataUri, String filename) {
    if (!dataUri?.startsWith('data:image/png;base64,')) return null
    try {
        String b64 = dataUri.substring('data:image/png;base64,'.length())
        byte[] pngBytes = Base64.getDecoder().decode(b64)
        String tmpDir = sysProp('sv.dte.qr.tmp.dir',
                new File(System.getProperty('java.io.tmpdir'), 'moqui-sv-dte-qr').absolutePath)
        File dir = new File(tmpDir)
        dir.mkdirs()
        File f = new File(dir, filename.replaceAll('[^A-Za-z0-9_.-]', '_'))
        f.bytes = pngBytes
        return "file://${f.absolutePath}"
    } catch (Throwable ignored) {
        return null
    }
}

/** Devuelve la URL del logo del emisor para embeber en el PDF, o null si no
 *  está configurado. Resuelve `SvFiscalProfile.logoContentLocation` a una
 *  ruta `file://…` absoluta o data URI. */
String resolveEmisorLogoUrl(ExecutionContext ec, String emisorNit) {
    if (!emisorNit) return null
    String nitDigits = digitsOnly(emisorNit)
    // Buscar party con ese NIT en PartyIdentification
    EntityValue ident = ec.entity.find('mantle.party.PartyIdentification')
            .condition('partyIdTypeEnumId', 'PitNit').list().find {
        digitsOnly((String) it.idValue) == nitDigits
    }
    if (ident == null) return null
    EntityValue fp = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', ident.partyId).one()
    String logoLoc = (String) fp?.logoContentLocation
    if (!logoLoc) return null
    try {
        // Resolver a una URL absoluta accesible por FOP
        org.moqui.resource.ResourceReference rr = ec.resource.getLocationReference(logoLoc)
        if (rr?.exists && rr.getUrl() != null) return rr.getUrl().toExternalForm()
    } catch (Throwable ignored) { }
    return logoLoc.startsWith('http') || logoLoc.startsWith('file:') ? logoLoc : null
}

/**
 * Devuelve el contexto que necesita el template FOP DtePrintableBody:
 *   dteEmision, dteJson, verificationQr (data URI), verificationQrUrl,
 *   emisorLogoUrl y dteEstadoLeyenda opcional.
 *
 * Reutilizable desde `render#DtePdf` (genera bytes) y desde la screen
 * standalone PrintDte.xml (que usa &lt;render-mode&gt; con el template).
 */
Object getDtePrintContext() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    if (dte == null) {
        ec.message.addError("DTE ${dteEmisionId} no existe")
        return [:]
    }
    Map dteJson = loadDteJson(ec, dte)
    if (dteJson == null) {
        ec.message.addError("DTE ${dteEmisionId} no tiene jsonOriginal")
        return [dteEmision: dte]
    }
    Map qr = buildVerificationQr(dteJson)
    Map barcode = buildNumeroControlBarcode(dteJson)
    String emisorLogoUrl = resolveEmisorLogoUrl(ec, (String) ((Map) dteJson.emisor)?.nit)
    // Escribir imagenes a disco temporal para que FOP las cargue por file://
    // de forma robusta, sin dejar binarios generados dentro del componente.
    String qrFileUrl = writeDataUriPngToTempFileUrl((String) qr.dataUri, "${dte.codigoGeneracion}-qr.png")
    String barcodeFileUrl = writeDataUriPngToTempFileUrl((String) barcode.dataUri, "${dte.codigoGeneracion}-barcode.png")
    return [
            dteEmision        : dte,
            dteJson           : dteJson,
            verificationQr    : qrFileUrl ?: qr.dataUri,
            verificationQrUrl : qr.url,
            verificationBarcode: barcodeFileUrl ?: barcode.dataUri,
            emisorLogoUrl     : emisorLogoUrl,
            dteEstadoLeyenda  : (ctx('estadoLeyenda') ?: ctx('dteEstadoLeyenda')) as String
    ]
}

Object renderDtePdf() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    Map prep = (Map) ec.service.sync()
            .name('sv.localization.DteServices.get#DtePrintContext')
            .parameters([dteEmisionId: dteEmisionId, estadoLeyenda: ctx('estadoLeyenda')]).call()
    EntityValue dte = (EntityValue) prep?.dteEmision
    Map dteJson = (Map) prep?.dteJson
    if (dte == null) return [rendered: false, message: 'DTE no existe']
    if (dteJson == null) return [rendered: false, message: 'DTE sin jsonOriginal']
    Map qr = [dataUri: prep.verificationQr, url: prep.verificationQrUrl]
    String emisorLogoUrl = (String) prep.emisorLogoUrl

    String templateLocation = "component://moqui-sv-localization/template/dte/${templateNameFor((String) dte.tipoDteCode)}"
    Map context = ec.context
    List<String> keys = ['dteEmision', 'dteJson', 'verificationQr', 'verificationQrUrl', 'verificationBarcode', 'emisorLogoUrl', 'dteEstadoLeyenda']
    Map oldValues = keys.collectEntries { String k -> [(k): context.get(k)] }
    Map hadValues = keys.collectEntries { String k -> [(k): context.containsKey(k)] }
    try {
        context.put('dteEmision', dte)
        context.put('dteJson', dteJson)
        context.put('verificationQr', qr.dataUri)
        context.put('verificationQrUrl', qr.url)
        context.put('verificationBarcode', prep.verificationBarcode)
        context.put('emisorLogoUrl', emisorLogoUrl)
        context.put('dteEstadoLeyenda', prep.dteEstadoLeyenda)
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [rendered: true, templateLocation: templateLocation, xslFoText: xslFo,
                pdfBytes: baos.toByteArray(), pdfSize: baos.size(), pageCount: pageCount,
                verificationQrUrl: qr.url, hasLogo: emisorLogoUrl != null]
    } finally {
        keys.each { String k ->
            if (hadValues[k]) context.put(k, oldValues[k]) else context.remove(k)
        }
    }
}

Object generateDteJson() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String invoiceId = (String) ctx('invoiceId')
    EntityValue invoice = one(ec, 'mantle.account.invoice.Invoice', [invoiceId: invoiceId])
    if (invoice == null) {
        ec.message.addError("Factura ${invoiceId} no existe")
        return [:]
    }

    String tipoDteCode = resolveTipoDte(invoice)
    if (!(tipoDteCode in ['01', '03', '05', '06', '14'])) {
        ec.message.addError("DTE ${tipoDteCode}: generación automática desde Invoice todavía requiere datos fiscales especializados; use JSON explícito y validate/sign/transmit o el servicio de evento fiscal cuando aplique")
        return [:]
    }
    int version = dteVersionFor(tipoDteCode)
    String schemaName = schemaNameFor(tipoDteCode)
    boolean receptorRequired = tipoDteCode in ['03', '05', '06', '14']

    Map issuer = issuerInfo(ec, (String) invoice.fromPartyId)
    Map items = buildItemsAndSummary(ec, invoiceId, tipoDteCode)
    Map receptor = receiverInfo(ec, (String) invoice.toPartyId, tipoDteCode)
    List<String> errors = []
    errors.addAll((List<String>) issuer.errors)
    errors.addAll((List<String>) items.errors)
    if (receptorRequired) {
        if (receptor == null) errors.add("DTE ${tipoDteCode}: receptor obligatorio (NIT/DUI requeridos)")
        else {
            if (tipoDteCode == '03' && !receptor.nrc) errors.add('CCF (03): NRC del receptor obligatorio')
            if (!receptor.codActividad) errors.add("DTE ${tipoDteCode}: actividad económica del receptor obligatoria")
        }
    }
    List<Map> docRelacionado = null
    if (tipoDteCode == '05' || tipoDteCode == '06') {
        docRelacionado = buildDocumentoRelacionado(ec, invoice, errors)
    }
    if (!errors.isEmpty()) {
        ec.message.addError(errors.join('; '))
        return [:]
    }

    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [invoiceId: invoiceId])
    if (dte == null) {
        dte = ec.entity.makeValue('sv.localization.dte.DteEmision').setAll([
                dteEmisionId: ec.entity.sequencedIdPrimary('sv.localization.dte.DteEmision', null, null),
                invoiceId: invoiceId,
                codigoGeneracion: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                numeroControl: nextNumeroControl(ec, tipoDteCode)
        ])
    }

    // Modelo de facturación (MH CAT-001): 1=previo, 2=diferido. Override por parámetro,
    // si no el default del emisor (SvFiscalProfile.modeloFacturacionDefault), si no 1.
    Integer tipoModeloParam = ctx('tipoModelo') == null || ctx('tipoModelo').toString().isEmpty()
            ? null : (ctx('tipoModelo').toString() as Integer)
    Integer tipoModelo = tipoModeloParam ?: ((Integer) ((Map) issuer.data)?.modeloFacturacion) ?: 1
    if (!(tipoModelo in [1, 2])) {
        ec.message.addError("tipoModelo debe ser 1 (previo) o 2 (diferido); recibido ${tipoModelo}")
        return [:]
    }

    Timestamp invoiceTs = (Timestamp) (invoice.invoiceDate ?: ec.user.nowTimestamp)
    def ldt = invoiceTs.toInstant().atZone(ZoneId.of('America/El_Salvador')).toLocalDateTime()
    Map identificacion = [
            version: version,
            ambiente: sysProp('sv.dte.mh.ambiente.default', '00'),
            tipoDte: tipoDteCode,
            numeroControl: dte.numeroControl,
            codigoGeneracion: dte.codigoGeneracion,
            tipoModelo: tipoModelo,
            tipoOperacion: 1,
            tipoContingencia: null,
            motivoContin: null,
            fecEmi: ldt.toLocalDate().format(DateTimeFormatter.ISO_DATE),
            horEmi: ldt.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME),
            tipoMoneda: invoice.currencyUomId ?: 'USD'
    ]
    if (tipoDteCode in ['05', '06']) identificacion.fusion = invoice.fusionNit ?: null
    if ((tipoDteCode == '05' || tipoDteCode == '06') && docRelacionado) {
        items.cuerpo.each { Map row -> row.numeroDocumento = docRelacionado[0].numeroDocumento }
    }

    Map emisorData = (Map) issuer.data
    if (tipoDteCode in ['05', '06']) {
        Set allowed = ['nit', 'nrc', 'nombre', 'codActividad', 'descActividad',
                       'nombreComercial', 'direccion', 'telefono', 'correo'] as Set
        emisorData = emisorData.findAll { k, v -> allowed.contains(k) }
    } else if (tipoDteCode == '14') {
        // FSE schema (fe-fse-v2.json) emisor requiere:
        // nit, nrc, nombre, codActividad, descActividad, direccion, telefono,
        // codEstable, codPuntoVenta, correo. Sin tipoEstablecimiento ni codEstableMH.
        Set allowed = ['nit', 'nrc', 'nombre', 'codActividad', 'descActividad',
                       'direccion', 'telefono', 'codEstable', 'codPuntoVenta', 'correo'] as Set
        emisorData = emisorData.findAll { k, v -> allowed.contains(k) }
    } else {
        Set allowed = ['nit', 'nrc', 'nombre', 'codActividad', 'descActividad',
                       'nombreComercial', 'direccion', 'telefono', 'correo',
                       'codEstable', 'codPuntoVenta'] as Set
        emisorData = emisorData.findAll { k, v -> allowed.contains(k) }
    }

    Map dteJson
    if (tipoDteCode == '14') {
        // FSE schema (fe-fse-v2.json): orden fijo identificacion, emisor, receptor,
        // cuerpoDocumento, resumen, apendice. Sin documentoRelacionado/ventaTercero/otrosDocumentos.
        dteJson = [
                identificacion: identificacion,
                emisor: emisorData,
                receptor: receptor,
                cuerpoDocumento: items.cuerpo,
                resumen: items.resumen,
                apendice: null
        ]
    } else {
        dteJson = [
                identificacion: identificacion,
                documentoRelacionado: docRelacionado,
                emisor: emisorData,
                receptor: receptor,
                ventaTercero: null,
                cuerpoDocumento: items.cuerpo,
                resumen: items.resumen,
                apendice: null
        ]
        if (!(tipoDteCode in ['05', '06'])) {
            dteJson.otrosDocumentos = null
        }
    }
    // Normalizar números antes de serializar: BigDecimal enteros → Integer
    // (sin trailing `.0`), decimales → Double (preserva representación natural).
    // MH valida la representación, no solo el valor aritmético.
    Map normalizedJson = (Map) normalizeNumbers(dteJson)
    String jsonText = JsonOutput.prettyPrint(JsonOutput.toJson(normalizedJson))

    dte.setAll([
            tipoDteCode: tipoDteCode,
            versionDte: version,
            schemaName: schemaName,
            ambienteEnumId: 'SvAmbTest',
            tipoTransmisionEnumId: 'SvTransNormal',
            statusId: 'DteCreated',
            jsonOriginal: jsonText,
            fechaGeneracion: ec.user.nowTimestamp,
            errorCode: null,
            errorMessage: null
    ])
    dte.createOrUpdate()
    invoice.dteEmisionId = dte.dteEmisionId
    invoice.tipoDteCode = tipoDteCode
    invoice.condicionOperacionEnumId = invoice.condicionOperacionEnumId ?: 'SvCondContado'
    invoice.update()
    addEvent(ec, (String) dte.dteEmisionId, 'SvEvtGenerated', "DTE tipo ${tipoDteCode} generado localmente")
    return [dteEmisionId: dte.dteEmisionId, codigoGeneracion: dte.codigoGeneracion,
            numeroControl: dte.numeroControl, tipoDteCode: tipoDteCode,
            schemaName: schemaName, dteJson: dteJson, dteJsonText: jsonText]
}

Object validateDteJson() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    String tipoDteCode = (String) (ctx('tipoDteCode') ?: '01')
    String requestedSchemaName = (String) ctx('schemaName')
    Map jsonMap = (Map) ctx('dteJson')
    String jsonText = (String) ctx('dteJsonText')
    EntityValue dte = null
    if (dteEmisionId) {
        dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
        tipoDteCode = dte?.tipoDteCode ?: tipoDteCode
        jsonText = jsonText ?: dte?.jsonOriginal
    }
    if (!jsonText && jsonMap != null) jsonText = JsonOutput.toJson(jsonMap)
    if (!jsonText) {
        ec.message.addError('No hay JSON DTE para validar')
        return [valid: false, schemaName: requestedSchemaName ?: schemaNameFor(tipoDteCode, dte?.versionDte), errors: ['No hay JSON DTE para validar']]
    }

    String schemaName = (String) (requestedSchemaName ?: dte?.schemaName ?: schemaNameFor(tipoDteCode, dte?.versionDte))
    File schemaFile = new File("${System.getProperty('moqui.runtime')}/component/moqui-sv-localization/resource/schemas/${schemaName}")
    ObjectMapper mapper = new ObjectMapper()
    JsonNode schemaNode = mapper.readTree(schemaFile)
    schemaNode = applyMhRuntimeSchemaOverrides(mapper, schemaName, schemaNode)
    JsonNode jsonNode = mapper.readTree(jsonText)
    JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode)
    Set<ValidationMessage> validationMessages = schema.validate(jsonNode)
    List<String> errors = validationMessages.collect { it.message }.sort()
    boolean valid = errors.isEmpty()
    if (!valid && dte != null) {
        dte.statusId = 'DteError'
        dte.errorCode = 'SCHEMA'
        dte.errorMessage = errors.take(5).join('; ')
        dte.update()
    }
    return [valid: valid, schemaName: schemaName, errors: errors]
}

JsonNode applyMhRuntimeSchemaOverrides(ObjectMapper mapper, String schemaName, JsonNode schemaNode) {
    if (schemaName != 'fe-nd-v4.json') return schemaNode

    renameEmptyTributosKey(schemaNode.get('properties')?.get('cuerpoDocumento')?.get('items'))
    renameEmptyTributosKey(schemaNode.get('properties')?.get('resumen'))
    return schemaNode
}

void renameEmptyTributosKey(JsonNode node) {
    if (node == null || !node.has('properties') || !node.get('properties').has('')) return
    def props = (com.fasterxml.jackson.databind.node.ObjectNode) node.get('properties')
    JsonNode tributosNode = props.remove('')
    props.set('tributos', tributosNode)

    if (node.has('required') && node.get('required').isArray()) {
        def required = (com.fasterxml.jackson.databind.node.ArrayNode) node.get('required')
        for (int i = 0; i < required.size(); i++) {
            if (required.get(i).asText() == '') required.set(i, required.textNode('tributos'))
        }
    }
}

/**
 * Normaliza valores numéricos del dteJson para alineación con el formato
 * esperado por MH oficial:
 *   - BigDecimal con valor entero exacto (`13.00`, `100.0`) → `Integer 13`, `100`
 *   - BigDecimal con decimales → `Double` con scale del original
 *   - Recursión sobre Maps y Lists
 *
 * Sin esta normalización el `JsonOutput` de Groovy emite `"totalIva": 13.0`
 * (con trailing zero) que MH rechaza como "CALCULO INCORRECTO" en NC v4 pese
 * a ser aritméticamente correcto. El JSON aceptado por MH para FC con valor
 * entero usa `"precioUni": 120` (sin decimales), confirmando que MH valida
 * representación numérica además del valor.
 */
Object normalizeNumbers(Object node) {
    if (node instanceof Map) {
        Map out = [:]
        node.each { k, v -> out[k] = normalizeNumbers(v) }
        return out
    }
    if (node instanceof List) {
        return node.collect { normalizeNumbers(it) }
    }
    if (node instanceof BigDecimal) {
        BigDecimal bd = (BigDecimal) node
        BigDecimal stripped = bd.stripTrailingZeros()
        if (stripped.scale() <= 0) {
            // Valor entero exacto: emitir como Integer/Long para no incluir trailing zero
            return stripped.toBigIntegerExact() as long
        }
        // Decimal: usar Double para que JsonOutput emita los decimales naturales
        return bd.doubleValue()
    }
    return node
}

String resolveSecret(String key) {
    if (!key) return null
    if (key.startsWith('secret://')) {
        ExecutionContext ec = (ExecutionContext) ctx('ec')
        return ec.resource.getLocationText(key, false)
    }
    return sysProp(key)
}

String signerEndpoint() {
    String base = sysProp('sv.dte.firmador.url', 'http://localhost:8113')
    return base.replaceAll('/+$', '') + '/firmardocumento/'
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

Map postForm(String url, Map params, Map headers = null) {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('POST')
    conn.setConnectTimeout(httpConnectTimeoutMs())
    conn.setReadTimeout(httpReadTimeoutMs())
    conn.setDoOutput(true)
    conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
    conn.setRequestProperty('Accept', 'application/json')
    headers?.each { k, v -> if (v != null) conn.setRequestProperty(k as String, v as String) }
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

// ====================================================================
// Circuit breaker / health del sistema MH
// ====================================================================

EntityValue loadHealth(ExecutionContext ec) {
    EntityValue h = one(ec, 'sv.localization.dte.SvDteSystemHealth', [healthId: 'DEFAULT'])
    if (h == null) {
        h = ec.entity.makeValue('sv.localization.dte.SvDteSystemHealth').setAll([
                healthId: 'DEFAULT', statusEnumId: 'SvHealthUnknown', consecutiveFailures: 0])
        h.create()
    }
    return h
}

boolean resilienceEnabled() { propBool('sv.dte.resilience.enabled', true) }
int failureThreshold() { Integer.parseInt(sysProp('sv.dte.resilience.failure.threshold', '3')) }
int degradedThreshold() { Integer.parseInt(sysProp('sv.dte.resilience.degraded.threshold', '1')) }

void recordHealthSuccess(ExecutionContext ec, String context) {
    EntityValue h = loadHealth(ec)
    String prevStatus = h.statusEnumId
    h.setAll([statusEnumId: 'SvHealthUp', consecutiveFailures: 0,
              lastSuccessAt: ec.user.nowTimestamp, lastCheckAt: ec.user.nowTimestamp,
              lastFailureMessage: null])
    h.update()
    if (prevStatus != 'SvHealthUp') {
        ec.logger.info("SV DTE: salud MH transición ${prevStatus} → SvHealthUp tras ${context}")
    }
}

EntityValue ensureActiveContingency(ExecutionContext ec, EntityValue h, String reason) {
    if (h.activeContingenciaId) {
        EntityValue existing = one(ec, 'sv.localization.dte.DteContingencia', [dteContingenciaId: h.activeContingenciaId])
        if (existing && existing.statusId == 'DcontOpen') return existing
    }
    String contId = ec.entity.sequencedIdPrimary('sv.localization.dte.DteContingencia', null, null)
    EntityValue cont = ec.entity.makeValue('sv.localization.dte.DteContingencia').setAll([
            dteContingenciaId: contId,
            codigoGeneracionContingencia: UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            tipoContingenciaEnumId: 'SvCont1', // 1=No disponibilidad sistema MH
            motivoTexto: reason,
            fromDate: ec.user.nowTimestamp,
            statusId: 'DcontOpen'])
    cont.create()
    h.activeContingenciaId = contId
    h.contingencyOpenedAt = ec.user.nowTimestamp
    h.update()
    ec.logger.warn("SV DTE: auto-contingencia abierta ${contId} motivo='${reason}'")
    return cont
}

void recordHealthFailure(ExecutionContext ec, String message) {
    EntityValue h = loadHealth(ec)
    int newCount = (h.consecutiveFailures ?: 0) as int
    newCount++
    String newStatus = h.statusEnumId
    if (newCount >= failureThreshold()) newStatus = 'SvHealthDown'
    else if (newCount >= degradedThreshold()) newStatus = 'SvHealthDegraded'
    h.setAll([statusEnumId: newStatus, consecutiveFailures: newCount,
              lastFailureAt: ec.user.nowTimestamp, lastCheckAt: ec.user.nowTimestamp,
              lastFailureMessage: message])
    h.update()
    if (newStatus == 'SvHealthDown' && !h.activeContingenciaId) {
        ensureActiveContingency(ec, h, "MH no responde tras ${newCount} fallos consecutivos: ${message}")
    }
}

void linkDteToContingency(ExecutionContext ec, String dteEmisionId, String contingenciaId) {
    EntityValue existing = ec.entity.find('sv.localization.dte.DteContingenciaItem')
            .condition('dteContingenciaId', contingenciaId)
            .condition('dteEmisionId', dteEmisionId).one()
    if (existing != null) return
    long max = ec.entity.find('sv.localization.dte.DteContingenciaItem')
            .condition('dteContingenciaId', contingenciaId).count()
    ec.entity.makeValue('sv.localization.dte.DteContingenciaItem').setAll([
            dteContingenciaId: contingenciaId, dteEmisionId: dteEmisionId, noItem: (max + 1) as int]).create()
}

Object signDte() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    if (dte == null) {
        ec.message.addError("DTE ${dteEmisionId} no existe")
        return [signed: false, message: 'DTE no existe']
    }
    if (propBool('sv.dte.sign.mock.enabled', false)) {
        String jws = "MOCK-JWS-${dte.codigoGeneracion}"
        dte.jwsCompactSerialization = jws
        dte.jsonFirmadoResp = JsonOutput.toJson([status: 'OK', body: jws, mock: true])
        dte.statusId = 'DteSigned'
        dte.errorCode = null
        dte.errorMessage = null
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtSignedOk', 'Firma mock explícita habilitada')
        return [signed: true, jwsCompactSerialization: jws, message: 'Firma mock explícita']
    }

    try {
        Map dteJson = loadDteJson(ec, dte)
        String nit = digitsOnly(dteJson?.emisor?.nit)
        EntityValue cert = one(ec, 'sv.localization.dte.DteCertificate', [nit: nit])
        String password = resolveSecret((String) cert?.passwordSecretKey)
        if (!cert || !password) throw new IllegalStateException("Certificado/password no configurado para NIT ${nit}")
        Map response = postJson(signerEndpoint(), [nit: nit, activo: true, passwordPri: password, dteJson: dteJson])
        Map parsed = response.body ? (Map) new JsonSlurper().parseText((String) response.body) : [:]
        if (response.status < 200 || response.status >= 300 || parsed.status != 'OK' || !parsed.body) {
            throw new IllegalStateException("Firmador rechazó DTE: HTTP ${response.status} ${response.body}")
        }
        dte.jwsCompactSerialization = parsed.body as String
        dte.jsonFirmadoResp = response.body
        dte.statusId = 'DteSigned'
        dte.errorCode = null
        dte.errorMessage = null
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtSignedOk', 'Firma local OK', parsed, response.status as Integer)
        return [signed: true, jwsCompactSerialization: dte.jwsCompactSerialization, message: 'Firma local OK']
    } catch (Throwable t) {
        dte.statusId = 'DteError'
        dte.errorCode = 'SIGN'
        dte.errorMessage = t.message
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtSignedFail', 'Firma local fallida', t.message)
        return [signed: false, message: t.message]
    }
}

Object transmitDte() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String dteEmisionId = (String) ctx('dteEmisionId')
    EntityValue dte = one(ec, 'sv.localization.dte.DteEmision', [dteEmisionId: dteEmisionId])
    if (dte == null) {
        ec.message.addError("DTE ${dteEmisionId} no existe")
        return [transmitted: false, mock: false, message: 'DTE no existe']
    }
    EntityValue config = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
    boolean mockEnabled = propBool('sv.dte.mh.mock.enabled', config?.mockEnabled != 'N')
    if (!dte.jwsCompactSerialization) {
        dte.statusId = 'DteError'
        dte.errorCode = 'TRANSMIT'
        dte.errorMessage = 'DTE no firmado; no se transmite ni en mock'
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtTransmittedFail', dte.errorMessage)
        return [transmitted: false, mock: mockEnabled, statusId: dte.statusId, message: dte.errorMessage]
    }
    if (mockEnabled) {
        String sello = "MOCK-${dte.codigoGeneracion}"
        Map mockPayload = [estado: 'PROCESADO_MOCK', selloRecibido: sello, descripcionMsg: 'Respuesta MH simulada en Fase 2A']
        dte.selloRecepcion = sello
        dte.statusId = 'DteMockAccepted'
        dte.fechaTransmision = ec.user.nowTimestamp
        dte.fechaProcesamientoMh = ec.user.nowTimestamp
        dte.observacionesMh = 'Transmisión mock local; no corresponde a aceptación oficial MH.'
        dte.errorCode = null
        dte.errorMessage = null
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtMockTransmitted', 'Transmisión mock local', mockPayload, 200)
        addEvent(ec, dteEmisionId, 'SvEvtMockAccepted', 'Aceptación mock local', mockPayload, 200)
        return [transmitted: true, mock: true, statusId: dte.statusId, selloRecepcion: sello, message: 'Aceptado mock local']
    }
    // ===== Rama real / simulador HTTP: conmutable por URLs en SvTaxAuthorityConfig =====
    // Switch maestro: si sv.dte.test.local=true, usa URLs/credenciales del simulador local
    // (sv.dte.local.*), ignorando lo que diga SvTaxAuthorityConfig. Override por env DTE_TEST_LOCAL.
    boolean useLocalSim = propBool('sv.dte.test.local', true)
    String authUrl, recepUrl, consultaUrl, userMh, mhPassword
    if (useLocalSim) {
        authUrl = sysProp('sv.dte.local.auth.url')
        recepUrl = sysProp('sv.dte.local.recepcion.url')
        consultaUrl = sysProp('sv.dte.local.consulta.url')
        userMh = sysProp('sv.dte.local.demo.user', 'demo')
        mhPassword = sysProp('sv.dte.local.demo.password', 'hacienda')
    } else {
        if (config == null) {
            dte.statusId = 'DteError'; dte.errorCode = 'MH_CONFIG'
            dte.errorMessage = 'SvTaxAuthorityConfig TA_SV_MH no configurada (modo real)'
            dte.update()
            addEvent(ec, dteEmisionId, 'SvEvtTransmittedFail', dte.errorMessage)
            return [transmitted: false, mock: false, statusId: dte.statusId, message: dte.errorMessage]
        }
        authUrl = config.mhAuthUrl
        recepUrl = config.mhRecepcionUrl
        consultaUrl = config.mhConsultaUrl
        userMh = config.defaultUserMh
        mhPassword = resolveSecret((String) config.passwordMhSecretKey)
    }

    if (!authUrl || !recepUrl) {
        dte.statusId = 'DteError'; dte.errorCode = 'MH_CONFIG'
        dte.errorMessage = "mhAuthUrl/mhRecepcionUrl ausentes (localSim=${useLocalSim})"
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtTransmittedFail', dte.errorMessage)
        return [transmitted: false, mock: false, statusId: dte.statusId, message: dte.errorMessage]
    }
    if (!userMh || !mhPassword) {
        dte.statusId = 'DteError'; dte.errorCode = 'MH_AUTH'
        dte.errorMessage = "Credenciales MH ausentes (user=${userMh}, localSim=${useLocalSim})"
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtTransmittedFail', dte.errorMessage)
        return [transmitted: false, mock: false, statusId: dte.statusId, message: dte.errorMessage]
    }

    // === Circuit breaker: si MH está DOWN, marcar DTE como contingencia sin intentar HTTP ===
    if (resilienceEnabled()) {
        EntityValue health = loadHealth(ec)
        if (health.statusEnumId == 'SvHealthDown') {
            EntityValue cont = ensureActiveContingency(ec, health, "MH marcado DOWN; DTE encolado en contingencia")
            dte.statusId = 'DteContingency'
            dte.tipoTransmisionEnumId = 'SvTransContingencia'
            dte.motivoContingenciaEnumId = 'SvCont1'
            dte.errorCode = 'MH_DOWN_AUTO'
            dte.errorMessage = "MH no disponible (auto-contingencia ${cont.dteContingenciaId})"
            dte.intentos = ((dte.intentos ?: 0) as int) + 1
            dte.update()
            linkDteToContingency(ec, dteEmisionId, (String) cont.dteContingenciaId)
            addEvent(ec, dteEmisionId, 'SvEvtAutoContingency',
                    "Circuit breaker DOWN: DTE colocado en contingencia ${cont.dteContingenciaId}")
            return [transmitted: false, mock: false, contingency: true, statusId: dte.statusId,
                    contingenciaId: cont.dteContingenciaId, message: dte.errorMessage]
        }
    }

    try {
        Map authResp = postForm((String) authUrl, [user: userMh, pwd: mhPassword])
        if (authResp.status < 200 || authResp.status >= 300 || !authResp.body) {
            throw new IllegalStateException("Auth MH falló: HTTP ${authResp.status} ${authResp.body}")
        }
        Map authParsed = (Map) new JsonSlurper().parseText((String) authResp.body)
        if (authParsed.status != 'OK' || !authParsed.body?.token) {
            throw new IllegalStateException("Auth MH respondió sin token: ${authResp.body}")
        }
        String token = ((String) authParsed.body.token).trim()
        String bearer = token.toLowerCase(Locale.ROOT).startsWith('bearer ') ? token : "Bearer ${token}"
        Map dteJson = loadDteJson(ec, dte)
        Map recPayload = [ambiente: (String) dte.ambienteEnumId == 'SvAmbProd' ? '01' : '00',
                          idEnvio: 1,
                          version: dteJson?.identificacion?.version ?: dte.versionDte ?: 1,
                          tipoDte: dte.tipoDteCode,
                          documento: dte.jwsCompactSerialization,
                          codigoGeneracion: dte.codigoGeneracion]
        Map recResp = postJson((String) recepUrl, recPayload, [Authorization: bearer])
        addEvent(ec, dteEmisionId, 'SvEvtTransmittedOk',
                "POST ${recepUrl} HTTP ${recResp.status}", recResp.body, recResp.status as Integer)
        if (recResp.status < 200 || recResp.status >= 300 || !recResp.body) {
            throw new IllegalStateException("Recepción MH HTTP ${recResp.status}: ${recResp.body}")
        }
        Map mhParsed = (Map) new JsonSlurper().parseText((String) recResp.body)
        dte.fechaTransmision = ec.user.nowTimestamp
        dte.fechaProcesamientoMh = ec.user.nowTimestamp
        dte.jsonFirmadoResp = recResp.body
        String obs = mhParsed.descripcionMsg as String
        if (mhParsed.observaciones instanceof List && !((List) mhParsed.observaciones).isEmpty()) {
            obs = (obs ? "${obs} | " : '') + ((List) mhParsed.observaciones).join(' | ')
        }
        dte.observacionesMh = obs
        dte.intentos = ((dte.intentos ?: 0) as int) + 1

        // === Casos especiales: MH procesa sin sello ===
        // Puede pasar en reintentos por duplicado o por respuestas internas inconsistentes.
        // Nunca se acepta un DTE sin sello: primero se intenta recuperar vía consultadte.
        boolean isDuplicated = mhParsed.codigoMsg == '002' || (mhParsed.descripcionMsg?.toLowerCase()?.contains('duplicad'))
        boolean processedWithoutSeal = (mhParsed.estado in ['PROCESADO', 'ACEPTADO']) && !mhParsed.selloRecibido
        if ((isDuplicated || processedWithoutSeal) && consultaUrl) {
            try {
                Map q = getJson("${consultaUrl}/${dte.codigoGeneracion}", [Authorization: bearer])
                if (q.status == 200 && q.body) {
                    Map qp = (Map) new JsonSlurper().parseText((String) q.body)
                    if (qp.selloRecibido) {
                        mhParsed.selloRecibido = qp.selloRecibido
                        mhParsed.estado = qp.estado ?: 'PROCESADO'
                        addEvent(ec, dteEmisionId, 'SvEvtAcceptedMh',
                                "DTE procesado por MH, recuperado sello via consultadte", qp, q.status as Integer)
                    }
                }
            } catch (Throwable qt) {
                ec.logger.warn("SV DTE: consultadte falló tras respuesta MH sin sello: ${qt.message}")
            }
        }

        if (mhParsed.estado == 'PROCESADO' || mhParsed.estado == 'ACEPTADO') {
            if (!mhParsed.selloRecibido) {
                throw new IllegalStateException("MH procesó DTE ${dte.codigoGeneracion} sin selloRecibido; no se marca aceptado hasta recuperar sello via consultadte")
            }
            dte.selloRecepcion = mhParsed.selloRecibido
            dte.statusId = 'DteAccepted'
            dte.errorCode = null; dte.errorMessage = null
            dte.update()
            if (resilienceEnabled()) recordHealthSuccess(ec, "DTE ${dte.codigoGeneracion} aceptado")
            addEvent(ec, dteEmisionId, 'SvEvtAcceptedMh',
                    "MH aceptó DTE (sello ${mhParsed.selloRecibido})", mhParsed, recResp.status as Integer)
            return [transmitted: true, mock: false, statusId: dte.statusId,
                    selloRecepcion: dte.selloRecepcion, message: 'Aceptado por MH']
        } else {
            dte.statusId = 'DteRejected'
            dte.errorCode = (mhParsed.codigoMsg ?: 'MH_REJECT') as String
            dte.errorMessage = obs ?: 'Rechazado por MH'
            dte.update()
            // RECHAZO de negocio (no caída): el MH respondió, por tanto sigue UP.
            if (resilienceEnabled()) recordHealthSuccess(ec, "DTE rechazado pero MH respondió")
            addEvent(ec, dteEmisionId, 'SvEvtRejectedMh', dte.errorMessage, mhParsed, recResp.status as Integer)
            return [transmitted: false, mock: false, statusId: dte.statusId, message: dte.errorMessage]
        }
    } catch (Throwable t) {
        dte.statusId = 'DteError'
        dte.errorCode = 'MH_TX'
        dte.errorMessage = t.message
        dte.intentos = ((dte.intentos ?: 0) as int) + 1
        dte.update()
        addEvent(ec, dteEmisionId, 'SvEvtTransmittedFail', t.message)
        if (resilienceEnabled()) {
            recordHealthFailure(ec, t.message)
            // Si tras este fallo MH quedó DOWN, mover el DTE a contingencia
            EntityValue health = loadHealth(ec)
            if (health.statusEnumId == 'SvHealthDown' && health.activeContingenciaId) {
                dte.statusId = 'DteContingency'
                dte.tipoTransmisionEnumId = 'SvTransContingencia'
                dte.motivoContingenciaEnumId = 'SvCont1'
                dte.errorMessage = "${t.message} | Movido a contingencia ${health.activeContingenciaId}"
                dte.update()
                linkDteToContingency(ec, dteEmisionId, (String) health.activeContingenciaId)
                addEvent(ec, dteEmisionId, 'SvEvtAutoContingency',
                        "Auto-contingencia tras umbral de fallos (${health.consecutiveFailures})")
                return [transmitted: false, mock: false, contingency: true, statusId: dte.statusId,
                        contingenciaId: health.activeContingenciaId, message: dte.errorMessage]
            }
        }
        return [transmitted: false, mock: false, statusId: dte.statusId, message: t.message]
    }
}

// ====================================================================
// Health check periódico (ServiceJob)
// ====================================================================

Object checkMhHealth() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    if (!resilienceEnabled()) return [skipped: true, message: 'resiliencia deshabilitada']
    if (propBool('sv.dte.mh.mock.enabled', true)) {
        // En modo mock no tiene sentido revisar MH real
        EntityValue h = loadHealth(ec)
        h.setAll([statusEnumId: 'SvHealthUp', consecutiveFailures: 0,
                  lastSuccessAt: ec.user.nowTimestamp, lastCheckAt: ec.user.nowTimestamp])
        h.update()
        return [skipped: true, status: 'SvHealthUp', message: 'mock activo']
    }

    boolean useLocalSim = propBool('sv.dte.test.local', true)
    String authUrl, userMh, mhPassword
    if (useLocalSim) {
        authUrl = sysProp('sv.dte.local.auth.url')
        userMh = sysProp('sv.dte.local.demo.user', 'demo')
        mhPassword = sysProp('sv.dte.local.demo.password', 'hacienda')
    } else {
        EntityValue config = one(ec, 'sv.localization.tax.SvTaxAuthorityConfig', [taxAuthorityId: 'TA_SV_MH'])
        if (config == null) return [skipped: true, message: 'SvTaxAuthorityConfig ausente']
        authUrl = config.mhAuthUrl
        userMh = config.defaultUserMh
        mhPassword = resolveSecret((String) config.passwordMhSecretKey)
    }
    if (!authUrl || !userMh || !mhPassword) return [skipped: true, message: 'config incompleta']

    EntityValue healthBefore = loadHealth(ec)
    String prevStatus = healthBefore.statusEnumId
    try {
        Map resp = postForm(authUrl, [user: userMh, pwd: mhPassword])
        if (resp.status >= 200 && resp.status < 500 && resp.body) {
            // 200 OK ideal; 401/403 indican que MH responde aunque rechace credenciales — sigue UP
            recordHealthSuccess(ec, "health check HTTP ${resp.status}")
            EntityValue healthAfter = loadHealth(ec)
            if (prevStatus == 'SvHealthDown' && healthAfter.activeContingenciaId) {
                // Auto-cierre de contingencia + retransmisión batch
                ec.logger.info("SV DTE: MH recuperado tras DOWN; cerrando contingencia ${healthAfter.activeContingenciaId}")
                ec.service.async().name('sv.localization.DteContingencyServices.close#Contingencia')
                        .parameters([dteContingenciaId: healthAfter.activeContingenciaId, reason: 'MH recuperado']).call()
            }
            return [status: 'SvHealthUp', httpStatus: resp.status]
        } else {
            recordHealthFailure(ec, "HTTP ${resp.status}: ${resp.body}")
            return [status: loadHealth(ec).statusEnumId, httpStatus: resp.status]
        }
    } catch (Throwable t) {
        recordHealthFailure(ec, t.message)
        return [status: loadHealth(ec).statusEnumId, error: t.message]
    }
}

// ====================================================================
// Reintento con backoff exponencial (ServiceJob)
// ====================================================================

Object retryFailedDtes() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    if (!resilienceEnabled()) return [skipped: true]
    int maxAttempts = Integer.parseInt(sysProp('sv.dte.resilience.retry.max.attempts', '3'))
    int baseMinutes = Integer.parseInt(sysProp('sv.dte.resilience.retry.base.minutes', '1'))
    long nowMs = System.currentTimeMillis()
    List<EntityValue> candidates = ec.entity.find('sv.localization.dte.DteEmision')
            .condition('statusId', org.moqui.entity.EntityCondition.IN, ['DteError', 'DteRetryQueued'])
            .list()
    int processed = 0, accepted = 0, stillFailing = 0, skipped = 0
    for (EntityValue dte in candidates) {
        int intentos = (dte.intentos ?: 0) as int
        if (intentos >= maxAttempts) { skipped++; continue }
        long waitMs = baseMinutes * 60_000L * (1L << Math.min(intentos, 10))
        long lastAttemptMs = ((java.sql.Timestamp) (dte.fechaTransmision ?: dte.fechaGeneracion ?: ec.user.nowTimestamp)).time
        if (nowMs - lastAttemptMs < waitMs) { skipped++; continue }
        processed++
        try {
            Map r = (Map) ec.service.sync().name('sv.localization.DteServices.transmit#Dte')
                    .parameters([dteEmisionId: dte.dteEmisionId]).call()
            if (r.transmitted) accepted++ else stillFailing++
        } catch (Throwable t) {
            stillFailing++
            ec.logger.warn("Retry de DTE ${dte.dteEmisionId} falló: ${t.message}")
        }
    }
    return [candidates: candidates.size(), processed: processed,
            accepted: accepted, stillFailing: stillFailing, skipped: skipped]
}

Object emitFromInvoice() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String invoiceId = (String) ctx('invoiceId')
    Map gen = (Map) ec.service.sync().name('sv.localization.DteServices.generate#DteJson')
            .parameters([invoiceId: invoiceId]).call()
    String dteEmisionId = (String) gen.dteEmisionId
    if (!dteEmisionId) return [message: 'No se generó DTE']
    Map validation = (Map) ec.service.sync().name('sv.localization.DteServices.validate#DteJson')
            .parameters([dteEmisionId: dteEmisionId]).call()
    if (!validation.valid) return [dteEmisionId: dteEmisionId, statusId: 'DteError', message: "Schema inválido: ${validation.errors}"]
    Map sign = (Map) ec.service.sync().name('sv.localization.DteServices.sign#Dte')
            .parameters([dteEmisionId: dteEmisionId]).call()
    if (!sign.signed) return [dteEmisionId: dteEmisionId, statusId: 'DteError', message: sign.message]
    Map tx = (Map) ec.service.sync().name('sv.localization.DteServices.transmit#Dte')
            .parameters([dteEmisionId: dteEmisionId]).call()
    return [dteEmisionId: dteEmisionId, statusId: tx.statusId, selloRecepcion: tx.selloRecepcion, message: tx.message]
}
