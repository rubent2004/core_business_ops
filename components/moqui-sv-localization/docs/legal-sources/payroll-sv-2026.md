# Payroll SV 2026 - fuentes oficiales locales

Este documento es el gate legal para los valores semilla de nomina SV en
`moqui-sv-localization`. Si una fuente oficial vigente contradice estos valores,
se debe actualizar primero este documento y despues el seed correspondiente.

## ISSS

- Fuente: Instituto Salvadoreno del Seguro Social, lineamiento de modificacion de
  salario maximo cotizable.
- URL oficial:
  `https://ovisss.isss.gob.sv/documentos_ofivi/Lineamiento_Mod_Salario_Maximo.pdf`
- Valor operativo local:
  - Trabajador: 3.00%
  - Patrono: 7.50%
  - Salario maximo cotizable: USD 1,000.00 mensual
- Vigencia usada para seed: 2015-08-01 00:00:00

## Pensiones / AFP

- Fuente: Superintendencia del Sistema Financiero, aviso SEPP.
- URL oficial: `https://ssf.gob.sv/2023/02/03/aviso-de-sistema-sepp/`
- Valor operativo local:
  - Cotizacion total: 16.00%
  - Trabajador: 7.25%
  - Patrono: 8.75%
  - Techo cotizable: sin techo local seed; no usar el techo historico de
    USD 7,714.43 salvo confirmacion oficial vigente.
- Vigencia usada para seed: 2023-01-01 00:00:00

## Renta mensual

- Fuente: Ministerio de Hacienda / Transparencia Fiscal, Decreto Ejecutivo No. 10
  de 2025, modificacion a tablas de retencion del Impuesto Sobre la Renta.
- URL oficial MH: `https://www.mh.gob.sv/wp-content/uploads/2025/05/Modificacion-a-las-tablas-de-retenci%C3%B3n-del-Impuesto-Sobre-la-Renta-Decreto-Ejecutivo-NO.10.pdf`
- URL oficial Transparencia Fiscal:
  `https://www.transparenciafiscal.gob.sv/downloads/pdf/700-DGII-DC-2025-01.pdf`
- Base operativa local para sueldo mensual: salario bruto menos ISSS trabajador
  y AFP trabajador.
- Tabla mensual seed:
  - Hasta USD 550.00: sin retencion.
  - USD 550.01 a USD 895.24: 10% sobre exceso de USD 550.00 mas USD 17.67.
  - USD 895.25 a USD 2,038.10: 20% sobre exceso de USD 895.24 mas USD 60.00.
  - Desde USD 2,038.11: 30% sobre exceso de USD 2,038.10 mas USD 288.57.
- Deduccion especial indicada por la tabla: para tramo II, aplicar deduccion
  mensual de USD 133.33 cuando el monto anual sea igual o inferior a
  USD 9,100.00.
- Vigencia usada para seed: 2025-05-01 00:00:00.

## Salario minimo

- Fuente: Ministerio de Trabajo y Prevision Social, publicacion de nuevos
  salarios minimos 2025.
- URL oficial: `https://www.mtps.gob.sv/2025/05/27/aprueban-incremento-del-12-al-salario-minimo-solicitado-por-el-gobierno-del-presidente-nayib-bukele/`
- Valores mensuales seed desde 2025-06-01:
  - Comercio, servicios, industria, ingenios azucareros y beneficios de cafe:
    USD 408.80.
  - Maquila textil y confeccion: USD 402.32.
  - Recoleccion de cana de azucar y beneficios de cafe: USD 305.23.
  - Recoleccion de cafe, sector agropecuario, pesca y otras actividades
    agricolas: USD 272.53.

## Vacaciones y aguinaldo

- Fuente: Codigo de Trabajo, art. 177 para vacacion anual remunerada y art. 198
  para aguinaldo.
- URL oficial Asamblea Legislativa:
  `https://www.asamblea.gob.sv/sites/default/files/documents/decretos/AD778A29-F1B3-495E-AE19-E2B05D93685D.pdf`
- Regla local:
  - Vacacion: 15 dias de salario mas 30%.
  - Aguinaldo: menos de 3 anos = 15 dias; 3 a menos de 10 anos = 19 dias;
    10 anos o mas = 21 dias.
