<#macro x value=""><#if value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?c, false)}<#else>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute((value!"")?string, false)}</#if></#macro>
<#macro money value=0><#if (value!"")?has_content && value?is_number>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(value?string("0.00"), false)}<#else>0.00</#if></#macro>
<#assign emp = employee!{}>
<#assign run = payrollRun!{}>
<#assign org = organization!{}>
<#assign line = payrollLine!{}>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter" page-width="8.5in" page-height="11in"
                               margin-top="0.5in" margin-bottom="0.5in" margin-left="0.6in" margin-right="0.6in">
            <fo:region-body/>
        </fo:simple-page-master>
    </fo:layout-master-set>
    <fo:page-sequence master-reference="letter">
        <fo:flow flow-name="xsl-region-body">

            <fo:block font-size="14pt" font-weight="bold" text-align="center" space-after="2pt"><@x org.organizationName!""/></fo:block>
            <fo:block text-align="center" font-size="8pt" color="#555555" space-after="2pt">NIT: <@x org.nit!""/>  NRC: <@x org.nrc!""/></fo:block>
            <#if (org.address!"")?has_content><fo:block text-align="center" font-size="8pt" color="#555555" space-after="10pt"><@x org.address/></fo:block></#if>

            <fo:block font-size="12pt" font-weight="bold" text-align="center" space-after="3pt" border-top="1pt solid #000000" border-bottom="1pt solid #000000" padding-top="4pt" padding-bottom="4pt">BOLETA DE PAGO</fo:block>
            <fo:block text-align="center" font-size="9pt" space-after="10pt">Periodo: <@x run.periodLabel!run.periodYearMonth!""/></fo:block>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Datos del empleado</fo:block>
                            <fo:block>Nombre: <@x emp.fullName!""/></fo:block>
                            <fo:block>DUI: <@x emp.dui!""/></fo:block>
                            <fo:block>NIT: <@x emp.nit!""/></fo:block>
                            <fo:block>Cargo: <@x line.positionDescription!""/></fo:block>
                            <#if (emp.fechaIngreso!"")?has_content><fo:block>Fecha ingreso: <@x emp.fechaIngreso/></fo:block></#if>
                        </fo:table-cell>
                        <fo:table-cell padding="4pt" border="0.5pt solid #999999">
                            <fo:block font-weight="bold" space-after="3pt">Datos previsionales</fo:block>
                            <fo:block>NUP ISSS: <@x emp.nupIsss!""/></fo:block>
                            <fo:block>NUP AFP: <@x emp.nupAfp!""/></fo:block>
                            <fo:block>AFP: <@x emp.afpProvider!""/></fo:block>
                            <fo:block>Corrida: <@x run.svPayrollRunId!""/></fo:block>
                            <fo:block>Estado: <@x run.statusId!""/></fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-after="8pt">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="0pt">
                            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">DEVENGADO</fo:block>
                            <fo:table table-layout="fixed" width="100%">
                                <fo:table-column column-width="65%"/>
                                <fo:table-column column-width="35%"/>
                                <fo:table-body>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>Sueldo base</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.grossMonthlyAmount!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    <#if (line.vacationPay!0) gt 0>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>Vacacion (<@x line.vacationDays!0/> dias + 30%)</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.vacationPay/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    </#if>
                                    <#if (line.aguinaldoAmount!0) gt 0>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>Aguinaldo (<@x line.aguinaldoDays!0/> dias)</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.aguinaldoAmount/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    </#if>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block font-weight="bold">Total devengado</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block text-align="right" font-weight="bold"><@money totals.gross!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                </fo:table-body>
                            </fo:table>
                        </fo:table-cell>
                        <fo:table-cell padding="0pt">
                            <fo:block font-weight="bold" background-color="#eeeeee" padding="4pt" border="0.5pt solid #999999">DEDUCCIONES</fo:block>
                            <fo:table table-layout="fixed" width="100%">
                                <fo:table-column column-width="65%"/>
                                <fo:table-column column-width="35%"/>
                                <fo:table-body>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>ISSS empleado (3%)</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.isssEmployee!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>AFP empleado (7.25%)</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.afpEmployee!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>Renta mensual</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block text-align="right"><@money line.rentaMonthly!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                    <fo:table-row>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block font-weight="bold">Total deducciones</fo:block></fo:table-cell>
                                        <fo:table-cell padding="3pt" border="0.5pt solid #999999" background-color="#f5f5f5"><fo:block text-align="right" font-weight="bold"><@money line.totalEmployeeDeductions!0/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                </fo:table-body>
                            </fo:table>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="13pt" font-weight="bold" text-align="right" border-top="1.5pt solid #000000" border-bottom="1.5pt solid #000000" padding="6pt" space-after="12pt" background-color="#f0f0f0">NETO A PAGAR: USD <@money totals.netPay!line.netPay!0/></fo:block>

            <fo:block font-size="8pt" font-weight="bold" space-after="3pt">Aportes patronales (informativos, no descuentan del neto)</fo:block>
            <fo:table table-layout="fixed" width="100%" space-after="14pt" font-size="8pt">
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="34%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>ISSS patronal (7.5%): <@money line.isssEmployer!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>AFP patronal (8.75%): <@money line.afpEmployer!0/></fo:block></fo:table-cell>
                        <fo:table-cell padding="3pt" border="0.5pt solid #cccccc"><fo:block>Costo total empleador: <@money line.employerCost!0/></fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:table table-layout="fixed" width="100%" space-after="6pt" space-before="20pt">
                <fo:table-column column-width="45%"/>
                <fo:table-column column-width="10%"/>
                <fo:table-column column-width="45%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center" font-size="8pt">Firma del empleado</fo:block></fo:table-cell>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="4pt" border-top="0.5pt solid #000000"><fo:block text-align="center" font-size="8pt">Firma del pagador</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>

            <fo:block font-size="7pt" color="#555555" border-top="0.5pt solid #999999" padding-top="4pt" space-before="20pt">
                Documento generado por el sistema. Recibo de pago segun el Codigo de Trabajo de la Republica de El Salvador.
                Generado: <@x generatedAt!""/>
            </fo:block>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
