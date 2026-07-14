<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro xb value=""><#local raw><#if value?is_number>${value?c}<#else>${(value!"")?string}</#if></#local><#local encoded = Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(raw, false)>${encoded?replace("_", "_&#8203;")?replace("-", "-&#8203;")?replace("/", "/&#8203;")}</#macro>
<#macro qty value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.##"), false)}<#else>0</#if></#macro>
<#assign run = svWorkEffort!{}>
<#assign consumeList = svConsumeList![]>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.55in" margin-bottom="0.55in" margin-left="0.6in" margin-right="0.6in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">

            <fo:block font-size="16pt" font-weight="bold" text-align="center" space-after="2pt">HOJA DE REQUISICION</fo:block>
            <fo:block text-align="center" font-size="8pt" color="#555555" space-after="2pt">Solicitud de materiales a bodega para orden de produccion</fo:block>
            <fo:block text-align="center" font-size="8pt" color="#555555" space-after="12pt">Metalúrgica del Pacífico</fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="10pt">
                <fo:table-column column-width="30%"/>
                <fo:table-column column-width="20%"/>
                <fo:table-column column-width="28%"/>
                <fo:table-column column-width="22%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">N° ORDEN</fo:block>
                            <fo:block font-size="8pt" wrap-option="wrap"><@xb run.workEffortId!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">ESTADO</fo:block>
                            <fo:block font-size="8pt" wrap-option="wrap"><@xb run.statusId!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">FECHA SOLICITUD</fo:block>
                            <fo:block><@x generatedAt!""/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #777777">
                            <fo:block font-weight="bold" font-size="7pt" color="#666666">N° MATERIALES</fo:block>
                            <fo:block>${consumeList?size}</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-weight="bold" space-after="3pt">Descripcion del trabajo</fo:block>
            <fo:block border="0.5pt solid #999999" padding="5pt" space-after="14pt"><@x run.description!run.workEffortName!""/></fo:block>

            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">Materiales solicitados</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="14pt" font-size="8pt">
                <fo:table-column column-width="6%"/>
                <fo:table-column column-width="24%"/>
                <fo:table-column column-width="36%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="14%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-header>
                    <fo:table-row background-color="#dddddd">
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">#</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Codigo</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold">Descripcion</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="right">Cantidad</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="center">Bodega</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999"><fo:block font-weight="bold" text-align="center">Despachado</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-header>
                <fo:table-body>
                    <#list consumeList as item>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block>${item?index + 1}</fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block font-size="7.5pt" wrap-option="wrap"><@xb item.pseudoId!item.productId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block wrap-option="wrap"><@xb item.productName!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@qty item.estimatedQuantity!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="center" font-size="6.5pt" wrap-option="wrap"><@xb item.facilityId!""/></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #cccccc"><fo:block text-align="center">_______</fo:block></fo:table-cell>
                    </fo:table-row>
                    </#list>
                    <#if !(consumeList?has_content)>
                    <fo:table-row>
                        <fo:table-cell padding="6pt" number-columns-spanned="6" border="0.5pt solid #cccccc">
                            <fo:block text-align="center" color="#888888">No hay materiales configurados para esta orden.</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                    </#if>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="8pt" color="#666666" space-before="20pt" space-after="3pt">Observaciones</fo:block>
            <fo:block border="0.5pt solid #cccccc" padding="6pt" space-after="20pt" min-height="0.5in">
                <fo:block> </fo:block>
                <fo:block> </fo:block>
            </fo:block>

            <fo:table table-layout="fixed" width="100%" space-before="20pt">
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000">
                            <fo:block text-align="center" font-size="8pt">Solicita (Produccion)</fo:block>
                        </fo:table-cell>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000">
                            <fo:block text-align="center" font-size="8pt">Entrega (Bodega)</fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt" space-before="20pt">
                Hoja de requisicion generada por el sistema. Los materiales que se despachan deben anotarse en el
                portal con el numero de orden y la cantidad despachada. Generada: <@x generatedAt!""/>
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
