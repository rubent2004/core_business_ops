import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

Object createSvInvoiceFromOrderPart() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String orderId = (String) ctx('orderId')
    String orderPartSeqId = (String) ctx('orderPartSeqId')

    EntityValue orderPart = ec.entity.find('mantle.order.OrderPart')
            .condition([orderId: orderId, orderPartSeqId: orderPartSeqId]).one()
    if (!orderPart) {
        ec.message.addError("Parte de orden ${orderId}/${orderPartSeqId} no encontrada")
        return [:]
    }

    String tipoDte = (String) orderPart.svTipoDteCode
    if (!tipoDte && orderPart.customerPartyId) {
        Map suggestion = (Map) ec.service.sync().name('sv.localization.PartyFiscalServices.suggest#TipoDte')
                .parameters([partyId: orderPart.customerPartyId]).call()
        tipoDte = (String) suggestion.suggestedTipoDte
    }

    String condicion = (String) (orderPart.svCondicionOperacionEnumId ?: 'SvCondContado')
    String formaPago = (String) (orderPart.svFormaPagoEnumId ?: 'SvPago01')

    Map invoiceParams = [orderId: orderId, orderPartSeqId: orderPartSeqId]
    if (ctx('statusId')) invoiceParams.statusId = ctx('statusId')
    if (ctx('invoiceDate')) invoiceParams.invoiceDate = ctx('invoiceDate')

    Map createResult = (Map) ec.service.sync().name('mantle.account.InvoiceServices.create#EntireOrderPartInvoice')
            .parameters(invoiceParams).call()
    String invoiceId = (String) createResult.invoiceId
    if (!invoiceId) return createResult ?: [:]

    EntityValue invoice = ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId', invoiceId).one()
    if (invoice) {
        invoice.tipoDteCode = invoice.tipoDteCode ?: tipoDte
        invoice.condicionOperacionEnumId = invoice.condicionOperacionEnumId ?: condicion
        invoice.formaPagoEnumId = invoice.formaPagoEnumId ?: formaPago
        invoice.update()
    }

    return [invoiceId: invoiceId, tipoDteCode: invoice?.tipoDteCode,
            condicionOperacionEnumId: invoice?.condicionOperacionEnumId,
            formaPagoEnumId: invoice?.formaPagoEnumId]
}
