<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro money value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.00"), false)}<#else>0.00</#if></#macro>
<#assign order = svOrderHeader!{}>
<#assign parts = svOrderParts![]>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.5in" margin-bottom="0.5in" margin-left="0.55in" margin-right="0.55in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">
            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="58%"/>
                <fo:table-column column-width="42%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell>
                            <fo:block font-size="16pt" font-weight="bold"><@x svOrderDocumentType!"ORDEN OPERATIVA"/></fo:block>
                            <fo:block color="#555555">Documento operativo generado desde Mantle ERP / Moqui</fo:block>
                        </fo:table-cell>
                        <fo:table-cell text-align="right">
                            <fo:block font-weight="bold">Orden <@x order.orderId/></fo:block>
                            <fo:block>Estado: <@x svOrderStatusLabel!order.statusId!""/></fo:block>
                            <fo:block>Fecha: <@x order.entryDate!order.placedDate!""/></fo:block>
                            <fo:block>Moneda: <@x svOrderCurrencyUomId!"USD"/></fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <#list parts as partWrap>
                <#assign part = partWrap.part!{}>
                <#assign vendor = partWrap.vendor!{}>
                <#assign customer = partWrap.customer!{}>
                <#assign items = partWrap.items![]>

                <fo:block font-size="11pt" font-weight="bold" background-color="#eeeeee" padding="4pt"
                          border="0.5pt solid #999999" space-before="4pt">
                    Parte <@x part.orderPartSeqId/> - <@x partWrap.partStatusLabel!part.statusId!""/>
                </fo:block>

                <fo:table table-layout="fixed" width="100%" space-after="8pt">
                    <fo:table-column column-width="50%"/>
                    <fo:table-column column-width="50%"/>
                    <fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="5pt" border="0.5pt solid #cccccc">
                                <fo:block font-weight="bold" space-after="2pt">Vendedor / Emisor</fo:block>
                                <fo:block><@x vendor.name!part.vendorPartyId!""/></fo:block>
                                <fo:block>NIT: <@x vendor.nit/></fo:block>
                                <fo:block>NRC: <@x vendor.nrc/></fo:block>
                                <#if vendor.codEstable?has_content || vendor.codPuntoVenta?has_content>
                                    <fo:block>Establecimiento/PV: <@x vendor.codEstable/> / <@x vendor.codPuntoVenta/></fo:block>
                                </#if>
                            </fo:table-cell>
                            <fo:table-cell padding="5pt" border="0.5pt solid #cccccc">
                                <fo:block font-weight="bold" space-after="2pt">Comprador / Receptor</fo:block>
                                <fo:block><@x customer.name!part.customerPartyId!""/></fo:block>
                                <fo:block>NIT: <@x customer.nit/></fo:block>
                                <fo:block>DUI: <@x customer.dui/></fo:block>
                                <fo:block>NRC: <@x customer.nrc/></fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body>
                </fo:table>

                <fo:table table-layout="fixed" width="100%" space-after="8pt">
                    <fo:table-column column-width="25%"/>
                    <fo:table-column column-width="25%"/>
                    <fo:table-column column-width="25%"/>
                    <fo:table-column column-width="25%"/>
                    <fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Tipo DTE sugerido</fo:block><fo:block><@x partWrap.tipoDteLabel/></fo:block></fo:table-cell>
                            <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Condicion</fo:block><fo:block><@x partWrap.condicionLabel/></fo:block></fo:table-cell>
                            <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Forma pago</fo:block><fo:block><@x partWrap.formaPagoLabel/></fo:block></fo:table-cell>
                            <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Bodega</fo:block><fo:block><@x part.facilityId/></fo:block></fo:table-cell>
                        </fo:table-row>
                    </fo:table-body>
                </fo:table>

                <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Lineas</fo:block>
                <fo:table table-layout="fixed" width="100%" space-after="8pt">
                    <fo:table-column column-width="9%"/>
                    <fo:table-column column-width="16%"/>
                    <fo:table-column column-width="39%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-column column-width="12%"/>
                    <fo:table-body>
                        <fo:table-row background-color="#f7f7f7">
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Item</fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Producto</fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold">Descripcion</fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold" text-align="right">Cant.</fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold" text-align="right">Precio</fo:block></fo:table-cell>
                            <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block font-weight="bold" text-align="right">Total</fo:block></fo:table-cell>
                        </fo:table-row>
                        <#list items as item>
                            <fo:table-row>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x item.orderItemSeqId/></fo:block></fo:table-cell>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x item.productId/></fo:block></fo:table-cell>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x item.description/></fo:block></fo:table-cell>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@x item.quantity/></fo:block></fo:table-cell>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@money item.unitAmount/></fo:block></fo:table-cell>
                                <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@money item.lineTotal/></fo:block></fo:table-cell>
                            </fo:table-row>
                        </#list>
                        <#if !(items?has_content)>
                            <fo:table-row>
                                <fo:table-cell padding="4pt" border="0.5pt solid #dddddd" number-columns-spanned="6">
                                    <fo:block>Esta parte no tiene lineas registradas.</fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                        </#if>
                    </fo:table-body>
                </fo:table>

                <fo:table table-layout="fixed" width="100%" space-after="10pt">
                    <fo:table-column column-width="70%"/>
                    <fo:table-column column-width="30%"/>
                    <fo:table-body>
                        <fo:table-row>
                            <fo:table-cell><fo:block></fo:block></fo:table-cell>
                            <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                                <fo:block text-align="right">Subtotal lineas: <@money partWrap.itemTotal/></fo:block>
                                <fo:block text-align="right" font-weight="bold">Total parte: <@money partWrap.displayTotal/></fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body>
                </fo:table>
            </#list>

            <fo:block text-align="right" font-size="12pt" font-weight="bold" space-before="4pt" space-after="24pt">
                Total orden: <@money svOrderTotal/> <@x svOrderCurrencyUomId!"USD"/>
            </fo:block>

            <fo:table table-layout="fixed" width="100%" space-before="22pt">
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center">Prepara</fo:block></fo:table-cell>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center">Autoriza / Recibe</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="7pt" color="#777777" text-align="right" space-before="8pt">
                Generado: <@x svOrderGeneratedAt/>
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
