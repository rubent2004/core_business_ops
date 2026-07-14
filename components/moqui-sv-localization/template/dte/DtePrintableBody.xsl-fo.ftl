<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#assign id = dteJson.identificacion!{}>
<#assign emisor = dteJson.emisor!{}>
<#assign receptor = (dteJson.receptor)!(dteJson.sujetoExcluido)!{}>
<#assign resumen = dteJson.resumen!{}>
<#assign items = dteJson.cuerpoDocumento![]>
<#assign docRel = dteJson.documentoRelacionado![]>
<#assign receptorDoc = (receptor.nit)!(receptor.numDocumento)!"">

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.45in" margin-bottom="0.45in" margin-left="0.45in" margin-right="0.45in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">
            <!-- Header: Logo emisor | Titulo + descripcion | QR verificacion MH -->
            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="22%"/>
                <fo:table-column column-width="56%"/>
                <fo:table-column column-width="22%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" display-align="center">
                            <#if emisorLogoUrl?has_content>
                                <fo:block text-align="left">
                                    <fo:external-graphic src="${emisorLogoUrl}" content-width="scale-to-fit" width="1.6in" content-height="scale-to-fit" height="0.9in" scaling="uniform"/>
                                </fo:block>
                            <#else>
                                <fo:block font-size="14pt" font-weight="bold" color="#222222"><@x emisor.nombre/></fo:block>
                                <fo:block font-size="7pt" color="#777777">NIT <@x emisor.nit/></fo:block>
                            </#if>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" display-align="center">
                            <fo:block font-size="15pt" font-weight="bold" text-align="center" space-after="3pt"><@x dteReportTitle/></fo:block>
                            <fo:block text-align="center" font-size="8pt" color="#555555">Representacion fiscal imprimible del DTE emitido</fo:block>
                            <#if verificationQrUrl?has_content>
                                <fo:block text-align="center" font-size="6pt" color="#888888" space-before="4pt">Verifique este DTE en factura.gob.sv</fo:block>
                            </#if>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" display-align="center">
                            <#if verificationQr?has_content>
                                <fo:block text-align="right">
                                    <fo:external-graphic src="${verificationQr}" content-width="1.3in" content-height="1.3in" scaling="uniform"/>
                                </fo:block>
                                <fo:block text-align="right" font-size="6pt" color="#888888">Verificacion MH</fo:block>
                            </#if>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <#if dteEstadoLeyenda?has_content>
                <fo:block text-align="center" font-size="18pt" font-weight="bold" color="#991b1b"
                          border="1.5pt solid #991b1b" padding="6pt" space-after="8pt">
                    <@x dteEstadoLeyenda/>
                </fo:block>
            </#if>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Identificacion</fo:block>
                            <fo:block>Tipo DTE: <@x id.tipoDte/></fo:block>
                            <fo:block>Version: <@x id.version/></fo:block>
                            <fo:block>Ambiente: <@x id.ambiente/></fo:block>
                            <fo:block>Numero control: <@x id.numeroControl/></fo:block>
                            <#if verificationBarcode?has_content>
                                <fo:block space-before="2pt">
                                    <fo:external-graphic src="${verificationBarcode}" content-width="2.55in" content-height="0.32in" scaling="uniform"/>
                                </fo:block>
                            </#if>
                            <fo:block>Codigo generacion: <@x id.codigoGeneracion/></fo:block>
                            <fo:block>Fecha/Hora: <@x id.fecEmi/> <@x id.horEmi/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Estado</fo:block>
                            <fo:block>Status: <@x dteEmision.statusId/></fo:block>
                            <fo:block>Sello recepcion: <@x dteEmision.selloRecepcion/></fo:block>
                            <fo:block>Fecha transmision: <@x dteEmision.fechaTransmision/></fo:block>
                            <#if dteEmision.errorMessage?has_content><fo:block>Error: <@x dteEmision.errorMessage/></fo:block></#if>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Emisor</fo:block>
                            <fo:block><@x emisor.nombre/></fo:block>
                            <fo:block>NIT: <@x emisor.nit/> NRC: <@x emisor.nrc/></fo:block>
                            <fo:block>Actividad: <@x emisor.codActividad/> - <@x emisor.descActividad/></fo:block>
                            <fo:block>Nombre comercial: <@x emisor.nombreComercial/></fo:block>
                            <fo:block>Correo: <@x emisor.correo/> Tel: <@x emisor.telefono/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Receptor</fo:block>
                            <fo:block><@x receptor.nombre/></fo:block>
                            <fo:block>NIT/DOC: <@x receptorDoc/> NRC: <@x receptor.nrc/></fo:block>
                            <fo:block>Actividad: <@x receptor.codActividad/> - <@x receptor.descActividad/></fo:block>
                            <fo:block>Nombre comercial: <@x receptor.nombreComercial/></fo:block>
                            <fo:block>Correo: <@x receptor.correo/> Tel: <@x receptor.telefono/></fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <#if docRel?has_content>
                <fo:block font-weight="bold" space-after="3pt">Documento relacionado</fo:block>
                <#list docRel as rel>
                    <fo:block space-after="6pt">Tipo: <@x rel.tipoDocumento/> Generacion: <@x rel.tipoGeneracion/> Numero: <@x rel.numeroDocumento/> Fecha: <@x rel.fechaEmision/></fo:block>
                </#list>
            </#if>

            <fo:block font-weight="bold" space-after="3pt">Detalle</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="8pt" font-size="8pt">
                <fo:table-column column-width="7%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="12%"/>
                <fo:table-column column-width="18%"/>
                <fo:table-column column-width="18%"/>
                <fo:table-header>
                    <fo:table-row background-color="#eeeeee">
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>#</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block>Descripcion</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block text-align="right">Cantidad</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block text-align="right">Precio</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block text-align="right">Monto</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-header>
                <fo:table-body>
                    <#list items as item>
                        <fo:table-row>
                            <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x item.numItem/></fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x item.descripcion/></fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@x item.cantidad/></fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@x item.precioUni/></fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@x (item.ventaGravada)!(item.compra)!(item.ventaExenta)!(item.ventaNoSuj)!"0.00"/></fo:block></fo:table-cell>
                        </fo:table-row>
                    </#list>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="65%"/>
                <fo:table-column column-width="35%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Resumen en letras</fo:block>
                            <fo:block><@x resumen.totalLetras/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <#if resumen.totalGravada?has_content><fo:block>Total gravada: <@x resumen.totalGravada/></fo:block></#if>
                            <#if resumen.totalCompra?has_content><fo:block>Total compra: <@x resumen.totalCompra/></fo:block></#if>
                            <#if resumen.subTotal?has_content><fo:block>Sub total: <@x resumen.subTotal/></fo:block></#if>
                            <#if resumen.totalIva?has_content><fo:block>IVA: <@x resumen.totalIva/></fo:block></#if>
                            <#if resumen.tributos?has_content>
                                <#list resumen.tributos as trib><fo:block><@x trib.descripcion/>: <@x trib.valor/></fo:block></#list>
                            </#if>
                            <fo:block font-weight="bold">Monto total: <@x resumen.montoTotalOperacion/></fo:block>
                            <#if resumen.totalPagar?has_content><fo:block font-weight="bold">Total pagar: <@x resumen.totalPagar/></fo:block></#if>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <#if verificationQrUrl?has_content>
                <fo:block font-size="6pt" color="#666666" text-align="center" space-before="6pt">
                    Verifique este DTE en: <@x verificationQrUrl/>
                </fo:block>
            </#if>
            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt" space-before="4pt">
                Firmado digitalmente por el emisor. Codigo generacion: <@x id.codigoGeneracion/> |
                Sello MH: <@x dteEmision.selloRecepcion/> |
                Transmision: <@x dteEmision.fechaTransmision/> |
                Procesamiento MH: <@x dteEmision.fechaProcesamientoMh/>.
                Documento generado desde DteEmision.jsonOriginal. No sustituye la validacion oficial del Ministerio de Hacienda.
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
