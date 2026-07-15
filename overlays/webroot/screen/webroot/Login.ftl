<#--
    Login CORE Business / Ops - acceso limpio y compacto.
    Mantiene toda la funcionalidad
    original de Moqui (tabs login/reset/change, 2FA, SSO).
-->
<style>
:root {
    --cf-bg-deep: #071116;
    --cf-bg-card: #0D1B22;
    --cf-bg-card-hover: #102630;
    --cf-cyan: #22D3EE;
    --cf-cyan-strong: #06B6D4;
    --cf-cyan-soft: rgba(34, 211, 238, 0.12);
    --cf-text: #E4EFF4;
    --cf-text-dim: #8FAAB5;
    --cf-border: #1E3D4A;
    --cf-border-soft: #152E39;
    --cf-error: #F87171;
}

* { box-sizing: border-box; }
html, body {
    margin: 0; padding: 0; height: 100%;
    background: var(--cf-bg-deep);
    color: var(--cf-text);
    font-family: 'Inter', 'Aptos', 'Segoe UI', system-ui, sans-serif;
    -webkit-font-smoothing: antialiased;
    overflow-x: hidden;
}
body.bg-light, body.bg-dark { background: var(--cf-bg-deep) !important; }

/* Apaga el chrome estándar de Moqui en login */
#top, .navbar, .navbar-inverse, footer { display: none !important; }
.container-fluid { padding: 0 !important; width: 100% !important; }
#content {
    margin-left: 0 !important;
    width: 100vw !important;
    min-height: 100vh !important;
    padding: 0 !important;
    background: transparent !important;
}
#content > .inner {
    min-width: 0 !important;
    padding: 0 !important;
}
#footer {
    display: none !important;
    margin-left: 0 !important;
    width: 100vw !important;
}
.row { margin: 0 !important; }

/* ── Stage ─────────────────────────────────────────────────────── */
.cf-stage {
    position: relative;
    width: 100%;
    min-height: 100vh;
    min-height: 100dvh;
    display: flex;
    align-items: center;
    justify-content: center;
    background:
        linear-gradient(90deg, #061014 0%, #071116 44%, #071116 56%, #050D11 100%);
    overflow: hidden;
    padding: 28px 16px;
}
.cf-stage::before,
.cf-stage::after {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    width: clamp(160px, 22vw, 360px);
    pointer-events: none;
    z-index: 0;
}
.cf-stage::before {
    left: 0;
    background:
        linear-gradient(90deg, rgba(3, 13, 17, 0.95), rgba(9, 33, 40, 0.72) 68%, transparent),
        repeating-linear-gradient(0deg, rgba(34, 211, 238, 0.035) 0 1px, transparent 1px 56px);
    border-right: 1px solid rgba(34, 211, 238, 0.08);
}
.cf-stage::after {
    right: 0;
    background:
        linear-gradient(270deg, rgba(3, 13, 17, 0.9), rgba(9, 33, 40, 0.48) 58%, transparent),
        repeating-linear-gradient(0deg, rgba(34, 211, 238, 0.026) 0 1px, transparent 1px 72px);
    border-left: 1px solid rgba(34, 211, 238, 0.055);
}

/* Meta arriba */
.cf-stage-meta {
    position: absolute;
    top: 22px; left: 24px;
    font-size: 11px;
    letter-spacing: 0.22em;
    text-transform: uppercase;
    color: var(--cf-text-dim);
    z-index: 3;
}
.cf-stage-meta span { color: var(--cf-cyan); font-weight: 700; }

.cf-stage-status {
    position: absolute;
    bottom: 18px; right: 24px;
    display: inline-flex; align-items: center; gap: 8px;
    padding: 6px 14px;
    background: rgba(16, 185, 129, 0.08);
    border: 1px solid rgba(16, 185, 129, 0.3);
    color: #10B981;
    border-radius: 100px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.15em;
    text-transform: uppercase;
    z-index: 3;
    white-space: nowrap;
}
.cf-stage-status::before {
    content: '';
    width: 7px; height: 7px;
    border-radius: 50%;
    background: #10B981;
}

/* ── Card ──────────────────────────────────────────────────────── */
.cf-form {
    position: relative;
    width: 100%;
    max-width: 440px;
    background: var(--cf-bg-card);
    border: 1px solid var(--cf-border);
    border-radius: 18px;
    box-shadow:
        0 22px 70px rgba(0, 0, 0, 0.42),
        0 0 0 1px rgba(34, 211, 238, 0.03) inset;
    overflow: hidden;
    z-index: 2;
}
.cf-form::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 3px;
    background: linear-gradient(90deg, var(--cf-cyan-strong), var(--cf-cyan), transparent 86%);
    z-index: 3;
}

/* Header con gradiente */
.cf-header {
    position: relative;
    min-height: 54px;
    background: linear-gradient(90deg, var(--cf-cyan-strong) 0%, var(--cf-cyan) 100%);
    border-bottom: 1px solid var(--cf-border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 20px;
    color: #FFFFFF;
}
.cf-header-title {
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.18em;
    text-transform: uppercase;
}
.cf-header-tag {
    font-size: 9.5px;
    font-weight: 600;
    letter-spacing: 0.18em;
    text-transform: uppercase;
    padding: 4px 10px;
    color: #FFFFFF;
    border: 1px solid rgba(255, 255, 255, 0.45);
    background: rgba(255, 255, 255, 0.08);
    border-radius: 100px;
}

/* Brand: ambos logos en paralelo */
.cf-brand {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 22px;
    padding: 20px 12px 8px;
}
.cf-brand-logo {
    height: 88px;
    width: 132px;
    object-fit: contain;
    display: block;
    filter: drop-shadow(0 2px 12px rgba(34, 211, 238, 0.18));
    transition: filter 0.25s ease, transform 0.25s ease;
}
.cf-brand-logo:hover {
    transform: translateY(-1px);
}
.cf-brand-divider {
    width: 1px; height: 46px;
    background: rgba(143, 170, 181, 0.24);
    opacity: 1;
    flex-shrink: 0;
}
.cf-brand-sub {
    display: block;
    font-size: 10px;
    letter-spacing: 0.22em;
    text-transform: uppercase;
    color: var(--cf-text-dim);
    text-align: center;
    margin-top: 0;
    padding: 0 24px;
}

/* Body */
.cf-body { padding: 8px 28px 26px; }
.cf-intro { text-align: center; margin: 4px 0 16px; }
.cf-intro h1 {
    font-size: 18px;
    font-weight: 700;
    margin: 0 0 4px;
    color: var(--cf-text);
    letter-spacing: -0.01em;
}
.cf-intro p {
    font-size: 12.5px;
    color: var(--cf-text-dim);
    margin: 0;
    letter-spacing: 0.02em;
}

/* Tabs */
.cf-tabs {
    display: flex;
    justify-content: center;
    gap: 4px;
    margin: 0 0 18px;
    padding: 0;
    border-bottom: 1px solid var(--cf-border);
}
.cf-tabs li { list-style: none; }
.cf-tabs a {
    display: block;
    padding: 8px 10px;
    font-size: 10.5px;
    font-weight: 700;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--cf-text-dim);
    text-decoration: none;
    border-bottom: 2px solid transparent;
    transition: color 0.15s, border-color 0.15s;
}
.cf-tabs a:hover { color: var(--cf-text); }
.cf-tabs li.active a { color: var(--cf-cyan); border-bottom-color: var(--cf-cyan); }

/* Fields */
.cf-field { position: relative; margin-bottom: 14px; }
.cf-field-label {
    display: block;
    font-size: 9.5px;
    font-weight: 700;
    letter-spacing: 0.18em;
    text-transform: uppercase;
    color: var(--cf-cyan);
    margin-bottom: 6px;
}
.cf-input, .form-control {
    width: 100% !important;
    background: var(--cf-bg-deep) !important;
    border: 1px solid var(--cf-border) !important;
    border-radius: 8px !important;
    height: 42px !important;
    padding: 0 14px !important;
    font-size: 14px !important;
    font-family: inherit !important;
    color: var(--cf-text) !important;
    outline: none !important;
    box-shadow: none !important;
    transition: border-color 0.18s, box-shadow 0.18s, background 0.18s;
}
.cf-input::placeholder, .form-control::placeholder { color: var(--cf-text-dim); letter-spacing: 0.02em; }
.cf-input:focus, .form-control:focus {
    border-color: var(--cf-cyan) !important;
    background: var(--cf-bg-card-hover) !important;
    box-shadow: 0 0 0 3px var(--cf-cyan-soft) !important;
}

/* Submit */
.cf-submit, .btn-primary, .btn-danger {
    width: 100% !important;
    margin-top: 6px;
    padding: 12px 16px !important;
    background: linear-gradient(90deg, var(--cf-cyan-strong) 0%, var(--cf-cyan) 100%) !important;
    color: #052028 !important;
    border: none !important;
    border-radius: 8px !important;
    font-family: inherit !important;
    font-size: 12.5px !important;
    font-weight: 800 !important;
    letter-spacing: 0.14em !important;
    text-transform: uppercase !important;
    cursor: pointer;
    transition: transform 0.15s ease, box-shadow 0.2s ease, filter 0.2s ease;
    box-shadow: 0 4px 14px rgba(34, 211, 238, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.25) !important;
}
.cf-submit:hover, .btn-primary:hover, .btn-danger:hover {
    transform: translateY(-1px);
    filter: brightness(1.08);
    box-shadow: 0 6px 22px rgba(34, 211, 238, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.3) !important;
}
.btn-danger {
    background: linear-gradient(90deg, #b91c1c 0%, #ef4444 100%) !important;
    color: #fff !important;
    box-shadow: 0 4px 14px rgba(239, 68, 68, 0.35) !important;
}

/* Warnings */
.text-warning { color: #fbbf24 !important; font-size: 12px; }
.text-danger { color: var(--cf-error) !important; }
.text-muted { color: var(--cf-text-dim) !important; font-size: 11.5px; letter-spacing: 0.02em; }
.text-center { text-align: center; }

/* Alerts above the form */
.alert {
    border-radius: 10px !important;
    border: 1px solid var(--cf-border) !important;
    background: rgba(248, 113, 113, 0.08) !important;
    color: var(--cf-error) !important;
    padding: 10px 14px !important;
    margin-bottom: 14px !important;
}
.alert-success { background: rgba(16, 185, 129, 0.08) !important; color: #10B981 !important; border-color: rgba(16, 185, 129, 0.3) !important; }
.alert .close { color: inherit; opacity: 0.7; }

/* Browser warning */
#browser-warning { padding: 14px; background: rgba(248, 113, 113, 0.08); border-radius: 10px; margin-bottom: 18px; }
#browser-warning h4 { font-size: 13px; }

/* Initial admin form polish */
.form-signin h3 { font-size: 18px; font-weight: 700; margin: 0 0 8px; color: var(--cf-text); }
.form-signin .form-control { margin-bottom: 8px; }

@media (max-width: 480px) {
    .cf-form { max-width: 100%; }
    .cf-stage { align-items: flex-start; padding: 64px 12px 18px; }
    .cf-stage::before, .cf-stage::after { width: 92px; opacity: 0.45; }
    .cf-stage-meta { top: 18px; left: 16px; right: 16px; font-size: 9.5px; text-align: center; }
    .cf-stage-status { display: none; }
    .cf-header { padding: 0 16px; }
    .cf-header-title { font-size: 11.5px; }
    .cf-header-tag { font-size: 8.5px; padding: 4px 8px; }
    .cf-brand { gap: 14px; padding: 18px 12px 8px; }
    .cf-brand-logo { height: 70px; width: 112px; }
    .cf-brand-divider { height: 38px; }
    .cf-brand-sub { font-size: 9px; letter-spacing: 0.14em; }
    .cf-body { padding: 8px 18px 22px; }
    .cf-tabs { gap: 0; justify-content: space-between; }
    .cf-tabs a { padding: 8px 5px; font-size: 9px; letter-spacing: 0.08em; }
}
</style>

<div class="cf-stage">

    <div class="cf-stage-meta">SARA Robotics · <span>CORE Business / Ops</span></div>

    <div class="cf-form">
        <div class="cf-header">
            <span class="cf-header-title">${ec.l10n.localize("Acceso seguro")!"Acceso Seguro"}</span>
            <span class="cf-header-tag">${ec.l10n.localize("Operativo")!"Operativo"}</span>
        </div>

        <div class="cf-brand">
            <img class="cf-brand-logo" src="${sri.buildUrl('/images/core/core-business-trans.png').url}" alt="CORE Business"/>
            <div class="cf-brand-divider"></div>
            <img class="cf-brand-logo" src="${sri.buildUrl('/images/core/core-ops-trans.png').url}" alt="CORE OPS"/>
        </div>
        <div class="cf-brand-sub">ERP · MES · Inventario · Producción · Fiscal SV</div>

        <div class="cf-body">
            <div id="browser-warning" class="hidden text-center" style="margin-bottom: 20px;">
                <h4 class="text-danger">Your browser is not supported, please use a recent version.</h4>
            </div>
            <script>
                var UA = window.navigator.userAgent.toLowerCase();
                if (UA && /msie|trident/.test(UA)) $("#browser-warning").removeClass("hidden");
            </script>

            <div class="cf-intro">
                <h1>${ec.l10n.localize("Bienvenido")!"Bienvenido"}</h1>
                <p>${ec.l10n.localize("Ingresa tus credenciales para acceder")!"Ingresa tus credenciales para acceder"}</p>
            </div>

            <ul class="cf-tabs" role="tablist">
                <li role="presentation"><a href="#login" aria-controls="login" role="tab" data-toggle="tab">${ec.l10n.localize("Login")}</a></li>
                <#if authFlowList?has_content && !authFlowList.isEmpty()><li role="presentation"><a href="#sso" aria-controls="sso" role="tab" data-toggle="tab">${ec.l10n.localize("SSO")}</a></li></#if>
                <li role="presentation"><a href="#reset" aria-controls="reset" role="tab" data-toggle="tab">${ec.l10n.localize("Reset Password")}</a></li>
                <li role="presentation"><a href="#change" aria-controls="change" role="tab" data-toggle="tab">${ec.l10n.localize("Change Password")}</a></li>
            </ul>

            <div class="tab-content">
                <div id="login" class="tab-pane active">
                    <form method="post" action="${sri.buildUrl("login").url}" id="login_form">
                        <input type="hidden" name="initialTab" value="login">
                        <div class="cf-field">
                            <label class="cf-field-label" for="login_form_username">${ec.l10n.localize("Username")}</label>
                            <input id="login_form_username" name="username" type="text" value="${(username!"")?html}"
                                    <#if username?has_content && secondFactorRequired>disabled="disabled"</#if>
                                    required="required" class="cf-input"
                                    placeholder="${ec.l10n.localize("nombre.de.usuario")!"nombre.de.usuario"}" autocomplete="username">
                        </div>
                        <#if secondFactorRequired>
                            <div class="cf-field">
                                <label class="cf-field-label" for="login_form_code">${ec.l10n.localize("Authentication Code")}</label>
                                <input id="login_form_code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code"
                                       required="required" class="cf-input"
                                       placeholder="••••••">
                            </div>
                        <#else>
                            <div class="cf-field">
                                <label class="cf-field-label" for="login_form_password">${ec.l10n.localize("Password")}</label>
                                <input id="login_form_password" name="password" type="password" required="required" class="cf-input"
                                       placeholder="••••••••" autocomplete="current-password">
                            </div>
                        </#if>
                        <button class="cf-submit" type="submit">${ec.l10n.localize("Sign in")}</button>
                        <#if expiredCredentials><p class="text-warning text-center" style="margin-top:10px;">${ec.l10n.localize("WARNING: Your password has expired")!"WARNING: Your password has expired"}</p></#if>
                        <#if passwordChangeRequired><p class="text-warning text-center" style="margin-top:10px;">${ec.l10n.localize("WARNING: Password change required")!"WARNING: Password change required"}</p></#if>
                    </form>
                </div>
                <#if authFlowList?has_content && !authFlowList.isEmpty()>
                    <div id="sso" class="tab-pane">
                        <#list authFlowList as authFlow>
                            <form method="post" action="/sso/login">
                                <input type="hidden" name="authFlowId" value="${authFlow.authFlowId}">
                                <button class="cf-submit" type="submit">${authFlow.description}</button>
                            </form>
                        </#list>
                    </div>
                </#if>
                <div id="reset" class="tab-pane">
                    <form method="post" action="${sri.buildUrl("resetPassword").url}" id="reset_form">
                        <p class="text-muted text-center">${ec.l10n.localize("Enter your username to email a reset password")}</p>
                        <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
                        <input type="hidden" name="initialTab" value="reset">
                        <div class="cf-field">
                            <label class="cf-field-label" for="reset_form_username">${ec.l10n.localize("Username")}</label>
                            <input id="reset_form_username" name="username" type="text" value="${(username!"")?html}"
                                    required="required" class="cf-input"
                                    placeholder="${ec.l10n.localize("nombre.de.usuario")!"nombre.de.usuario"}">
                        </div>
                        <button class="cf-submit btn-danger" type="submit">${ec.l10n.localize("Email Reset Password")}</button>
                    </form>
                </div>
                <div id="change" class="tab-pane">
                    <form method="post" action="${sri.buildUrl("changePassword").url}" id="change_form">
                        <p class="text-muted text-center">${ec.l10n.localize("Enter details to change your password")}</p>
                        <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
                        <input type="hidden" name="initialTab" value="change">
                        <div class="cf-field">
                            <label class="cf-field-label" for="change_form_username">${ec.l10n.localize("Username")}</label>
                            <input id="change_form_username" name="username" type="text" value="${(username!"")?html}"
                                    required="required" class="cf-input"
                                    placeholder="${ec.l10n.localize("nombre.de.usuario")!"nombre.de.usuario"}">
                        </div>
                        <#if secondFactorRequired>
                            <input type="hidden" name="oldPassword" value="ignored">
                            <div class="cf-field">
                                <label class="cf-field-label" for="change_form_code">${ec.l10n.localize("Authentication Code")}</label>
                                <input id="change_form_code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code"
                                        required="required" class="cf-input" placeholder="••••••">
                            </div>
                        <#else>
                            <div class="cf-field">
                                <label class="cf-field-label">${ec.l10n.localize("Old Password")}</label>
                                <input type="password" name="oldPassword" required="required" class="cf-input" placeholder="••••••••">
                            </div>
                        </#if>
                        <div class="cf-field">
                            <label class="cf-field-label">${ec.l10n.localize("New Password")}</label>
                            <input type="password" name="newPassword" required="required" class="cf-input" placeholder="••••••••">
                        </div>
                        <div class="cf-field">
                            <label class="cf-field-label">${ec.l10n.localize("New Password Verify")}</label>
                            <input type="password" name="newPasswordVerify" required="required" class="cf-input" placeholder="••••••••">
                        </div>
                        <button class="cf-submit btn-danger" type="submit">${ec.l10n.localize("Change Password")}</button>
                        <p class="text-muted text-center" style="margin-top:10px;">${ec.l10n.localize("Password must be at least")!"Mínimo"} ${minLength} ${ec.l10n.localize("characters")!"caracteres"} · ${minDigits} ${ec.l10n.localize("number")!"número"}<#if (minDigits > 1)>s</#if><#if (minOthers > 0)> · ${minOthers} ${ec.l10n.localize("punctuation")!"símbolo"}<#if (minOthers > 1)>s</#if></#if></p>
                    </form>
                </div>
            </div>

            <#if secondFactorRequired>
                <div style="margin-top: 14px;">
                    <p class="text-center text-muted" style="margin-bottom:8px;">${ec.l10n.localize("An authentication code is required for your account, you have these options:")}</p>
                    <ul style="padding-left:20px; color: var(--cf-text-dim); font-size: 11.5px;">
                        <#list factorTypeDescriptions as factorType><li>${factorType}</li></#list>
                    </ul>
                    <#list sendableFactors as userAuthcFactor>
                        <form method="post" action="${sri.buildUrl("sendOtp").url}" style="margin-top:8px;">
                            <input type="hidden" name="factorId" value="${userAuthcFactor.factorId}">
                            <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
                            <input type="hidden" name="initialTab" class="initial-tab">
                            <button class="cf-submit" type="submit">${ec.l10n.localize("Send code to")} ${userAuthcFactor.factorOption!}</button>
                        </form>
                    </#list>
                </div>
            </#if>

            <#if (ec.web.sessionAttributes.get("moquiPreAuthcUsername"))?has_content>
                <form method="post" action="${sri.buildUrl("removePreAuth").url}" id="remove_preauth_form" style="margin-top:14px;">
                    <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
                    <button class="cf-submit" type="submit" style="background:transparent !important; color: var(--cf-cyan) !important; border: 1px solid var(--cf-border) !important; box-shadow:none !important;">${ec.l10n.localize("Change User")}</button>
                </form>
            </#if>
        </div>
    </div>

    <div class="cf-stage-status">${ec.l10n.localize("Sistema en línea")!"Sistema en línea"}</div>
</div>

<script>
$(function () {
    $('a[data-toggle="tab"]').on('shown.bs.tab', function (e) {
        var $target = $(e.target);
        window.location.hash = $target.attr('href');
        var tabName = window.location.hash.slice(1);
        $('.initial-tab').val(tabName);
        // styling: cf-tabs uses .active on li
        $('.cf-tabs li').removeClass('active');
        $target.closest('li').addClass('active');
        <#if username?has_content && secondFactorRequired>
            if (tabName === "login") { $("#login_form_code").focus(); }
            else if (tabName === "change") { $("#change_form_code").focus(); }
            else if (tabName === "reset") { $("#reset_form_username").focus(); }
        <#else>
            if (tabName === "login") { $("#login_form_username").focus(); }
            else if (tabName === "change") { $("#change_form_username").focus(); }
            else if (tabName === "reset") { $("#reset_form_username").focus(); }
        </#if>
    });
    $('a[href="' + (location.hash || '${initialTab!"#login"}') + '"]').tab('show');
})
</script>
