<#assign tipo = (dteJson.identificacion.tipoDte)!(dteJson.identificacion.tipoEvento)!"DTE">
<#assign dteReportTitle = "DOCUMENTO TRIBUTARIO ELECTRONICO " + tipo>
<#include "component://moqui-sv-localization/template/dte/DtePrintableBody.xsl-fo.ftl">
