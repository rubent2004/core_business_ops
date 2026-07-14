import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import javax.xml.transform.stream.StreamSource
import java.math.RoundingMode

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext ec() { (ExecutionContext) ctx('ec') }

EntityValue one(ExecutionContext ec, String entityName, Map fields) {
    def find = ec.entity.find(entityName)
    fields.each { k, v -> find.condition((String) k, v) }
    return find.one()
}

BigDecimal bd(Object raw, BigDecimal fallback = BigDecimal.ZERO) {
    if (raw == null || raw == '') return fallback
    return new BigDecimal(raw.toString())
}

BigDecimal money(Object raw) { bd(raw).setScale(2, RoundingMode.HALF_UP) }

String clean(Object raw) {
    String value = raw == null ? '' : raw.toString()
    return value.trim()
}

String statusDescription(ExecutionContext ec, String statusId) {
    if (!statusId) return ''
    EntityValue status = one(ec, 'moqui.basic.StatusItem', [statusId: statusId])
    return clean(status?.description ?: statusId)
}

String enumDescription(ExecutionContext ec, String enumId) {
    if (!enumId) return ''
    EntityValue en = one(ec, 'moqui.basic.Enumeration', [enumId: enumId])
    return clean(en?.description ?: en?.optionValue ?: enumId)
}

Map partyInfo(ExecutionContext ec, String partyId) {
    if (!partyId) return [:]
    EntityValue pd = one(ec, 'mantle.party.PartyDetail', [partyId: partyId])
    EntityValue profile = one(ec, 'sv.localization.party.SvFiscalProfile', [partyId: partyId])
    List<EntityValue> ids = ec.entity.find('mantle.party.PartyIdentification')
            .condition('partyId', partyId).list()
    Map idMap = ids.collectEntries { EntityValue id -> [(id.partyIdTypeEnumId as String): id.idValue] }
    String personName = [pd?.firstName, pd?.middleName, pd?.lastName].findAll { clean(it) }.join(' ')
    String name = clean(pd?.organizationName) ?: clean(personName) ?: clean(pd?.pseudoId) ?: partyId
    return [
            partyId       : partyId,
            pseudoId      : clean(pd?.pseudoId),
            name          : name,
            nit           : clean(idMap.PitNit),
            dui           : clean(idMap.PitDui ?: pd?.dui),
            nrc           : clean(idMap.PitNrc),
            giro          : clean(profile?.actividadEconomicaId),
            codEstable    : clean(profile?.codEstableCode ?: profile?.codEstableMhCode),
            codPuntoVenta : clean(profile?.codPuntoVentaCode ?: profile?.codPuntoVentaMhCode)
    ]
}

String documentTypeLabel(Object raw, EntityValue part) {
    String requested = clean(raw).toLowerCase(Locale.ROOT)
    if (requested in ['purchase', 'compra', 'po']) return 'ORDEN DE COMPRA'
    if (requested in ['sale', 'venta', 'sales', 'so']) return 'ORDEN DE VENTA'
    if (part?.svTipoDteCode) return 'ORDEN DE VENTA'
    return 'ORDEN OPERATIVA'
}

Map itemRow(EntityValue item) {
    BigDecimal quantity = bd(item.quantity, BigDecimal.ONE)
    BigDecimal unitAmount = bd(item.unitAmount)
    BigDecimal lineTotal = quantity.multiply(unitAmount).setScale(2, RoundingMode.HALF_UP)
    return [
            orderItemSeqId : clean(item.orderItemSeqId),
            productId      : clean(item.productId),
            description    : clean(item.itemDescription ?: item.productId ?: item.orderItemSeqId),
            itemTypeEnumId : clean(item.itemTypeEnumId),
            quantity       : quantity,
            quantityUomId  : clean(item.quantityUomId),
            unitAmount     : money(unitAmount),
            lineTotal      : lineTotal,
            comments       : clean(item.comments)
    ]
}

Object getOrderPrintContext() {
    ExecutionContext ec = ec()
    String orderId = clean(ctx('orderId'))
    String orderPartSeqId = clean(ctx('orderPartSeqId')) ?: null
    if (!orderId) {
        ec.message.addError('Seleccione una orden para imprimir')
        return [:]
    }

    EntityValue orderHeader = one(ec, 'mantle.order.OrderHeader', [orderId: orderId])
    if (!orderHeader) {
        ec.message.addError("Orden ${orderId} no encontrada")
        return [:]
    }

    def partFind = ec.entity.find('mantle.order.OrderPart').condition('orderId', orderId)
    if (orderPartSeqId) partFind.condition('orderPartSeqId', orderPartSeqId)
    List<EntityValue> orderParts = partFind.orderBy('orderPartSeqId').list()
    if (!orderParts) {
        ec.message.addError("Orden ${orderId} no tiene partes para imprimir")
        return [orderHeader: orderHeader]
    }

    List<Map> parts = []
    BigDecimal computedTotal = BigDecimal.ZERO
    for (EntityValue part in orderParts) {
        List<Map> items = ec.entity.find('mantle.order.OrderItem')
                .condition('orderId', orderId)
                .condition('orderPartSeqId', part.orderPartSeqId)
                .orderBy('orderItemSeqId').list()
                .collect { EntityValue item -> itemRow(item) }
        BigDecimal itemTotal = items.inject(BigDecimal.ZERO) { BigDecimal total, Map row -> total.add((BigDecimal) row.lineTotal) }
        computedTotal = computedTotal.add(part.partTotal != null ? bd(part.partTotal) : itemTotal)
        parts.add([
                part              : part,
                partStatusLabel   : statusDescription(ec, (String) part.statusId),
                vendor            : partyInfo(ec, (String) part.vendorPartyId),
                customer          : partyInfo(ec, (String) part.customerPartyId),
                tipoDteLabel      : clean(part.svTipoDteCode),
                condicionLabel    : enumDescription(ec, (String) part.svCondicionOperacionEnumId),
                formaPagoLabel    : enumDescription(ec, (String) part.svFormaPagoEnumId),
                items             : items,
                itemTotal         : itemTotal.setScale(2, RoundingMode.HALF_UP),
                displayTotal      : money(part.partTotal != null ? part.partTotal : itemTotal)
        ])
    }

    EntityValue firstPart = orderParts.first()
    String label = documentTypeLabel(ctx('documentType'), firstPart)
    return [
            orderHeader      : orderHeader,
            orderStatusLabel : statusDescription(ec, (String) orderHeader.statusId),
            documentTypeLabel: label,
            parts            : parts,
            generatedAt      : ec.user.nowTimestamp,
            currencyUomId    : clean(orderHeader.currencyUomId ?: 'USD'),
            total            : money(orderHeader.grandTotal != null ? orderHeader.grandTotal : computedTotal)
    ]
}

Object renderOrderPdf() {
    ExecutionContext ec = ec()
    Map data = (Map) ec.service.sync().name('sv.localization.OrderPdfServices.get#OrderPrintContext')
            .parameters([orderId: ctx('orderId'), orderPartSeqId: ctx('orderPartSeqId'), documentType: ctx('documentType')]).call()
    EntityValue orderHeader = (EntityValue) data?.orderHeader
    if (!orderHeader || !data?.parts) return [rendered: false, message: 'Orden no encontrada o sin lineas']

    String templateLocation = 'component://moqui-sv-localization/template/order/OrderPrintable.xsl-fo.ftl'
    Map renderContext = ec.context
    Map values = [
            svOrderHeader      : orderHeader,
            svOrderStatusLabel : data.orderStatusLabel,
            svOrderDocumentType: data.documentTypeLabel,
            svOrderParts       : data.parts,
            svOrderGeneratedAt : data.generatedAt,
            svOrderCurrencyUomId: data.currencyUomId,
            svOrderTotal       : data.total
    ]
    Map oldValues = values.collectEntries { String key, Object value -> [(key): renderContext.get(key)] }
    Map hadValues = values.collectEntries { String key, Object value -> [(key): renderContext.containsKey(key)] }

    try {
        values.each { String key, Object value -> renderContext.put(key, value) }
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [
                rendered        : true,
                templateLocation: templateLocation,
                xslFoText       : xslFo,
                pdfBytes        : baos.toByteArray(),
                pdfSize         : baos.size(),
                pageCount       : pageCount,
                filename        : "orden-${orderHeader.orderId}.pdf",
                contentType     : 'application/pdf'
        ]
    } finally {
        oldValues.each { String key, Object value ->
            if (hadValues[key]) renderContext.put(key, value) else renderContext.remove(key)
        }
    }
}
