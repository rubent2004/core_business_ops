import org.moqui.context.ExecutionContext

/* ------------------------------------------------------------------ */
/* Helpers (mismo estilo script-binding que el resto del componente)   */
/* ------------------------------------------------------------------ */
Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext ec() { (ExecutionContext) ctx('ec') }

/** Normaliza roleIds: acepta List, coma-separado o null. */
List<String> normRoles(Object raw) {
    if (raw == null) return []
    if (raw instanceof List) return raw.findAll { it }.collect { it.toString().trim() }.findAll { it }
    return raw.toString().split(',').collect { it.trim() }.findAll { it }
}

/** Normaliza IDs recibidos desde checkbox/dropdown multi. */
List<String> normIds(Object raw) { normRoles(raw).collect { it.trim() }.findAll { it } }

String normalizeSvRoleId(String userGroupId, String roleCode) {
    String raw = (userGroupId ?: roleCode ?: '').trim()
    if (!raw) return null
    String compact = raw.replaceAll('[^A-Za-z0-9_]', '')
    if (!compact) return null
    if (!compact.toLowerCase().startsWith('sv')) compact = "Sv${compact.capitalize()}"
    if (!compact.startsWith('Sv')) compact = "Sv${compact.substring(2)}"
    return compact
}

boolean isManagedSvRole(def userGroup) {
    if (!userGroup) return false
    String userGroupId = userGroup.userGroupId as String
    return userGroupId?.startsWith('Sv') || userGroup.groupTypeEnumId == 'UgtMarbleErp'
}

String defaultActionForArtifactGroup(String artifactGroupId, String description) {
    String id = artifactGroupId ?: ''
    String text = ((description ?: '') + ' ' + id).toLowerCase()
    if (id == 'MarbleErpApp' || id == 'SvMyAccount') return 'AUTHZA_VIEW'
    if (id.startsWith('SvScreen')) return 'AUTHZA_VIEW'
    if (text.contains('reporte') || text.contains('report') || text.contains('pdf') ||
            text.contains('lectura') || text.contains('read-only') || text.contains('solo lectura') ||
            text.contains('busqueda') || text.contains('búsqueda')) return 'AUTHZA_VIEW'
    if (id.endsWith('Entities') || id.contains('Read') || id.contains('Reports')) return 'AUTHZA_VIEW'
    return 'AUTHZA_ALL'
}

String actionLabel(String authzActionEnumId) {
    authzActionEnumId == 'AUTHZA_VIEW' ? 'Ver / consultar' : 'Operar / modificar'
}

String categoryForArtifactGroup(String artifactGroupId, String description) {
    String id = artifactGroupId ?: ''
    String desc = description ?: ''
    int dash = desc.indexOf('—')
    if (dash > 0) return desc.substring(0, dash).replace('SV', '').trim() ?: 'General'
    if (id.contains('Dte')) return 'DTE'
    if (id.contains('Acctg') || id.contains('Iva')) return 'Contabilidad'
    if (id.contains('Payroll')) return 'RRHH'
    if (id.contains('Manufacturing') || id.contains('Mfg')) return 'Produccion'
    if (id.contains('Inventory') || id.contains('Shipping') || id.contains('Shipment')) return 'Bodega'
    if (id.contains('Order') || id.contains('Customer') || id.contains('Supplier')) return 'Comercial'
    if (id.contains('Admin')) return 'Administracion'
    if (id.startsWith('SimpleScreens')) return 'Mantle ERP'
    return 'General'
}

String labelForArtifactGroup(String artifactGroupId, String description) {
    String desc = description ?: artifactGroupId
    int dash = desc.indexOf('—')
    return dash > 0 ? desc.substring(dash + 1).trim() : desc
}

Set<String> manageableArtifactGroupIds(ExecutionContext ec) {
    Set<String> roleIds = new LinkedHashSet<>()
    ec.entity.find('moqui.security.UserGroup').list().each { ug ->
        if (isManagedSvRole(ug)) roleIds.add(ug.userGroupId as String)
    }

    Set<String> ids = new LinkedHashSet<>()
    ec.entity.find('moqui.security.ArtifactAuthz').list().each { authz ->
        if (roleIds.contains(authz.userGroupId as String) && authz.artifactGroupId) {
            ids.add(authz.artifactGroupId as String)
        }
    }
    ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', 'like', 'Sv%').list().each { ag ->
        ids.add(ag.artifactGroupId as String)
    }
    ids.add('MarbleErpApp')
    return ids
}

String authzIdFor(ExecutionContext ec, String userGroupId, String artifactGroupId) {
    String raw = "${userGroupId}_${artifactGroupId}".replaceAll('[^A-Za-z0-9_]', '_')
    if (raw.length() <= 60) return raw
    long hash = (raw.hashCode() as long) & 0xffffffffL
    return "SvRole_${Long.toString(hash, 16)}"
}

boolean checkedValue(Object raw) {
    String value = raw?.toString()
    return value in ['Y', 'true', 'on', '1']
}

/** Crea la membresía a un grupo sólo si no existe una vigente. Asume authz deshabilitado. */
void ensureMember(ExecutionContext ec, String userGroupId, String userId) {
    if (!userGroupId || !userId) return
    def existing = ec.entity.find('moqui.security.UserGroupMember')
            .condition('userId', userId)
            .condition('userGroupId', userGroupId)
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp)
            .list()
    if (existing) return
    ec.service.sync().name('create#moqui.security.UserGroupMember')
            .parameters([userGroupId: userGroupId, userId: userId, fromDate: ec.user.nowTimestamp])
            .call()
}

/* ------------------------------------------------------------------ */
/* create#SvUserForEmployee                                            */
/* ------------------------------------------------------------------ */
Map createSvUserForEmployee() {
    ExecutionContext ec = ec()
    String username = ((String) ctx('username'))?.trim()
    String newPassword = (String) ctx('newPassword')
    String newPasswordVerify = (String) ctx('newPasswordVerify')
    String requirePasswordChange = ((String) ctx('requirePasswordChange')) ?: 'Y'
    String emailAddress = ((String) ctx('emailAddress'))?.trim() ?: null
    String userFullName = (String) ctx('userFullName')
    String partyId = ((String) ctx('partyId'))?.trim() ?: null
    List<String> roleIds = normRoles(ctx('roleIds'))
    String roleId = (String) ctx('roleId')
    if (roleId && !roleIds.contains(roleId)) roleIds.add(roleId)

    if (!username) { ec.message.addError('El nombre de usuario es obligatorio'); return [:] }
    if (!newPassword || newPassword != newPasswordVerify) {
        ec.message.addError('Las contraseñas no coinciden'); return [:]
    }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        Map createOut = ec.service.sync().name('org.moqui.impl.UserServices.create#UserAccount')
                .parameters([username: username, newPassword: newPassword,
                             newPasswordVerify: newPasswordVerify,
                             requirePasswordChange: requirePasswordChange,
                             emailAddress: emailAddress]).call()
        if (ec.message.hasError()) return [:]
        String userId = (String) createOut.userId

        ec.service.sync().name('org.moqui.impl.UserServices.update#UserAccount')
                .parameters([userId: userId, userFullName: userFullName, partyId: partyId,
                             locale: 'es_SV', timeZone: 'America/El_Salvador']).call()

        ensureMember(ec, 'ALL_USERS', userId)
        for (String rid in roleIds) ensureMember(ec, rid, userId)

        ec.message.addMessage("Usuario ${username} creado correctamente")
        return [userId: userId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* create#SvRole                                                       */
/* ------------------------------------------------------------------ */
Map createSvRole() {
    ExecutionContext ec = ec()
    String userGroupId = normalizeSvRoleId((String) ctx('userGroupId'), (String) ctx('roleCode'))
    String description = ((String) ctx('description'))?.trim()
    if (!userGroupId) { ec.message.addError('Codigo de rol requerido'); return [:] }
    if (userGroupId.length() > 60) { ec.message.addError('El codigo del rol es demasiado largo'); return [:] }
    if (!description) { ec.message.addError('Descripcion del rol requerida'); return [:] }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        def existing = ec.entity.find('moqui.security.UserGroup').condition('userGroupId', userGroupId).one()
        if (existing) { ec.message.addError("Ya existe el rol ${userGroupId}"); return [:] }
        ec.entity.makeValue('moqui.security.UserGroup')
                .setAll([userGroupId: userGroupId, groupTypeEnumId: 'UgtMarbleErp', description: description])
                .create()
        ec.message.addMessage("Rol ${userGroupId} creado")
        return [userGroupId: userGroupId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* update#SvRole                                                       */
/* ------------------------------------------------------------------ */
Map updateSvRole() {
    ExecutionContext ec = ec()
    String userGroupId = ((String) ctx('userGroupId'))?.trim()
    String description = ((String) ctx('description'))?.trim()
    if (!userGroupId) { ec.message.addError('userGroupId requerido'); return [:] }
    if (!description) { ec.message.addError('Descripcion del rol requerida'); return [:] }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        def role = ec.entity.find('moqui.security.UserGroup').condition('userGroupId', userGroupId).one()
        if (!isManagedSvRole(role)) { ec.message.addError("Rol no administrable desde esta pantalla: ${userGroupId}"); return [:] }
        role.description = description
        role.groupTypeEnumId = role.groupTypeEnumId ?: 'UgtMarbleErp'
        role.ipAllowed = ((String) ctx('ipAllowed'))?.trim() ?: null
        role.requireAuthcFactor = ((String) ctx('requireAuthcFactor')) ?: 'N'
        role.update()
        ec.message.addMessage('Rol actualizado')
        return [userGroupId: userGroupId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* get#SvRolePermissionCatalog                                         */
/* ------------------------------------------------------------------ */
Map getSvRolePermissionCatalog() {
    ExecutionContext ec = ec()
    String userGroupId = ((String) ctx('userGroupId'))?.trim()
    Set<String> manageableIds = manageableArtifactGroupIds(ec)

    Map<String, Object> currentAuthzByGroup = [:]
    if (userGroupId) {
        ec.entity.find('moqui.security.ArtifactAuthz').condition('userGroupId', userGroupId).list().each { authz ->
            if (authz.artifactGroupId && !currentAuthzByGroup.containsKey(authz.artifactGroupId as String)) {
                currentAuthzByGroup[authz.artifactGroupId as String] = authz
            }
        }
    }

    List<Map> permissionList = []
    manageableIds.each { String artifactGroupId ->
        def group = ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', artifactGroupId).one()
        if (!group) return
        String description = group.description ?: artifactGroupId
        def current = currentAuthzByGroup[artifactGroupId]
        String action = (current?.authzActionEnumId ?: defaultActionForArtifactGroup(artifactGroupId, description)) as String
        String category = categoryForArtifactGroup(artifactGroupId, description)
        boolean sensitive = artifactGroupId in ['SvScreenAdmin', 'SvUserAdminServices']
        String tooltip = "${description}. Tecnico: ArtifactGroup ${artifactGroupId}; al activar se crea ArtifactAuthz para este rol. Nivel sugerido: ${actionLabel(action)}."
        if (sensitive) tooltip += ' Alto impacto: permite administrar usuarios, roles o permisos.'
        permissionList.add([
                artifactGroupId   : artifactGroupId,
                permissionEnabled : current ? 'Y' : 'N',
                enabled           : current != null,
                category          : category,
                label             : labelForArtifactGroup(artifactGroupId, description),
                description       : description,
                tooltip           : tooltip,
                authzActionEnumId : action,
                actionLabel       : actionLabel(action),
                sensitiveLabel    : sensitive ? 'Alto impacto' : ''
        ])
    }
    permissionList.sort { a, b ->
        (a.category <=> b.category) ?: (a.label <=> b.label) ?: (a.artifactGroupId <=> b.artifactGroupId)
    }
    return [permissionList: permissionList]
}

/* ------------------------------------------------------------------ */
/* set#SvRolePermissions                                               */
/* ------------------------------------------------------------------ */
Map setSvRolePermissions() {
    ExecutionContext ec = ec()
    String userGroupId = ((String) ctx('userGroupId'))?.trim()
    if (!userGroupId) {
        userGroupId = (ec.web.parameters.get('userGroupId_0') ?: ec.web.parameters.get('userGroupId'))?.toString()
    }
    if (!userGroupId) { ec.message.addError('userGroupId requerido'); return [:] }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        def role = ec.entity.find('moqui.security.UserGroup').condition('userGroupId', userGroupId).one()
        if (!isManagedSvRole(role)) { ec.message.addError("Rol no administrable desde esta pantalla: ${userGroupId}"); return [:] }

        Set<String> manageableIds = manageableArtifactGroupIds(ec)
        Set<String> desiredIds = new LinkedHashSet<>()
        Object explicitIds = ctx('artifactGroupIds')
        if (explicitIds != null) {
            desiredIds.addAll(normIds(explicitIds))
        } else {
            int index = 0
            while (true) {
                Object artifactGroupParam = ec.web.parameters.get("artifactGroupId_${index}")
                Object userGroupParam = ec.web.parameters.get("userGroupId_${index}")
                if (artifactGroupParam == null && userGroupParam == null) break

                String artifactGroupId = artifactGroupParam?.toString()
                if (artifactGroupId && checkedValue(ec.web.parameters.get("permissionEnabled_${index}"))) {
                    desiredIds.add(artifactGroupId)
                }
                index++
            }
        }
        desiredIds = desiredIds.findAll { manageableIds.contains(it) } as Set<String>
        if (userGroupId == 'SvAdmin') {
            desiredIds.add('SvScreenAdmin')
            desiredIds.add('SvUserAdminServices')
        }

        Map<String, List> currentByGroup = [:].withDefault { [] }
        ec.entity.find('moqui.security.ArtifactAuthz').condition('userGroupId', userGroupId).list().each { authz ->
            if (authz.artifactGroupId && manageableIds.contains(authz.artifactGroupId as String)) {
                currentByGroup[authz.artifactGroupId as String].add(authz)
            }
        }

        int enabled = 0
        int disabled = 0
        currentByGroup.each { String artifactGroupId, List authzRows ->
            if (!desiredIds.contains(artifactGroupId)) {
                authzRows.each { it.delete(); disabled++ }
            }
        }

        desiredIds.each { String artifactGroupId ->
            def group = ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', artifactGroupId).one()
            if (!group) return
            String action = defaultActionForArtifactGroup(artifactGroupId, group.description as String)
            String authzType = userGroupId == 'SvAdmin' ? 'AUTHZT_ALWAYS' : 'AUTHZT_ALLOW'
            List rows = currentByGroup[artifactGroupId]
            def authz = rows ? rows[0] : null
            if (authz) {
                authz.authzTypeEnumId = authzType
                authz.authzActionEnumId = action
                authz.update()
                if (rows.size() > 1) rows.drop(1).each { it.delete() }
            } else {
                ec.entity.makeValue('moqui.security.ArtifactAuthz')
                        .setAll([artifactAuthzId: authzIdFor(ec, userGroupId, artifactGroupId),
                                 userGroupId: userGroupId, artifactGroupId: artifactGroupId,
                                 authzTypeEnumId: authzType, authzActionEnumId: action])
                        .create()
            }
            enabled++
        }

        ec.message.addMessage("Permisos actualizados para ${userGroupId}: ${enabled} activos, ${disabled} desactivados")
        return [userGroupId: userGroupId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* update#SvUserAccount                                                */
/* ------------------------------------------------------------------ */
Map updateSvUserAccount() {
    ExecutionContext ec = ec()
    String userId = (String) ctx('userId')
    if (!userId) { ec.message.addError('userId requerido'); return [:] }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        ec.service.sync().name('org.moqui.impl.UserServices.update#UserAccount')
                .parameters([userId: userId,
                             userFullName: ctx('userFullName'),
                             emailAddress: ((String) ctx('emailAddress'))?.trim() ?: null,
                             partyId: ((String) ctx('partyId'))?.trim() ?: null,
                             disabled: ((String) ctx('disabled')) ?: 'N']).call()
        if (!ec.message.hasError()) ec.message.addMessage('Cuenta actualizada')
        return [userId: userId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* set#SvUserRoles — reconcilia las membresías Sv* del usuario          */
/* ------------------------------------------------------------------ */
Map setSvUserRoles() {
    ExecutionContext ec = ec()
    String userId = (String) ctx('userId')
    List<String> roleIds = normRoles(ctx('roleIds'))
    if (!userId) { ec.message.addError('userId requerido'); return [:] }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        def currentList = ec.entity.find('moqui.security.UserGroupMember')
                .condition('userId', userId)
                .condition('userGroupId', 'like', 'Sv%')
                .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp)
                .list()
        // expira roles que ya no se desean
        for (def ugm in currentList) {
            if (!roleIds.contains(ugm.userGroupId)) {
                ugm.thruDate = ec.user.nowTimestamp
                ugm.update()
            }
        }
        // agrega los nuevos
        for (String rid in roleIds) ensureMember(ec, rid, userId)

        if (!ec.message.hasError()) ec.message.addMessage('Roles actualizados')
        return [userId: userId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}

/* ------------------------------------------------------------------ */
/* set#SvUserPassword — reseteo administrativo (usa ADMIN_PASSWORD)      */
/* ------------------------------------------------------------------ */
Map setSvUserPassword() {
    ExecutionContext ec = ec()
    String userId = (String) ctx('userId')
    String newPassword = (String) ctx('newPassword')
    String newPasswordVerify = (String) ctx('newPasswordVerify')
    String requirePasswordChange = ((String) ctx('requirePasswordChange')) ?: 'Y'
    if (!userId) { ec.message.addError('userId requerido'); return [:] }
    if (!newPassword || newPassword != newPasswordVerify) {
        ec.message.addError('Las contraseñas no coinciden'); return [:]
    }

    boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
    try {
        // oldPassword se ignora porque SvAdmin tiene el permiso ADMIN_PASSWORD
        ec.service.sync().name('org.moqui.impl.UserServices.update#Password')
                .parameters([userId: userId, oldPassword: 'ignored',
                             newPassword: newPassword, newPasswordVerify: newPasswordVerify]).call()
        if (ec.message.hasError()) return [:]
        ec.service.sync().name('org.moqui.impl.UserServices.update#UserAccount')
                .parameters([userId: userId, requirePasswordChange: requirePasswordChange]).call()
        ec.message.addMessage('Contraseña restablecida')
        return [userId: userId]
    } finally {
        if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
    }
}
