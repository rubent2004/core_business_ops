import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.sql.Timestamp

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

// =====================================================================
// Helpers compartidos entre todos los smokes
// =====================================================================

void ensureProductGravado(ExecutionContext ec) {
    EntityValue prod = ec.entity.find('mantle.product.Product').condition('productId', 'SV_PROD_GRAV').useCache(false).one()
    if (prod != null) return
    // TX propia para que un fallo de FK no envenene la TX padre del smoke
    ec.transaction.runRequireNew(30, 'ensure SV_PROD_GRAV', {
        if (ec.entity.find('mantle.product.Product').condition('productId', 'SV_PROD_GRAV').useCache(false).one() != null) return
        ec.entity.makeValue('mantle.product.Product').setAll([
                productId: 'SV_PROD_GRAV', pseudoId: 'GRAVADO',
                productName: 'Producto demo gravado',
                productTypeEnumId: 'PtAsset', amountUomId: 'USD',
                svUnidadMedidaCode: '59', svTipoItemCode: '1',
                svTributoCode: '20', svTaxTreatmentEnumId: 'SvTaxGravada'
        ]).create()
    })
}

void ensureConsumerReceptor(ExecutionContext ec, String partyId, String ownerPartyId) {
    EntityValue rcv = ec.entity.find('mantle.party.Party').condition('partyId', partyId).one()
    if (rcv != null) return
    ec.entity.makeValue('mantle.party.Party').setAll([
            partyId: partyId, partyTypeEnumId: 'PtyPerson', ownerPartyId: ownerPartyId]).create()
    ec.entity.makeValue('mantle.party.Person').setAll([
            partyId: partyId, firstName: 'Carlos', lastName: 'Martínez', dui: '01234567-8']).create()
    ec.entity.makeValue('mantle.party.PartyRole').setAll([
            partyId: partyId, roleTypeId: 'Customer']).create()
    ec.entity.makeValue('mantle.party.PartyIdentification').setAll([
            partyId: partyId, partyIdTypeEnumId: 'PitDui', idValue: '01234567-8']).create()
    ec.entity.makeValue('sv.localization.party.SvFiscalProfile').setAll([
            svFiscalProfileId: 'SvFp_' + partyId,
            partyId: partyId, tipoContribuyenteEnumId: 'TcOtros']).create()
}

void ensureBusinessReceptor(ExecutionContext ec, String partyId, String ownerPartyId) {
    EntityValue rcv = ec.entity.find('mantle.party.Party').condition('partyId', partyId).useCache(false).one()
    if (rcv != null) return
    ec.transaction.runRequireNew(30, "ensure business receptor ${partyId}", {
        if (ec.entity.find('mantle.party.Party').condition('partyId', partyId).useCache(false).one() != null) return
        // Receptor REAL: MANUEL NAPOLEON CARDONA GUTIERREZ — persona natural con
        // NIT 021009289 + NRC 3095561 registrados en MH apitest (validado por
        // emisión previa con sello 20252D0D0FEAA9E64FD1AE39F239379C72A65XRI).
        ec.entity.makeValue('mantle.party.Party').setAll([
                partyId: partyId, partyTypeEnumId: 'PtyPerson', ownerPartyId: ownerPartyId]).create()
        ec.entity.makeValue('mantle.party.Person').setAll([
                partyId: partyId, firstName: 'MANUEL', middleName: 'NAPOLEON',
                lastName: 'CARDONA GUTIERREZ']).create()
        ec.entity.makeValue('mantle.party.PartyRole').setAll([
                partyId: partyId, roleTypeId: 'Customer']).create()
        ec.entity.makeValue('mantle.party.PartyIdentification').setAll([
                partyId: partyId, partyIdTypeEnumId: 'PitNit', idValue: '021009289']).create()
        ec.entity.makeValue('mantle.party.PartyIdentification').setAll([
                partyId: partyId, partyIdTypeEnumId: 'PitNrc', idValue: '3095561']).create()
        ec.entity.makeValue('sv.localization.party.SvFiscalProfile').setAll([
                svFiscalProfileId: 'SvFp_' + partyId,
                partyId: partyId, tipoContribuyenteEnumId: 'TcOtros',
                actividadEconomicaId: 'SVEA_71102', nrc: '3095561',
                tipoEstablecimientoMhCode: '02',
                responsableNombre: 'MANUEL NAPOLEON CARDONA GUTIERREZ',
                responsableDui: '02100928-9']).create()
        // PostalAddress requerido para CCF schema (receptor.direccion)
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_PA', contactMechTypeEnumId: 'CmtPostalAddress']).create()
        ec.entity.makeValue('mantle.party.contact.PostalAddress').setAll([
                contactMechId: partyId + '_PA', toName: 'Cliente Empresarial Smoke',
                address1: 'Blvd. Los Héroes #456', city: 'San Salvador',
                countryGeoId: 'SLV', stateProvinceGeoId: 'SV06',
                departamentoGeoId: 'SV06', municipioGeoId: 'SV06_M23',
                distritoGeoId: 'SV06_M23_D03', postalCode: '01101',
                complemento: 'Blvd. Los Héroes #456, local 3']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_PA',
                contactMechPurposeId: 'PostalPrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
        // Phone + Email
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_PT', contactMechTypeEnumId: 'CmtTelecomNumber']).create()
        ec.entity.makeValue('mantle.party.contact.TelecomNumber').setAll([
                contactMechId: partyId + '_PT', countryCode: '503', contactNumber: '22119876']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_PT',
                contactMechPurposeId: 'PhonePrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_EM', contactMechTypeEnumId: 'CmtEmailAddress',
                infoString: 'compras@clientesmoke.com.sv']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_EM',
                contactMechPurposeId: 'EmailPrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
    })
}

void ensureExcludedSupplier(ExecutionContext ec, String partyId) {
    EntityValue rcv = ec.entity.find('mantle.party.Party').condition('partyId', partyId).useCache(false).one()
    if (rcv != null) return
    ec.transaction.runRequireNew(30, "ensure excluded supplier ${partyId}", {
        if (ec.entity.find('mantle.party.Party').condition('partyId', partyId).useCache(false).one() != null) return
        ec.entity.makeValue('mantle.party.Party').setAll([
                partyId: partyId, partyTypeEnumId: 'PtyPerson']).create()
        ec.entity.makeValue('mantle.party.Person').setAll([
                partyId: partyId, firstName: 'Juan', lastName: 'Sujeto Excluido', dui: '11223344-5']).create()
        ec.entity.makeValue('mantle.party.PartyRole').setAll([
                partyId: partyId, roleTypeId: 'Supplier']).create()
        ec.entity.makeValue('mantle.party.PartyIdentification').setAll([
                partyId: partyId, partyIdTypeEnumId: 'PitDui', idValue: '11223344-5']).create()
        ec.entity.makeValue('sv.localization.party.SvFiscalProfile').setAll([
                svFiscalProfileId: 'SvFp_' + partyId,
                partyId: partyId, tipoContribuyenteEnumId: 'TcExcluido',
                actividadEconomicaId: 'SVEA_01111']).create()
        // PostalAddress + Telecom + Email para FSE schema
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_PA', contactMechTypeEnumId: 'CmtPostalAddress']).create()
        ec.entity.makeValue('mantle.party.contact.PostalAddress').setAll([
                contactMechId: partyId + '_PA', toName: 'Juan Sujeto Excluido',
                address1: 'Colonia Centro', city: 'San Salvador',
                countryGeoId: 'SLV', stateProvinceGeoId: 'SV06',
                departamentoGeoId: 'SV06', municipioGeoId: 'SV06_M23',
                distritoGeoId: 'SV06_M23_D03', postalCode: '01101',
                complemento: 'Colonia Centro 123, San Salvador']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_PA',
                contactMechPurposeId: 'PostalPrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_PT', contactMechTypeEnumId: 'CmtTelecomNumber']).create()
        ec.entity.makeValue('mantle.party.contact.TelecomNumber').setAll([
                contactMechId: partyId + '_PT', countryCode: '503', contactNumber: '76005678']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_PT',
                contactMechPurposeId: 'PhonePrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
        ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                contactMechId: partyId + '_EM', contactMechTypeEnumId: 'CmtEmailAddress',
                infoString: 'sujeto.excluido@example.sv']).create()
        ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                partyId: partyId, contactMechId: partyId + '_EM',
                contactMechPurposeId: 'EmailPrimary',
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).create()
    })
}

String createInvoiceAndItem(ExecutionContext ec, String invoiceTypeEnumId,
                            String fromPartyId, String toPartyId, BigDecimal amount,
                            String descr, String dteReferenceEmisionId = null) {
    String invoiceId = ec.entity.sequencedIdPrimary('mantle.account.invoice.Invoice', null, null)
    Timestamp now = ec.user.nowTimestamp
    Map invoiceFields = [
            invoiceId        : invoiceId,
            invoiceTypeEnumId: invoiceTypeEnumId,
            fromPartyId      : fromPartyId,
            toPartyId        : toPartyId,
            statusId         : 'InvoiceFinalized',
            invoiceDate      : now,
            currencyUomId    : 'USD',
            invoiceTotal     : amount,
            unpaidTotal      : amount,
            description      : descr
    ]
    if (dteReferenceEmisionId) invoiceFields.dteReferenceEmisionId = dteReferenceEmisionId
    ec.entity.makeValue('mantle.account.invoice.Invoice').setAll(invoiceFields).create()
    ec.entity.makeValue('mantle.account.invoice.InvoiceItem').setAll([
            invoiceId        : invoiceId,
            invoiceItemSeqId : '0001',
            itemTypeEnumId   : 'ItemSales',
            productId        : 'SV_PROD_GRAV',
            quantity         : new BigDecimal('1'),
            amount           : amount,
            description      : descr
    ]).create()
    return invoiceId
}

Map runEmitAndCollect(ExecutionContext ec, String invoiceId, String invoiceTypeEnumId) {
    Map result = (Map) ec.service.sync()
            .name('sv.localization.DteServices.emit#FromInvoice')
            .parameters([invoiceId: invoiceId]).call()

    String dteEmisionId = (String) result?.dteEmisionId
    EntityValue dte = dteEmisionId ? ec.entity.find('sv.localization.dte.DteEmision')
            .condition('dteEmisionId', dteEmisionId).useCache(false).one() : null

    return [
            tipoDte         : invoiceTypeEnumId,
            invoiceId       : invoiceId,
            dteEmisionId    : dteEmisionId,
            codigoGeneracion: dte?.codigoGeneracion,
            numeroControl   : dte?.numeroControl,
            statusId        : result?.statusId ?: dte?.statusId,
            selloRecepcion  : result?.selloRecepcion ?: dte?.selloRecepcion,
            message         : result?.message,
            errorMessage    : dte?.errorMessage,
            errorCode       : dte?.errorCode
    ]
}

void requireOrg(ExecutionContext ec, String partyId) {
    if (ec.entity.find('mantle.party.Organization').condition('partyId', partyId).one() == null) {
        throw new IllegalStateException("Organization ${partyId} no existe. Carga el seed install con ./gradlew load -Ptypes=all,install.")
    }
}

/** Asegura que SARA Org tenga PostalAddress + TelecomNumber + EmailAddress
 *  completos en su propia TX, defendiéndonos de seeds que no cargaron campos. */
void ensureOrgContactMechs(ExecutionContext ec, String partyId) {
    ec.transaction.runRequireNew(30, "ensure contacts ${partyId}", {
        // PostalAddress
        EntityValue pa = ec.entity.find('mantle.party.contact.ContactMech')
                .condition('contactMechId', partyId + '_PA').useCache(false).one()
        if (pa == null) {
            ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                    contactMechId: partyId + '_PA', contactMechTypeEnumId: 'CmtPostalAddress'
            ]).create()
            ec.entity.makeValue('mantle.party.contact.PostalAddress').setAll([
                    contactMechId: partyId + '_PA', toName: 'SARA Robotics',
                    address1: 'San Salvador, El Salvador', city: 'San Salvador',
                    countryGeoId: 'SLV', stateProvinceGeoId: 'SV06',
                    departamentoGeoId: 'SV06', municipioGeoId: 'SV06_M23',
                    distritoGeoId: 'SV06_M23_D03', postalCode: '01101',
                    complemento: 'San Salvador, El Salvador'
            ]).create()
            ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                    partyId: partyId, contactMechId: partyId + '_PA',
                    contactMechPurposeId: 'PostalPrimary',
                    fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)
            ]).create()
        } else {
            EntityValue paData = ec.entity.find('mantle.party.contact.PostalAddress')
                    .condition('contactMechId', partyId + '_PA').useCache(false).one()
            if (paData == null || !paData.city) {
                ec.entity.makeValue('mantle.party.contact.PostalAddress').setAll([
                        contactMechId: partyId + '_PA', toName: 'SARA Robotics',
                        address1: 'San Salvador, El Salvador', city: 'San Salvador',
                        countryGeoId: 'SLV', stateProvinceGeoId: 'SV06',
                        departamentoGeoId: 'SV06', municipioGeoId: 'SV06_M23',
                        distritoGeoId: 'SV06_M23_D03', postalCode: '01101',
                        complemento: 'San Salvador, El Salvador'
                ]).createOrUpdate()
            }
        }
        // Phone
        if (ec.entity.find('mantle.party.contact.ContactMech').condition('contactMechId', partyId + '_PT').useCache(false).one() == null) {
            ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                    contactMechId: partyId + '_PT', contactMechTypeEnumId: 'CmtTelecomNumber']).create()
            ec.entity.makeValue('mantle.party.contact.TelecomNumber').setAll([
                    contactMechId: partyId + '_PT', countryCode: '503', contactNumber: '22000000']).create()
            ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                    partyId: partyId, contactMechId: partyId + '_PT',
                    contactMechPurposeId: 'PhonePrimary',
                    fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)
            ]).create()
        } else {
            EntityValue tnData = ec.entity.find('mantle.party.contact.TelecomNumber')
                    .condition('contactMechId', partyId + '_PT').useCache(false).one()
            if (tnData == null) {
                ec.entity.makeValue('mantle.party.contact.TelecomNumber').setAll([
                        contactMechId: partyId + '_PT', countryCode: '503', contactNumber: '22000000'
                ]).create()
            }
        }
        // Email — infoString va en ContactMech mismo
        EntityValue em = ec.entity.find('mantle.party.contact.ContactMech')
                .condition('contactMechId', partyId + '_EM').useCache(false).one()
        if (em == null) {
            ec.entity.makeValue('mantle.party.contact.ContactMech').setAll([
                    contactMechId: partyId + '_EM', contactMechTypeEnumId: 'CmtEmailAddress',
                    infoString: 'facturacion@sararobotics.com']).create()
            ec.entity.makeValue('mantle.party.contact.PartyContactMech').setAll([
                    partyId: partyId, contactMechId: partyId + '_EM',
                    contactMechPurposeId: 'EmailPrimary',
                    fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)
            ]).create()
        } else if (!em.infoString) {
            em.infoString = 'facturacion@sararobotics.com'
            em.update()
        }
    })
}

Object smokeFcRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String fromPartyId = (String) (ctx('fromPartyId') ?: 'ORG_SARA_ROBOTICS')
    String toPartyId = (String) (ctx('toPartyId') ?: 'SMOKE_CONS_RCV')
    BigDecimal amount = (BigDecimal) (ctx('amount') ?: new BigDecimal('1.13'))
    String descr = (String) (ctx('description') ?: 'Smoke FC 01 real apitest')

    requireOrg(ec, fromPartyId)
    ensureOrgContactMechs(ec, fromPartyId)
    String invoiceId = null
    ec.transaction.runUseOrBegin(30, 'Smoke FC setup', {
        ensureConsumerReceptor(ec, toPartyId, fromPartyId)
        ensureProductGravado(ec)
        invoiceId = createInvoiceAndItem(ec, 'InvSvFc', fromPartyId, toPartyId, amount, descr)
    })
    return runEmitAndCollect(ec, invoiceId, 'InvSvFc')
}

Object smokeCcfRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String fromPartyId = (String) (ctx('fromPartyId') ?: 'ORG_SARA_ROBOTICS')
    String toPartyId = (String) (ctx('toPartyId') ?: 'SMOKE_BIZ_MOLVU')
    BigDecimal amount = (BigDecimal) (ctx('amount') ?: new BigDecimal('11.30'))
    String descr = (String) (ctx('description') ?: 'Smoke CCF 03 real apitest')

    requireOrg(ec, fromPartyId)
    ensureOrgContactMechs(ec, fromPartyId)
    String invoiceId = null
    ec.transaction.runUseOrBegin(30, 'Smoke CCF setup', {
        ensureBusinessReceptor(ec, toPartyId, fromPartyId)
        ensureProductGravado(ec)
        invoiceId = createInvoiceAndItem(ec, 'InvSvCcf', fromPartyId, toPartyId, amount, descr)
    })
    return runEmitAndCollect(ec, invoiceId, 'InvSvCcf')
}

Object smokeNcRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String fromPartyId = (String) (ctx('fromPartyId') ?: 'ORG_SARA_ROBOTICS')
    String toPartyId = (String) (ctx('toPartyId') ?: 'SMOKE_BIZ_MOLVU')
    String referenceDteEmisionId = (String) ctx('referenceDteEmisionId')
    BigDecimal amount = (BigDecimal) (ctx('amount') ?: new BigDecimal('11.30'))
    String descr = (String) (ctx('description') ?: 'Smoke NC 05 real apitest')

    if (!referenceDteEmisionId) {
        ec.message.addError('referenceDteEmisionId requerido (debe apuntar a un CCF DteAccepted)')
        return [:]
    }
    requireOrg(ec, fromPartyId)
    ensureOrgContactMechs(ec, fromPartyId)
    String invoiceId = null
    ec.transaction.runUseOrBegin(30, 'Smoke NC setup', {
        ensureBusinessReceptor(ec, toPartyId, fromPartyId)
        ensureProductGravado(ec)
        invoiceId = createInvoiceAndItem(ec, 'InvSvNc', fromPartyId, toPartyId, amount, descr, referenceDteEmisionId)
    })
    return runEmitAndCollect(ec, invoiceId, 'InvSvNc')
}

Object smokeNdRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String fromPartyId = (String) (ctx('fromPartyId') ?: 'ORG_SARA_ROBOTICS')
    String toPartyId = (String) (ctx('toPartyId') ?: 'SMOKE_BIZ_MOLVU')
    String referenceDteEmisionId = (String) ctx('referenceDteEmisionId')
    BigDecimal amount = (BigDecimal) (ctx('amount') ?: new BigDecimal('11.30'))
    String descr = (String) (ctx('description') ?: 'Smoke ND 06 real apitest')

    if (!referenceDteEmisionId) {
        ec.message.addError('referenceDteEmisionId requerido (debe apuntar a un CCF DteAccepted)')
        return [:]
    }
    requireOrg(ec, fromPartyId)
    ensureOrgContactMechs(ec, fromPartyId)
    String invoiceId = null
    ec.transaction.runUseOrBegin(30, 'Smoke ND setup', {
        ensureBusinessReceptor(ec, toPartyId, fromPartyId)
        ensureProductGravado(ec)
        invoiceId = createInvoiceAndItem(ec, 'InvSvNd', fromPartyId, toPartyId, amount, descr, referenceDteEmisionId)
    })
    return runEmitAndCollect(ec, invoiceId, 'InvSvNd')
}

Object smokeFseRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    // FSE 14: SARA COMPRA a sujeto excluido. Invoice from=supplier to=SARA.
    String orgPartyId = (String) (ctx('orgPartyId') ?: 'ORG_SARA_ROBOTICS')
    String supplierPartyId = (String) (ctx('supplierPartyId') ?: 'SMOKE_EXCL_SUPP2')
    BigDecimal amount = (BigDecimal) (ctx('amount') ?: new BigDecimal('11.30'))
    String descr = (String) (ctx('description') ?: 'Smoke FSE 14 real apitest')

    requireOrg(ec, orgPartyId)
    ensureOrgContactMechs(ec, orgPartyId)
    String invoiceId = null
    ec.transaction.runUseOrBegin(30, 'Smoke FSE setup', {
        ensureExcludedSupplier(ec, supplierPartyId)
        ensureProductGravado(ec)
        // FSE 14 Invoice direction: from=Org (emisor del documento) to=SujetoExcluido (receptor).
        // Conceptualmente Org compra a Sujeto Excluido, pero en Mantle el "from" es el emisor del DTE.
        invoiceId = createInvoiceAndItem(ec, 'InvSvFse', orgPartyId, supplierPartyId, amount, descr)
    })
    return runEmitAndCollect(ec, invoiceId, 'InvSvFse')
}

/**
 * Orquestador: ejecuta FC → CCF → NC → ND → FSE en orden. NC/ND reciben el
 * dteEmisionId del CCF emitido en este mismo run.
 */
Map callAndCapture(ExecutionContext ec, String serviceName, Map params) {
    ec.message.clearAll()
    Map result = null
    try {
        result = (Map) ec.service.sync().name(serviceName).parameters(params).call()
    } catch (Throwable t) {
        result = [exception: t.message]
    }
    List<String> errs = []
    if (ec.message.hasError()) {
        errs.addAll(ec.message.errors)
        ec.message.clearAll()
    }
    if (errs) (result ?: [:]).accumulatedErrors = errs
    return result ?: [:]
}

Object smokeAllTypesRealEmission() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String orgId = 'ORG_SARA_ROBOTICS'
    requireOrg(ec, orgId)
    ensureOrgContactMechs(ec, orgId)
    List<Map> results = []

    results.add(callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#FcRealEmission',
            [fromPartyId: orgId, toPartyId: 'SMOKE_CONS_RCV',
             amount: new BigDecimal('1.13'), description: 'Smoke FC 01 real apitest']))

    Map ccfResult = callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#CcfRealEmission',
            [fromPartyId: orgId, toPartyId: 'SMOKE_BIZ_MOLVU',
             amount: new BigDecimal('11.30'), description: 'Smoke CCF 03 real apitest'])
    results.add(ccfResult)

    if (ccfResult?.statusId in ['DteAccepted', 'DteMockAccepted']) {
        results.add(callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#NcRealEmission',
                [fromPartyId: orgId, toPartyId: 'SMOKE_BIZ_MOLVU',
                 referenceDteEmisionId: ccfResult.dteEmisionId,
                 amount: new BigDecimal('11.30'), description: 'Smoke NC 05 real apitest']))
        // Emite un SEGUNDO CCF para ND (cada NC/ND debe referenciar un CCF
        // distinto; MH rechaza por compliance si un mismo CCF tiene NC+ND
        // aplicadas a la vez).
        Map ccfForNd = callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#CcfRealEmission',
                [fromPartyId: orgId, toPartyId: 'SMOKE_BIZ_MOLVU',
                 amount: new BigDecimal('11.30'),
                 description: 'Smoke CCF 03 (base para ND)'])
        if (ccfForNd?.statusId in ['DteAccepted', 'DteMockAccepted']) {
            results.add(callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#NdRealEmission',
                    [fromPartyId: orgId, toPartyId: 'SMOKE_BIZ_MOLVU',
                     referenceDteEmisionId: ccfForNd.dteEmisionId,
                     amount: new BigDecimal('11.30'), description: 'Smoke ND 06 real apitest']))
        } else {
            results.add([tipoDte: 'InvSvNd', skipped: true, reason: 'CCF base para ND no aceptado'])
        }
    } else {
        results.add([tipoDte: 'InvSvNc', skipped: true, reason: 'CCF previo no aceptado'])
        results.add([tipoDte: 'InvSvNd', skipped: true, reason: 'CCF previo no aceptado'])
    }

    results.add(callAndCapture(ec, 'sv.localization.SmokeRealEmissionServices.smoke#FseRealEmission',
            [orgPartyId: orgId, supplierPartyId: 'SMOKE_EXCL_SUPP2',
             amount: new BigDecimal('11.30'), description: 'Smoke FSE 14 real apitest']))

    return [results: results]
}
