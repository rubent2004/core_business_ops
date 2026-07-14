<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro m value="">${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(((value!0)?number)?string["0.00"], false)}</#macro>
<#assign ev = eventJson!{}>
<#assign id = ev.identificacion!{}>
<#assign emisor = ev.emisor!{}>
<#assign receptor = (ev.documento)!(ev.receptor)!{}>
<#assign rels = ev.documentoRelacionado![]>
<#assign cuerpo = ev.cuerpoDocumento![]>
<#assign resumen = ev.resumen!{}>
<#assign titulo = eventTitle!"Evento DTE">

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.45in" margin-bottom="0.45in" margin-left="0.45in" margin-right="0.45in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">
            <fo:block font-size="15pt" font-weight="bold" text-align="center" space-after="1pt"><@x titulo/></fo:block>
            <fo:block text-align="center" font-size="7pt" color="#555555" space-after="8pt">
                Representación gráfica del evento — Ministerio de Hacienda, El Salvador
            </fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="6pt">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold">Emisor</fo:block>
                            <fo:block><@x emisor.nombre/></fo:block>
                            <fo:block>NIT: <@x emisor.nit/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold">Receptor</fo:block>
                            <fo:block><@x receptor.nombre/></fo:block>
                            <fo:block>Doc: <@x (receptor.numDocumento)!(receptor.nit)!""/></fo:block>
                            <#if receptor.correo?has_content><fo:block>Correo: <@x receptor.correo/></fo:block></#if>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="34%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Cód. generación evento:</fo:block><fo:block font-size="7pt"><@x id.codigoGeneracion/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Fecha emisión:</fo:block><fo:block><@x id.fecEmi/> <@x id.horEmi/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Sello de recepción:</fo:block><fo:block font-size="7pt"><@x selloEvento/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <#if motivoEvento?has_content>
                <fo:block space-after="6pt"><fo:inline font-weight="bold">Motivo: </fo:inline><@x motivoEvento/></fo:block>
            </#if>

            <#if rels?has_content>
                <fo:block font-weight="bold" space-after="3pt">Documentos relacionados</fo:block>
                <fo:table table-layout="fixed" width="100%" font-size="8pt" space-after="8pt">
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="58%"/>
                    <fo:table-column column-width="30%"/>
                    <fo:table-header>
                        <fo:table-row background-color="#eeeeee">
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Tipo</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Código de generación</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Fecha emisión</fo:block></fo:table-cell>
                        </fo:table-row>
                    </fo:table-header>
                    <fo:table-body>
                        <#list rels as r>
                            <fo:table-row>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x r.tipoDocumento/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block font-size="7pt"><@x r.codigoGeneracion/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x r.fechaEmision/></fo:block></fo:table-cell>
                            </fo:table-row>
                        </#list>
                    </fo:table-body>
                </fo:table>
            </#if>

            <#if cuerpo?has_content>
                <fo:block font-weight="bold" space-after="3pt">Detalle del evento</fo:block>
                <fo:table table-layout="fixed" width="100%" font-size="8pt" space-after="8pt">
                    <fo:table-column column-width="7%"/>
                    <fo:table-column column-width="43%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="14%"/>
                    <fo:table-header>
                        <fo:table-row background-color="#eeeeee">
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>#</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block>Descripción</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">No sujeto</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Exento</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">Gravado</fo:block></fo:table-cell>
                            <fo:table-cell padding="2pt" border="0.5pt solid #999999"><fo:block text-align="right">No gravado</fo:block></fo:table-cell>
                        </fo:table-row>
                    </fo:table-header>
                    <fo:table-body>
                        <#list cuerpo as item>
                            <fo:table-row>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x item.numItem/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block><@x item.descripcion/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m item.ventaNoSuj!0/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m item.ventaExenta!0/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m item.ventaGravada!0/></fo:block></fo:table-cell>
                                <fo:table-cell padding="2pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@m item.noGravado!0/></fo:block></fo:table-cell>
                            </fo:table-row>
                        </#list>
                    </fo:table-body>
                </fo:table>
            </#if>

            <#if (resumen.montoTotalOperacion)?? || (resumen.totalPagar)??>
                <fo:block text-align="right" font-weight="bold" space-after="8pt">
                    Monto del evento: $<@m (resumen.montoTotalOperacion)!(resumen.totalPagar)!(resumen.total)!0/>
                </fo:block>
            </#if>

            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt">
                Este evento ha sido transmitido y sellado por el Ministerio de Hacienda. Debe entregarse al
                receptor junto con su archivo JSON. Conserve este documento como parte de su respaldo tributario.
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
