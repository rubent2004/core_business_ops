<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro money value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.00"), false)}<#else>0.00</#if></#macro>
<#assign run = svWorkEffort!{}>
<#assign cost = svCost!{}>
<#assign lines = cost.materialLines![]>
<#assign issuanceList = svIssuanceList![]>
<#assign receiptList = svReceiptList![]>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.55in" margin-bottom="0.55in" margin-left="0.6in" margin-right="0.6in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">
            <fo:block font-size="15pt" font-weight="bold" text-align="center" space-after="4pt">HOJA DE CIERRE Y COSTEO</fo:block>
            <fo:block text-align="center" color="#555555" space-after="12pt">Orden <@x run.workEffortId!""/> - <@x run.workEffortName!""/></fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Estado</fo:block><fo:block><@x run.statusId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Bodega</fo:block><fo:block><@x run.facilityId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Inicio</fo:block><fo:block><@x run.estimatedStartDate!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Cierre</fo:block><fo:block><@x run.actualCompletionDate!""/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Materiales</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="15%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="13%"/>
                <fo:table-column column-width="13%"/>
                <fo:table-column column-width="14%"/>
                <fo:table-body>
                    <fo:table-row background-color="#f6f6f6">
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Codigo</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Material</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Cant.</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">C.U.</fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Total</fo:block></fo:table-cell>
                    </fo:table-row>
                    <#list lines as item>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block><@x item.pseudoId!item.productId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block><@x item.productName!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@x item.estimatedQuantity!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money item.unitCost!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money item.lineCost!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Resumen</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="12pt">
                <fo:table-column column-width="60%"/>
                <fo:table-column column-width="40%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Materiales estimados</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">USD <@money cost.materialCostEstimated!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Materiales reales</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">USD <@money cost.materialCostReal!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>Mano de obra</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right">USD <@money cost.laborCost!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row background-color="#f0f0f0">
                        <fo:table-cell padding="5pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Costo total de la orden</fo:block></fo:table-cell>
                        <fo:table-cell padding="5pt" border="0.5pt solid #999999"><fo:block text-align="right" font-weight="bold">USD <@money cost.totalCost!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="8pt" color="#555555" space-after="4pt">Despachos registrados: <@x issuanceList?size/>. Recepciones registradas: <@x receiptList?size/>.</fo:block>
            <fo:block font-size="8pt" color="#555555">Generado: <@x generatedAt!""/></fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
