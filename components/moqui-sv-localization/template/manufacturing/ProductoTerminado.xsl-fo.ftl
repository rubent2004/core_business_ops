<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro qty value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.##"), false)}<#else>0</#if></#macro>
<#macro money value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.00"), false)}<#else>0.00</#if></#macro>
<#assign run = svWorkEffort!{}>
<#assign produceList = svProduceList![]>
<#assign receiptList = svReceiptList![]>
<#assign cost = svCost!{}>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.55in" margin-bottom="0.55in" margin-left="0.6in" margin-right="0.6in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">

            <fo:block font-size="16pt" font-weight="bold" text-align="center" space-after="2pt">HOJA DE PRODUCTO TERMINADO</fo:block>
            <fo:block text-align="center" font-size="8pt" color="#555555" space-after="2pt">Registro de recepcion de producto terminado al cierre de la orden</fo:block>
            <fo:block text-align="center" font-size="8pt" color="#555555" space-after="12pt">Metalúrgica del Pacífico</fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">N° ORDEN</fo:block>
                            <fo:block><@x run.workEffortId!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">ESTADO</fo:block>
                            <fo:block><@x run.statusId!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">FECHA CIERRE</fo:block>
                            <fo:block><@x run.actualCompletionDate!run.estimatedCompletionDate!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">N° RECEPCIONES</fo:block>
                            <fo:block>${receiptList?size}</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Producto terminado</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="14pt">
                <fo:table-column column-width="18%"/>
                <fo:table-column column-width="48%"/>
                <fo:table-column column-width="17%"/>
                <fo:table-column column-width="17%"/>
                <fo:table-header>
                    <fo:table-row background-color="#dddddd">
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Codigo</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Descripcion</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Estimado</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Recibido</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-header>
                <fo:table-body>
                    <#list produceList as item>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block><@x item.pseudoId!item.productId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block><@x item.productName!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@qty item.estimatedQuantity!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@qty item.receivedQuantity!item.actualQuantity!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                    <#if !(produceList?has_content)>
                    <fo:table-row>
                        <fo:table-cell padding="6pt" number-columns-spanned="4" border="0.5pt solid #cccccc">
                            <fo:block text-align="center" color="#888888">No hay producto terminado configurado.</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                    </#if>
                </fo:table-body>
            </fo:table>

            <#if receiptList?has_content>
            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Detalle de recepciones</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="14pt" font-size="8pt">
                <fo:table-column column-width="6%"/>
                <fo:table-column column-width="20%"/>
                <fo:table-column column-width="32%"/>
                <fo:table-column column-width="14%"/>
                <fo:table-column column-width="14%"/>
                <fo:table-column column-width="14%"/>
                <fo:table-header>
                    <fo:table-row background-color="#dddddd">
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">#</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Fecha</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Bodega destino</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Cantidad</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Costo unit.</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Costo total</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-header>
                <fo:table-body>
                    <#list receiptList as r>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block>${r?index + 1}</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x r.receivedDate!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block><@x r.facilityId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right"><@qty r.quantityAccepted!r.quantityIncluded!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right">$<@money r.unitCost!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #dddddd"><fo:block text-align="right">$<@money r.totalCost!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                </fo:table-body>
            </fo:table>
            </#if>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Resumen de costos</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="14pt">
                <fo:table-column column-width="60%"/>
                <fo:table-column column-width="40%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Costo de materiales</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">$<@money cost.materials!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Mano de obra</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">$<@money cost.labor!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Gastos indirectos de fabricacion</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">$<@money cost.overhead!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row>
                        <fo:table-cell padding="6pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block font-weight="bold">COSTO TOTAL DE LA ORDEN</fo:block></fo:table-cell>
                        <fo:table-cell padding="6pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block text-align="right" font-weight="bold">$<@money cost.total!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-before="30pt">
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000">
                            <fo:block text-align="center" font-size="8pt">Entrega (Produccion)</fo:block>
                        </fo:table-cell>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000">
                            <fo:block text-align="center" font-size="8pt">Recibe (Bodega PT)</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt" space-before="20pt">
                Hoja de producto terminado generada por el sistema al cerrar la orden. Generada: <@x generatedAt!""/>
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
