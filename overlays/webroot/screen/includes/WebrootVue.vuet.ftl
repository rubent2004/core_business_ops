<#--
This software is in the public domain under CC0 1.0 Universal plus a
Grant of Patent License.

To the extent possible under law, the author(s) have dedicated all
copyright and related and neighboring rights to this software to the
public domain worldwide. This software is distributed without any
warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software (see the LICENSE.md file). If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.
-->
<div id="apps-root"><#-- NOTE: webrootVue component attaches here, uses this and below for template -->
    <input type="hidden" id="confMoquiSessionToken" value="${ec.web.sessionToken}">
    <input type="hidden" id="confAppHost" value="${ec.web.getHostName(true)}">
    <input type="hidden" id="confAppRootPath" value="${ec.web.servletContext.contextPath}">
    <input type="hidden" id="confBasePath" value="${ec.web.servletContext.contextPath}/apps">
    <input type="hidden" id="confLinkBasePath" value="${ec.web.servletContext.contextPath}/vapps">
    <input type="hidden" id="confUserId" value="${ec.user.userId!''}">
    <input type="hidden" id="confLocale" value="${ec.user.locale.toLanguageTag()}">
    <input type="hidden" id="confOuterStyle" value="${ec.user.getPreference("OUTER_STYLE")!"bg-light"}">
    <#assign navbarCompList = sri.getThemeValues("STRT_HEADER_NAVBAR_COMP")>
    <#list navbarCompList! as navbarCompUrl><input type="hidden" class="confNavPluginUrl" value="${navbarCompUrl}"></#list>
    <#if hideNav! != 'true'>
    <#-- ============================================================
         CORE Business/Ops — Sidebar vertical (estilo Superset)
         Reemplaza la antigua navbar horizontal. La data sigue siendo
         navMenuList[0].subscreens (apps de primer nivel) y los botones
         globales (history, notify, dark/light, logout) bajan al footer
         de la sidebar.
         ============================================================ -->
    <aside id="cf-sidebar" class="cf-sidebar">
        <header class="cf-side-header">
            <m-link href="/apps" class="cf-brand" title="CORE Business / Ops">
                <#-- Monograma inline inspirado en logos CoreBusiness / CoreOPS:
                     circulo abierto + 2 nodos satélites. Escalable. -->
                <svg class="cf-brand-mark" viewBox="0 0 64 64" aria-hidden="true">
                    <circle cx="32" cy="32" r="22" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-dasharray="120 18" transform="rotate(-30 32 32)"/>
                    <circle cx="48" cy="20" r="5" fill="currentColor"/>
                    <circle cx="50" cy="44" r="3.5" fill="currentColor"/>
                </svg>
                <span class="cf-brand-text">
                    <span class="cf-brand-title">CORE</span>
                    <span class="cf-brand-sub">Business / Ops</span>
                </span>
            </m-link>
            <button type="button" class="cf-sidebar-toggle" onclick="window.cfToggleSidebar && window.cfToggleSidebar()" title="Contraer/expandir menú" aria-label="Contraer/expandir menú">
                <i class="fa fa-bars"></i>
            </button>
        </header>

        <nav class="cf-side-nav">
            <template v-if="navMenuList && navMenuList.length && navMenuList[0].subscreens">
                <template v-for="subscreen in navMenuList[0].subscreens">
                    <m-link :key="subscreen.pathWithParams"
                            :href="subscreen.pathWithParams"
                            class="cf-nav-item cf-nav-item-root"
                            :class="{ 'cf-active': subscreen.active }"
                            :title="subscreen.title">
                        <template v-if="subscreen.image">
                            <i v-if="subscreen.imageType === 'icon'" class="cf-nav-icon" :class="subscreen.image"></i>
                            <img v-else class="cf-nav-icon cf-nav-icon-img" :src="subscreen.image" :alt="subscreen.title">
                        </template>
                        <i v-else class="cf-nav-icon fa fa-circle-o"></i>
                        <span class="cf-nav-label">{{subscreen.title}}</span>
                    </m-link>
                    <#-- Sub-nivel agrupado por CORE BUSINESS / CORE OPS / FISCAL -->
                    <template v-if="subscreen.active && navMenuList.length > 1 && navMenuList[1].subscreens && navMenuList[1].subscreens.length">
                        <template v-for="group in cfGroupedSubscreens(navMenuList[1].subscreens)">
                            <div v-if="group.items.length" :key="'g-' + group.id" :class="'cf-nav-group cf-nav-group-' + group.id">
                                <#-- Header del grupo: solo si hay 2+ items y no es el grupo INICIO single-item -->
                                <div v-if="group.items.length > 1 || group.id !== 'home'" class="cf-nav-group-header">
                                    <span class="cf-nav-group-dot"></span>
                                    <span class="cf-nav-group-label">{{group.label}}</span>
                                    <span class="cf-nav-group-count">{{group.items.length}}</span>
                                </div>
                                <m-link v-for="sub2 in group.items"
                                        :key="sub2.pathWithParams"
                                        :href="sub2.pathWithParams"
                                        class="cf-nav-item cf-nav-item-sub"
                                        :class="{ 'cf-active': sub2.active }"
                                        :title="sub2.title">
                                    <template v-if="sub2.image">
                                        <i v-if="sub2.imageType === 'icon'" class="cf-nav-icon" :class="sub2.image"></i>
                                        <img v-else class="cf-nav-icon cf-nav-icon-img" :src="sub2.image" :alt="sub2.title">
                                    </template>
                                    <i v-else class="cf-nav-icon fa fa-angle-right"></i>
                                    <span class="cf-nav-label">{{sub2.title}}</span>
                                </m-link>
                            </div>
                        </template>
                    </template>
                </template>
            </template>
        </nav>

        <#-- ===== Footer de la sidebar: usuario + acciones globales ===== -->
        <div class="cf-side-footer">
            <div class="cf-side-actions">
                <#-- Dark/light -->
                <a href="#" @click.prevent="switchDarkLight()" class="cf-icon-btn" title="${ec.l10n.localize("Switch Dark/Light")}">
                    <i class="fa fa-adjust"></i>
                </a>
                <#-- Historial -->
                <div class="cf-icon-btn dropdown" id="history-menu">
                    <a id="history-menu-link" href="#" data-toggle="dropdown" title="${ec.l10n.localize("Screen History")}">
                        <i class="fa fa-history"></i>
                    </a>
                    <ul class="dropdown-menu dropdown-menu-right">
                        <li v-for="histItem in navHistoryList"><m-link :href="histItem.pathWithParams">
                            <template v-if="histItem.image">
                                <i v-if="histItem.imageType === 'icon'" :class="histItem.image" style="padding-right: 8px;"></i>
                                <img v-else :src="histItem.image" :alt="histItem.title" width="18" style="padding-right: 4px;">
                            </template>
                            <i v-else class="fa fa-link" style="padding-right: 8px;"></i>
                            {{histItem.title}}</m-link></li>
                    </ul>
                </div>
                <#-- Notificaciones -->
                <div class="cf-icon-btn dropdown" id="notify-history-menu">
                    <a id="notify-history-menu-link" href="#" data-toggle="dropdown" title="${ec.l10n.localize("Notify History")}">
                        <i class="fa fa-bell-o"></i>
                    </a>
                    <ul class="dropdown-menu dropdown-menu-right" @click.prevent="stopProp">
                        <li v-for="histItem in notifyHistoryList">
                            <div :class="'alert alert-' + histItem.type" @click.prevent="stopProp" role="alert"><strong>{{histItem.time}}</strong> <span>{{histItem.message}}</span></div>
                        </li>
                    </ul>
                </div>
                <#-- Selector de idioma (cambia ec.user.locale via /apps/setUserLocale) -->
                <#assign currentLocaleTag = (ec.user.locale.toLanguageTag())!"es-SV" />
                <#if currentLocaleTag?contains("es")>
                    <#assign currentLocaleShort = "ES" />
                <#elseif currentLocaleTag?contains("en")>
                    <#assign currentLocaleShort = "EN" />
                <#else>
                    <#assign currentLocaleShort = currentLocaleTag?upper_case />
                </#if>
                <div class="cf-icon-btn dropdown" id="locale-menu">
                    <a id="locale-menu-link" href="#" data-toggle="dropdown" title="${ec.l10n.localize("Cambiar idioma")!"Cambiar idioma"}">
                        <i class="fa fa-globe"></i>
                        <span class="cf-locale-current">${currentLocaleShort}</span>
                    </a>
                    <ul class="dropdown-menu dropdown-menu-right cf-locale-menu">
                        <li><a href="${sri.buildUrl('/apps/setUserLocale?locale=es_SV').url}"><span class="cf-flag">🇸🇻</span> Español (El Salvador)</a></li>
                        <li><a href="${sri.buildUrl('/apps/setUserLocale?locale=es').url}"><span class="cf-flag">🌎</span> Español</a></li>
                        <li><a href="${sri.buildUrl('/apps/setUserLocale?locale=en_US').url}"><span class="cf-flag">🇺🇸</span> English (US)</a></li>
                        <li><a href="${sri.buildUrl('/apps/setUserLocale?locale=en').url}"><span class="cf-flag">🇬🇧</span> English</a></li>
                    </ul>
                </div>
                <#-- Documentación -->
                <div class="cf-icon-btn dropdown" id="document-menu" :class="{hidden:!documentMenuList.length}">
                    <a id="document-menu-link" href="#" data-toggle="dropdown" title="Documentation">
                        <i class="fa fa-question-circle"></i>
                    </a>
                    <ul class="dropdown-menu dropdown-menu-right">
                        <li v-for="screenDoc in documentMenuList">
                            <a href="#" @click.prevent="showScreenDocDialog(screenDoc.index)">{{screenDoc.title}}</a></li>
                    </ul>
                </div>
                <#-- Spinner -->
                <div class="cf-icon-btn" :class="{ hidden: loading < 1 }"><div class="spinner small"><div>&nbsp;</div></div></div>
                <#-- QZ print options placeholder -->
                <component :is="qzVue" ref="qzVue"></component>
                <#-- Nav plugins -->
                <template v-for="navPlugin in navPlugins"><component :is="navPlugin"></component></template>
            </div>

            <#-- Calcular rol primario del usuario para mostrar abajo del nombre.
                 Toma el grupo SV con más prioridad (Admin > Facturacion > ...).
                 Si el user no está en ningún grupo SV, cae a "Usuario". -->
            <#assign userGroupSet = (ec.user.userGroupIdSet)![] />
            <#assign cfRolePriority = [
                {"id":"SvAdmin","label":"Administrador"},
                {"id":"SvFacturacion","label":"Facturación"},
                {"id":"SvContador","label":"Contador"},
                {"id":"SvRRHH","label":"Recursos Humanos"},
                {"id":"SvVentas","label":"Ventas"},
                {"id":"SvProduccion","label":"Producción"},
                {"id":"SvBodega","label":"Bodega"},
                {"id":"SvCompras","label":"Compras"},
                {"id":"SvAuditor","label":"Auditor"}
            ] />
            <#assign cfUserRoleLabel = "Usuario" />
            <#list cfRolePriority as r>
                <#if userGroupSet?seq_contains(r.id)>
                    <#assign cfUserRoleLabel = r.label />
                    <#break>
                </#if>
            </#list>
            <div class="cf-user">
                <div class="cf-user-avatar">${((ec.user.userAccount.userFullName)!'?')?substring(0,1)?upper_case}</div>
                <div class="cf-user-info">
                    <div class="cf-user-name">${(ec.user.userAccount.userFullName)!ec.user.username!''}</div>
                    <div class="cf-user-role">${cfUserRoleLabel}</div>
                </div>
                <a href="${sri.buildUrl("/Login/logout").url}" class="cf-user-logout"
                        onclick="return confirm('${ec.l10n.localize("Logout")} ${(ec.user.userAccount.userFullName)!''}?')"
                        title="${ec.l10n.localize("Logout")}">
                    <i class="fa fa-power-off"></i>
                </a>
            </div>
        </div>
    </aside>

    <#-- Breadcrumb superior compacto (sustituye al navMenuList del navbar viejo) -->
    <div id="cf-breadcrumb" v-if="navMenuList && navMenuList.length > 1">
        <template v-for="(navMenuItem, menuIndex) in navMenuList">
            <m-link v-if="menuIndex < navMenuList.length - 1" :key="menuIndex" :href="getNavHref(menuIndex)" class="cf-crumb">{{navMenuItem.title}}</m-link>
            <span v-else :key="'last-' + menuIndex" class="cf-crumb cf-crumb-current">{{navMenuItem.title}}</span>
            <i v-if="menuIndex < navMenuList.length - 1" :key="'sep-' + menuIndex" class="fa fa-angle-right cf-crumb-sep"></i>
        </template>
    </div>
    </#if>

    <div id="content"><div class="inner"><div class="container-fluid">
        <subscreens-active></subscreens-active>
    </div></div></div>

    <#if hideNav! != 'true'>
    <div id="footer" class="bg-dark">
        <div id="apps-footer-content">
            <p>Powered by SARA Robotics</p>
        </div>
    </div>
    </#if>
</div>

<script>
(function () {
    function applySidebarState(collapsed) {
        document.body.classList.toggle('cf-sidebar-collapsed', !!collapsed);
    }

    window.cfToggleSidebar = function () {
        var collapsed = !document.body.classList.contains('cf-sidebar-collapsed');
        applySidebarState(collapsed);
        try { window.localStorage.setItem('cfSidebarCollapsed', collapsed ? 'true' : 'false'); } catch (ignore) {}
    };

    try { applySidebarState(window.localStorage.getItem('cfSidebarCollapsed') === 'true'); } catch (ignore) {}
})();

/* Dashboard card click delegation — make entire card clickable (icons, description, whitespace) */
document.addEventListener('click', function(event) {
    var card = event.target.closest('.dashboard-card');
    if (!card) return;
    if (event.target.closest('input, select, textarea, button:not(.card-link), .modal, .q-dialog, .q-menu, .dropdown-menu')) return;
    var link = card.querySelector('a.card-link') || card.querySelector('button.card-link') || card.querySelector('.card-link');
    if (link) {
        if (event.target === link || link.contains(event.target)) return;
        event.preventDefault();
        event.stopPropagation();
        link.click();
    }
});
</script>

<div id="screen-document-dialog" class="modal dynamic-dialog" aria-hidden="true" style="display: none;" tabindex="-1">
    <div class="modal-dialog modal-lg">
        <div class="modal-content">
            <div class="modal-header">
                <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                <h4 class="modal-title">${ec.l10n.localize("Documentation")}</h4>
            </div>
            <div class="modal-body" id="screen-document-dialog-body">
                <div class="spinner"><div>&nbsp;</div></div>
            </div>
            <div class="modal-footer"><button type="button" class="btn btn-primary" data-dismiss="modal">${ec.l10n.localize("Close")}</button></div>
        </div>
    </div>
</div>
