<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro money value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.00"), false)}<#else>0.00</#if></#macro>
<#assign run = svWorkEffort!{}>
<#assign produceList = svProduceList![]>
<#assign consumeList = svConsumeList![]>
<#assign partyList = svPartyList![]>
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
            <fo:block font-size="15pt" font-weight="bold" text-align="center" space-after="4pt">ORDEN DE PRODUCCION</fo:block>
            <fo:block text-align="center" color="#555555" space-after="12pt">Metalúrgica del Pacífico — Manufactura metálica</fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-column column-width="25%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Orden</fo:block><fo:block><@x run.workEffortId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Estado</fo:block><fo:block><@x run.statusId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Inicio</fo:block><fo:block><@x run.estimatedStartDate!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777"><fo:block font-weight="bold">Entrega</fo:block><fo:block><@x run.estimatedCompletionDate!""/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" space-after="3pt">Descripcion del trabajo</fo:block>
            <fo:block border="0.5pt solid #999999" padding="5pt" space-after="10pt"><@x run.description!run.workEffortName!""/></fo:block>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Producto a fabricar</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="18%"/>
                <fo:table-column column-width="62%"/>
                <fo:table-column column-width="20%"/>
                <fo:table-body>
                    <#list produceList as item>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block><@x item.pseudoId!item.productId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block><@x item.productName!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@x item.estimatedQuantity!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                    <#if !(produceList?has_content)>
                    <fo:table-row><fo:table-cell padding="4pt" number-columns-spanned="3" border="0.5pt solid #cccccc"><fo:block>No hay producto configurado.</fo:block></fo:table-cell></fo:table-row>
                    </#if>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Materiales requeridos</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="16%"/>
                <fo:table-column column-width="49%"/>
                <fo:table-column column-width="15%"/>
                <fo:table-column column-width="20%"/>
                <fo:table-body>
                    <#list consumeList as item>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block><@x item.pseudoId!item.productId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block><@x item.productName!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@x item.estimatedQuantity!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block></fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" space-after="3pt">Responsables</fo:block>
            <fo:block border="0.5pt solid #999999" padding="5pt" space-after="12pt">
                <#if partyList?has_content>
                    <#list partyList as party><@x party.firstName!party.organizationName!party.partyId!""/><#if party_has_next>, </#if></#list>
                <#else>
                    Sin responsables asignados.
                </#if>
            </fo:block>

            <fo:table table-layout="fixed" width="100%" space-before="28pt">
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center">Produccion</fo:block></fo:table-cell>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center">Bodega</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
