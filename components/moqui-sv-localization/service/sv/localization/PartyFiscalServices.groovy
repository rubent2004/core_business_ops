import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

Map normalizeDigits(String raw, int expectedLength, String label) {
    String digits = (raw ?: '').replaceAll('[^0-9]', '')
    if (digits.length() != expectedLength) {
        return [valid: false, normalizedValue: raw, message: "${label} debe tener ${expectedLength} dígitos"]
    }
    return [valid: true, normalizedValue: digits, message: 'OK']
}

Map validateDuiValue(String raw) {
    Map base = normalizeDigits(raw, 9, 'DUI')
    if (!base.valid) return base
    String digits = base.normalizedValue
    int sum = 0
    for (int i = 0; i < 8; i++) sum += Character.digit(digits.charAt(i), 10) * (9 - i)
    int expected = (10 - (sum % 10)) % 10
    int actual = Character.digit(digits.charAt(8), 10)
    String formatted = "${digits.substring(0, 8)}-${digits.substring(8)}"
    return [valid: expected == actual, normalizedValue: formatted,
            message: expected == actual ? 'OK' : "DUI inválido: dígito verificador esperado ${expected}"]
}

Map validateNitValue(String raw) {
    Map base = normalizeDigits(raw, 14, 'NIT')
    if (!base.valid) return base
    String digits = base.normalizedValue
    int[] weights = [9, 8, 7, 6, 5, 4, 3, 2, 7, 6, 5, 4, 3] as int[]
    int sum = 0
    for (int i = 0; i < 13; i++) sum += Character.digit(digits.charAt(i), 10) * weights[i]
    int remainder = sum % 11
    int expected = 11 - remainder
    if (expected == 10 || expected == 11) expected = 0
    int actual = Character.digit(digits.charAt(13), 10)
    String formatted = "${digits.substring(0, 4)}-${digits.substring(4, 10)}-${digits.substring(10, 13)}-${digits.substring(13)}"
    return [valid: expected == actual, normalizedValue: formatted,
            message: expected == actual ? 'OK' : "NIT inválido: dígito verificador esperado ${expected}"]
}

Object validateDui() {
    return validateDuiValue((String) ctx('dui'))
}

Object validateNit() {
    return validateNitValue((String) ctx('nit'))
}

void storeIdentification(ExecutionContext ec, String partyId, String typeEnumId, String value) {
    if (!value) return
    ec.entity.makeValue('mantle.party.PartyIdentification').setAll([
            partyId: partyId, partyIdTypeEnumId: typeEnumId, idValue: value
    ]).createOrUpdate()
}

void applyContributorClassification(ExecutionContext ec, String partyId, String classificationId) {
    if (!classificationId) return
    Set<String> svClassIds = ['TcGrande', 'TcMediano', 'TcOtros', 'TcExcluido'] as Set
    if (!svClassIds.contains(classificationId)) {
        ec.message.addError("Tipo de contribuyente SV inválido: ${classificationId}")
        return
    }

    Timestamp nowTs = ec.user.nowTimestamp
    List<EntityValue> applList = ec.entity.find('mantle.party.PartyClassificationAppl')
            .condition('partyId', partyId).condition('thruDate', null).list()
    boolean targetExists = false
    for (EntityValue appl in applList) {
        if (!svClassIds.contains((String) appl.partyClassificationId)) continue
        if (appl.partyClassificationId == classificationId) {
            targetExists = true
        } else {
            appl.thruDate = nowTs
            appl.update()
        }
    }
    if (!targetExists) {
        ec.entity.makeValue('mantle.party.PartyClassificationAppl').setAll([
                partyId: partyId, partyClassificationId: classificationId, fromDate: nowTs
        ]).create()
    }
}

Object upsertPartyFiscalProfile() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    String duiRaw = (String) ctx('dui')
    String nitRaw = (String) ctx('nit')
    String nrc = ((String) ctx('nrc'))?.trim()
    String responsableDuiRaw = (String) ctx('responsableDui')

    String duiNorm = null
    if (duiRaw) {
        Map duiResult = validateDuiValue(duiRaw)
        if (!duiResult.valid) {
            ec.message.addError((String) duiResult.message)
            return [:]
        }
        duiNorm = (String) duiResult.normalizedValue
    }

    String nitNorm = null
    if (nitRaw) {
        Map nitResult = validateNitValue(nitRaw)
        if (!nitResult.valid) {
            ec.message.addError((String) nitResult.message)
            return [:]
        }
        nitNorm = (String) nitResult.normalizedValue
    }

    String responsableDuiNorm = null
    if (responsableDuiRaw) {
        Map responsableDuiResult = validateDuiValue(responsableDuiRaw)
        if (!responsableDuiResult.valid) {
            ec.message.addError("Responsable: ${responsableDuiResult.message}")
            return [:]
        }
        responsableDuiNorm = (String) responsableDuiResult.normalizedValue
    }

    EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile').condition('partyId', partyId).one()
    if (profile == null) {
        String profileId = ec.entity.sequencedIdPrimary('sv.localization.party.SvFiscalProfile', null, null)
        profile = ec.entity.makeValue('sv.localization.party.SvFiscalProfile').setAll([
                svFiscalProfileId: profileId, partyId: partyId
        ])
    }

    Map profileFields = [
            tipoContribuyenteEnumId: ctx('tipoContribuyenteEnumId'),
            actividadEconomicaId: ctx('actividadEconomicaId'),
            nrc: nrc,
            tipoEstablecimientoMhCode: ctx('tipoEstablecimientoMhCode'),
            codEstableMhCode: ctx('codEstableMhCode'),
            codPuntoVentaMhCode: ctx('codPuntoVentaMhCode'),
            codEstableCode: ctx('codEstableCode'),
            codPuntoVentaCode: ctx('codPuntoVentaCode'),
            nombreComercial: ctx('nombreComercial'),
            responsableNombre: ctx('responsableNombre'),
            responsableDui: responsableDuiNorm,
            fechaInicioOperaciones: ctx('fechaInicioOperaciones')
    ]
    if (ctx('nupIsss') != null) profileFields.nupIsss = ((String) ctx('nupIsss'))?.trim()
    if (ctx('nupAfp') != null) profileFields.nupAfp = ((String) ctx('nupAfp'))?.trim()
    if (ctx('afpProviderEnumId') != null) profileFields.afpProviderEnumId = ctx('afpProviderEnumId')
    if (ctx('fechaIngreso') != null) profileFields.fechaIngreso = ctx('fechaIngreso')
    profile.setAll(profileFields)
    profile.createOrUpdate()

    storeIdentification(ec, partyId, 'PitDui', duiNorm)
    storeIdentification(ec, partyId, 'PitNit', nitNorm)
    storeIdentification(ec, partyId, 'PitNrc', nrc)
    applyContributorClassification(ec, partyId, (String) ctx('tipoContribuyenteEnumId'))

    EntityValue party = ec.entity.find('mantle.party.Party').condition('partyId', partyId).one()
    if (party != null && party.svFiscalProfileId != profile.svFiscalProfileId) {
        party.svFiscalProfileId = profile.svFiscalProfileId
        party.update()
    }
    EntityValue person = ec.entity.find('mantle.party.Person').condition('partyId', partyId).one()
    if (person != null && duiNorm) {
        person.dui = duiNorm
        person.update()
    }
    EntityValue org = ec.entity.find('mantle.party.Organization').condition('partyId', partyId).one()
    if (org != null) {
        org.nombreComercial = ctx('nombreComercial')
        org.actividadEconomicaId = ctx('actividadEconomicaId')
        org.tipoEstablecimientoMhCode = ctx('tipoEstablecimientoMhCode')
        org.update()
    }

    return [svFiscalProfileId: profile.svFiscalProfileId]
}

Object storeSvPostalAddress() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    String contactMechId = (String) ctx('contactMechId')
    String departamentoGeoId = (String) ctx('departamentoGeoId')
    String municipioGeoId = (String) ctx('municipioGeoId')
    String distritoGeoId = (String) ctx('distritoGeoId')
    String complemento = (String) ctx('complemento')
    String purposeId = (String) ctx('contactMechPurposeId') ?: 'PostalPrimary'

    EntityValue deptGeo = ec.entity.find('moqui.basic.Geo').condition('geoId', departamentoGeoId).one()
    EntityValue munGeo = ec.entity.find('moqui.basic.Geo').condition('geoId', municipioGeoId).one()

    if (contactMechId) {
        EntityValue address = ec.entity.find('mantle.party.contact.PostalAddress')
                .condition('contactMechId', contactMechId).one()
        if (address) {
            address.countryGeoId = 'SLV'
            address.stateProvinceGeoId = departamentoGeoId
            address.departamentoGeoId = departamentoGeoId
            address.municipioGeoId = municipioGeoId
            address.distritoGeoId = distritoGeoId
            address.complemento = complemento
            address.address1 = complemento
            address.city = munGeo?.geoNameLocal ?: munGeo?.geoName
            address.update()
            return [contactMechId: contactMechId]
        }
    }

    Map storeResult = ec.service.sync().name('mantle.party.ContactServices.store#PartyContactInfo').parameters([
            partyId: partyId,
            postalContactMechPurposeId: purposeId,
            countryGeoId: 'SLV',
            stateProvinceGeoId: departamentoGeoId,
            address1: complemento,
            city: munGeo?.geoNameLocal ?: munGeo?.geoName ?: '',
            postalCode: '0000'
    ]).call()
    String newCmId = (String) storeResult.postalContactMechId
    if (newCmId) {
        EntityValue address = ec.entity.find('mantle.party.contact.PostalAddress')
                .condition('contactMechId', newCmId).one()
        if (address) {
            address.departamentoGeoId = departamentoGeoId
            address.municipioGeoId = municipioGeoId
            address.distritoGeoId = distritoGeoId
            address.complemento = complemento
            address.update()
        }
    }
    return [contactMechId: newCmId]
}

void storeSvDteContactInfo() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    String telefono = ((String) ctx('telefono'))?.trim()
    String correo = ((String) ctx('correo'))?.trim()

    if (telefono) {
        String digits = telefono.replaceAll('[^0-9+]', '')
        EntityValue existingPhone = ec.entity.find('mantle.party.contact.PartyContactMechTelecomNumber')
                .condition('partyId', partyId).conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()?.first
        if (existingPhone) {
            EntityValue tn = ec.entity.find('mantle.party.contact.TelecomNumber')
                    .condition('contactMechId', existingPhone.contactMechId).one()
            if (tn) {
                tn.contactNumber = digits
                tn.update()
            }
        } else {
            ec.service.sync().name('mantle.party.ContactServices.store#PartyContactInfo').parameters([
                    partyId: partyId,
                    telecomContactMechPurposeId: 'PhonePrimary',
                    contactNumber: digits
            ]).call()
        }
    }

    if (correo) {
        EntityValue existingEmail = ec.entity.find('mantle.party.contact.PartyContactMechInfo')
                .condition('partyId', partyId)
                .condition('contactMechTypeEnumId', 'CmtEmailAddress')
                .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()?.first
        if (existingEmail) {
            EntityValue cm = ec.entity.find('moqui.basic.contact.ContactMech')
                    .condition('contactMechId', existingEmail.contactMechId).one()
            if (cm) {
                cm.infoString = correo
                cm.update()
            }
        } else {
            ec.service.sync().name('mantle.party.ContactServices.store#PartyContactInfo').parameters([
                    partyId: partyId,
                    emailContactMechPurposeId: 'EmailPrimary',
                    emailAddress: correo
            ]).call()
        }
    }
}

Object getSvMunicipiosForDepartamento() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String departamentoGeoId = (String) ctx('departamentoGeoId')
    List<EntityValue> assocList = ec.entity.find('moqui.basic.GeoAssoc')
            .condition('geoId', departamentoGeoId)
            .condition('geoAssocTypeEnumId', 'GAT_REGIONS').list()
    List resultList = []
    for (EntityValue assoc in assocList) {
        EntityValue geo = ec.entity.find('moqui.basic.Geo').condition('geoId', assoc.toGeoId).one()
        if (geo?.geoTypeEnumId == 'GEOT_MUNICIPALITY') {
            resultList.add([value: geo.geoId, label: "${geo.geoCodeAlpha2} - ${geo.geoNameLocal ?: geo.geoName}"])
        }
    }
    resultList.sort { it.label }
    return [resultList: resultList]
}

Object getSvDistritosForMunicipio() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String municipioGeoId = (String) ctx('municipioGeoId')
    List<EntityValue> assocList = ec.entity.find('moqui.basic.GeoAssoc')
            .condition('geoId', municipioGeoId)
            .condition('geoAssocTypeEnumId', 'GAT_REGIONS').list()
    List resultList = []
    for (EntityValue assoc in assocList) {
        EntityValue geo = ec.entity.find('moqui.basic.Geo').condition('geoId', assoc.toGeoId).one()
        if (geo?.geoTypeEnumId == 'GEOT_DISTRICT') {
            resultList.add([value: geo.geoId, label: geo.geoNameLocal ?: geo.geoName])
        }
    }
    resultList.sort { it.label }
    return [resultList: resultList]
}

Object checkDteReadiness() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    List<String> issues = []
    boolean readyForFc = true
    boolean readyForCcf = true

    EntityValue detail = ec.entity.find('mantle.party.PartyDetail').condition('partyId', partyId).one()
    if (!detail) { return [ready: false, readyForFc: false, readyForCcf: false, issues: ['Party no encontrado']] }

    String dui = null
    String nit = null
    String nrc = null
    EntityValue duiIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: 'PitDui']).one()
    EntityValue nitIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: 'PitNit']).one()
    EntityValue nrcIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: 'PitNrc']).one()
    dui = duiIdent?.idValue
    nit = nitIdent?.idValue
    nrc = nrcIdent?.idValue

    if (!dui && !nit) {
        issues.add('Falta DUI o NIT (al menos uno es requerido para Factura)')
        readyForFc = false
        readyForCcf = false
    }
    if (!nit) {
        issues.add('NIT requerido para Comprobante de Crédito Fiscal')
        readyForCcf = false
    }
    if (!nrc) {
        EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile')
                .condition('partyId', partyId).one()
        if (!profile?.nrc) {
            issues.add('NRC requerido para Comprobante de Crédito Fiscal')
            readyForCcf = false
        }
    }

    EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', partyId).one()
    if (!profile?.actividadEconomicaId) {
        issues.add('Actividad económica requerida para CCF')
        readyForCcf = false
    }

    Timestamp now = ec.user.nowTimestamp
    EntityValue address = ec.entity.find('mantle.party.contact.PartyContactMechPostalAddress')
            .condition('partyId', partyId)
            .conditionDate('fromDate', 'thruDate', now).list()?.first
    if (!address) {
        issues.add('Dirección postal requerida')
        readyForFc = false; readyForCcf = false
    } else {
        if (!address.departamentoGeoId) {
            issues.add('Departamento SV no asignado en dirección')
            readyForCcf = false
        }
        if (!address.municipioGeoId) {
            issues.add('Municipio SV no asignado en dirección')
            readyForCcf = false
        }
        if (!address.distritoGeoId) {
            issues.add('Distrito SV requerido en dirección (schema v2)')
            readyForFc = false; readyForCcf = false
        }
        if (!address.complemento && !address.address1) {
            issues.add('Complemento de dirección requerido')
            readyForCcf = false
        }
    }

    EntityValue phone = ec.entity.find('mantle.party.contact.PartyContactMechTelecomNumber')
            .condition('partyId', partyId)
            .conditionDate('fromDate', 'thruDate', now).list()?.first
    if (!phone) {
        issues.add('Teléfono requerido para DTE')
    }

    EntityValue email = ec.entity.find('mantle.party.contact.PartyContactMechInfo')
            .condition('partyId', partyId)
            .condition('contactMechTypeEnumId', 'CmtEmailAddress')
            .conditionDate('fromDate', 'thruDate', now).list()?.first
    if (!email) {
        issues.add('Correo electrónico requerido para CCF')
        readyForCcf = false
    }

    boolean ready = readyForFc || readyForCcf
    return [ready: ready, readyForFc: readyForFc, readyForCcf: readyForCcf, issues: issues]
}

Object createSvCustomer() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyType = (String) ctx('partyTypeEnumId') ?: 'PtyPerson'
    boolean isOrg = partyType == 'PtyOrganization'
    // roleTypeId permite reusar este servicio para clientes (Customer) y proveedores (Supplier).
    String roleTypeId = ((String) ctx('roleTypeId'))?.trim() ?: 'Customer'
    boolean isSupplier = roleTypeId == 'Supplier'
    String tercero = isSupplier ? 'proveedor' : 'cliente'
    String firstName = ((String) ctx('firstName'))?.trim()
    String lastName = ((String) ctx('lastName'))?.trim()
    String organizationName = ((String) (ctx('organizationName') ?: ctx('nombreComercial')))?.trim()
    String dui = ((String) ctx('dui'))?.trim()
    String nit = ((String) ctx('nit'))?.trim()
    String tipoContribuyenteEnumId = ((String) ctx('tipoContribuyenteEnumId'))?.trim()
    String depto = ((String) ctx('departamentoGeoId'))?.trim()
    String mun = ((String) ctx('municipioGeoId'))?.trim()
    String dist = ((String) ctx('distritoGeoId'))?.trim()
    String comp = ((String) ctx('complemento'))?.trim()

    if (isOrg) {
        if (!organizationName) ec.message.addError('Razón social o nombre comercial es requerido')
        if (!nit) ec.message.addError("NIT es requerido para ${tercero} contribuyente".toString())
    } else {
        if (!firstName && !lastName) ec.message.addError('Nombre o apellido es requerido')
        if (!dui && !nit) ec.message.addError("DUI o NIT es requerido para ${tercero} persona".toString())
    }
    if (!depto) ec.message.addError('Departamento SV es requerido')
    if (!mun) ec.message.addError('Municipio SV es requerido')
    if (!dist) ec.message.addError('Distrito SV es requerido')
    if (!comp) ec.message.addError('Dirección es requerida')

    if (dui) {
        Map duiResult = validateDuiValue(dui)
        if (!duiResult.valid) ec.message.addError((String) duiResult.message)
    }
    if (nit) {
        Map nitResult = validateNitValue(nit)
        if (!nitResult.valid) ec.message.addError((String) nitResult.message)
    }
    if (tipoContribuyenteEnumId) {
        Set<String> svClassIds = ['TcGrande', 'TcMediano', 'TcOtros', 'TcExcluido'] as Set
        if (!svClassIds.contains(tipoContribuyenteEnumId)) {
            ec.message.addError("Tipo de contribuyente SV inválido: ${tipoContribuyenteEnumId}")
        }
    }
    if (ec.message.hasError()) return [:]

    Map createParams = [roleTypeId: roleTypeId]
    String partyId
    if (isOrg) {
        createParams.organizationName = organizationName
        Map result = ec.service.sync().name('mantle.party.PartyServices.create#Organization')
                .parameters(createParams).call()
        partyId = (String) result.partyId
    } else if (isSupplier) {
        // create#PersonCustomer fuerza el rol Customer; para proveedores usamos create#Person + rol Supplier.
        createParams.firstName = firstName
        createParams.lastName = lastName
        Map result = ec.service.sync().name('mantle.party.PartyServices.create#Person')
                .parameters(createParams).call()
        partyId = (String) result.partyId
    } else {
        createParams.firstName = firstName
        createParams.lastName = lastName
        Map result = ec.service.sync().name('mantle.party.PartyServices.create#PersonCustomer')
                .parameters(createParams).call()
        partyId = (String) result.partyId
    }
    if (!partyId) { ec.message.addError('No se pudo crear el tercero'); return [:] }

    // Los dropdowns opcionales mandan "" cuando no se selecciona nada; normalizar a null
    // evita violar la FK a SvEconomicActivity (actividadEconomicaId) y enums vacíos.
    String actividadEconomicaId = ((String) ctx('actividadEconomicaId'))?.trim() ?: null
    String tipoContrib = ((String) ctx('tipoContribuyenteEnumId'))?.trim() ?: null
    ec.service.sync().name('sv.localization.PartyFiscalServices.upsert#PartyFiscalProfile').parameters([
            partyId: partyId,
            dui: ((String) ctx('dui'))?.trim() ?: null,
            nit: ((String) ctx('nit'))?.trim() ?: null,
            nrc: ((String) ctx('nrc'))?.trim() ?: null,
            tipoContribuyenteEnumId: tipoContrib,
            actividadEconomicaId: actividadEconomicaId,
            nombreComercial: ((String) ctx('nombreComercial'))?.trim() ?: null
    ]).call()
    if (ec.message.hasError()) return [:]

    if (depto && mun && comp) {
        EntityValue munGeo = ec.entity.find('moqui.basic.Geo').condition('geoId', mun).one()
        Map storeResult = ec.service.sync().name('mantle.party.ContactServices.store#PartyContactInfo').parameters([
                partyId: partyId,
                postalContactMechPurposeId: 'PostalPrimary',
                countryGeoId: 'SLV',
                stateProvinceGeoId: depto,
                address1: comp,
                city: munGeo?.geoNameLocal ?: munGeo?.geoName ?: '',
                postalCode: '0000'
        ]).call()
        String newCmId = (String) storeResult.postalContactMechId
        if (newCmId) {
            EntityValue addr = ec.entity.find('mantle.party.contact.PostalAddress').condition('contactMechId', newCmId).one()
            if (addr) {
                addr.departamentoGeoId = depto
                addr.municipioGeoId = mun
                addr.distritoGeoId = dist
                addr.complemento = comp
                addr.update()
            }
        }
    }

    String tel = ((String) ctx('telefono'))?.trim()
    String email = ((String) ctx('correo'))?.trim()
    if (tel || email) {
        ec.service.sync().name('sv.localization.PartyFiscalServices.store#SvDteContactInfo').parameters([
                partyId: partyId, telefono: tel, correo: email
        ]).call()
    }

    return [partyId: partyId]
}

Object suggestTipoDte() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')

    EntityValue nitIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: 'PitNit']).one()
    EntityValue nrcIdent = ec.entity.find('mantle.party.PartyIdentification')
            .condition([partyId: partyId, partyIdTypeEnumId: 'PitNrc']).one()
    EntityValue profile = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', partyId).one()

    String nit = nitIdent?.idValue
    String nrc = nrcIdent?.idValue ?: profile?.nrc
    String tipoContrib = profile?.tipoContribuyenteEnumId

    if (tipoContrib == 'TcExcluido') {
        return [suggestedTipoDte: '14', suggestedLabel: 'Factura Sujeto Excluido', reason: 'Sujeto excluido']
    }
    if (nit && nrc && profile?.actividadEconomicaId) {
        return [suggestedTipoDte: '03', suggestedLabel: 'Comprobante de Crédito Fiscal', reason: 'Tiene NIT + NRC + actividad']
    }
    return [suggestedTipoDte: '01', suggestedLabel: 'Factura', reason: nit ? 'Sin NRC o actividad' : 'Solo DUI o sin identificación fiscal']
}

// =====================================================================
// Logo de organización: upload + remove para PDFs DTE
// =====================================================================

Object uploadOrganizationLogo() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    Object logoFile = ctx('logoFile')
    if (logoFile == null) {
        ec.message.addError('No se recibió archivo de logo')
        return [:]
    }

    long size = 0L
    String filename = null
    byte[] bytes = null
    try {
        // FileItem (commons-fileupload) o similar
        size = ((Number) logoFile.getClass().getMethod('getSize').invoke(logoFile)).longValue()
        filename = (String) logoFile.getClass().getMethod('getName').invoke(logoFile)
        bytes = (byte[]) logoFile.getClass().getMethod('get').invoke(logoFile)
    } catch (Throwable t) {
        ec.message.addError("No se pudo leer el archivo: ${t.message}")
        return [:]
    }
    if (!bytes || size == 0L) {
        ec.message.addError('El archivo está vacío')
        return [:]
    }
    if (size > 1_048_576L) {
        ec.message.addError("Logo demasiado grande (${size} bytes). Máximo 1 MB.")
        return [:]
    }

    String ext = 'png'
    if (filename) {
        String lower = filename.toLowerCase()
        if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) ext = 'jpg'
        else if (lower.endsWith('.png')) ext = 'png'
        else if (lower.endsWith('.gif')) ext = 'gif'
    }
    String safeName = "${partyId}.${ext}"
    String uploadDir = ec.resource.getLocationReference('component://moqui-sv-localization/upload/logos').uri.path
    File dir = new File(uploadDir)
    if (!dir.exists()) dir.mkdirs()
    // Limpiar versiones previas con otras extensiones
    ['png', 'jpg', 'gif'].each { String e ->
        File old = new File(dir, "${partyId}.${e}")
        if (old.exists() && e != ext) old.delete()
    }
    File target = new File(dir, safeName)
    target.bytes = bytes

    String contentLocation = "file://${target.absolutePath}"

    // Persistir en SvFiscalProfile
    EntityValue fp = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', partyId).useCache(false).one()
    if (fp == null) {
        String id = ec.entity.sequencedIdPrimary('sv.localization.party.SvFiscalProfile', null, null)
        ec.entity.makeValue('sv.localization.party.SvFiscalProfile').setAll([
                svFiscalProfileId: id, partyId: partyId, logoContentLocation: contentLocation
        ]).create()
    } else {
        fp.logoContentLocation = contentLocation
        fp.update()
    }

    return [logoContentLocation: contentLocation, filename: safeName, size: size]
}

Object removeOrganizationLogo() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String partyId = (String) ctx('partyId')
    EntityValue fp = ec.entity.find('sv.localization.party.SvFiscalProfile')
            .condition('partyId', partyId).useCache(false).one()
    if (fp == null || !fp.logoContentLocation) return [removed: false]
    String loc = fp.logoContentLocation as String
    fp.logoContentLocation = null
    fp.update()
    if (loc.startsWith('file://')) {
        try {
            File f = new File(loc.substring('file://'.length()))
            if (f.exists()) f.delete()
        } catch (Throwable ignored) { }
    }
    return [removed: true]
}
