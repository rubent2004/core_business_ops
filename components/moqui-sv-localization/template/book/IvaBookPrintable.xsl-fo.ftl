<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro m value="">${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(((value!0)?number)?string["0.00"], false)}</#macro>
<#assign entries = ivaBookEntryList![]>
<#assign totals = ivaBookTotals!{}>
<#assign title = ivaBookTitle!"Libro IVA">
<#assign partyLabel = (ivaBookTypeEnumId == "SvBookPurchases")?then("Proveedor", "Receptor")>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="8pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter-landscape" page-width="11in" page-height="8.5in"
                               margin-top="0.35in" margin-bottom="0.35in" margin-left="0.35in" margin-right="0.35in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter-landscape">
        <fo:flow flow-name="xsl-region-body">
            <fo:block font-size="14pt" font-weight="bold" text-align="center" space-after="2pt"><@x title/></fo:block>
            <fo:block text-align="center" font-size="7pt" color="#555555" space-after="8pt">
                Reporte fiscal operativo regenerado desde SvIvaBookEntry. No es formato oficial certificado MH/DGI.
            </fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="34%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Organizacion: <@x organizationPartyId/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Periodo: <@x periodYearMonth/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Generado: <@x generatedAt/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" space-after="3pt">Detalle</fo:block>
            <fo:table table-layout="fixed" width="100%" font-size="6.7pt" space-after="8pt">
                <fo:table-column column-width="4%"/>
                <fo:table-column column-width="7%"/>
                <fo:table-column column-width="5%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="18%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-column column-width="8%"/>
                <fo:table-header>
                    <fo:table-row background-color="#eeeeee">
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>#</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Fecha</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Tipo</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Documento</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block><@x partyLabel/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Exento</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">No sujeto</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Gravado</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">IVA</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Retenido</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Percibido</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Total</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-header>
                <fo:table-body>
                    <#list entries as entry>
                        <fo:table-row>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x entry.numeroCorrelativo/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x entry.fechaEmision/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x entry.tipoDteCode/><#if (entry.dteSourceStatusId!"") == "DteMockAccepted"> *</#if></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x entry.nitNrcCounterParty/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x entry.nombreCounterParty/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.montoExento/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.montoNoSujeto/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.montoGravado/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.iva/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.iva1pctRetenido/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.iva1pctPercibido/></fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m entry.total/></fo:block></fo:table-cell>
                        </fo:table-row>
                    </#list>
                    <fo:table-row background-color="#f5f5f5">
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999" number-columns-spanned="5"><fo:block font-weight="bold" text-align="right">TOTALES</fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.montoExento/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.montoNoSujeto/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.montoGravado/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.iva/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.iva1pctRetenido/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.iva1pctPercibido/></fo:block></fo:table-cell>
                        <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right"><@m totals.total/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt">
                Entradas: <@x totals.entryCount/>. Las notas de credito se presentan con signo negativo.
                Las filas marcadas con * provienen de DTE aceptados por simulador (mock), no de DTE reales sellados por MH.
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
