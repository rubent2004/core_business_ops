# moqui-sv-localization

Componente Moqui de localización fiscal y laboral para El Salvador.

## Alcance

- Datos territoriales SV: 14 departamentos, 44 municipios (consolidación 2024), distritos.
- Identificación fiscal: DUI, NIT, NRC, tipo de contribuyente, actividad económica.
- Impuestos: IVA 13%, retenciones, percepciones, libro de IVA compras/ventas.
- Nómina: ISSS, AFP, renta mensual, aguinaldo, vacación, salario mínimo por sector. Tablas versionadas por fecha de vigencia.
- DTE (Documento Tributario Electrónico): FC, CCF, NC, ND, anulación, contingencia. Integración con firmador oficial del Ministerio de Hacienda.

## DTE local y gobierno tecnico

- NC/ND se limitan a referencias de CCF tipo `03` aceptado o mock-aceptado.
- Los PDFs FOP se generan desde `DteEmision.jsonOriginal`; son una representación fiscal imprimible, no un formato oficial certificado por MH.
- SECA de auto-emisión queda deshabilitado por defecto con `sv.dte.seca.auto_emit.enabled=false`. No se engancha a finalización/posteo de factura hasta tener un punto upstream estable.
- Antes de modificar generación DTE o schemas, compare los schemas copiados al componente contra `dte_documents/`:

```bash
cd moqui-erp-lab/moqui-framework/runtime/component/moqui-sv-localization
./bin/check-dte-docs.sh
```

Si `dte_documents/` está en otra ruta, use:

```bash
DTE_DOCUMENTS_DIR=/ruta/dte_documents ./bin/check-dte-docs.sh
```

## Producto SV

La pestaña `Product > SV Producto` guarda defaults fiscales de línea:

- unidad de medida CAT-014;
- tipo de ítem CAT-011;
- tributo CAT-015;
- tratamiento fiscal operativo;
- banderas de retención/percepción;
- defaults de exportación/retorno cuando apliquen.

El generador DTE usa `svUnidadMedidaCode`, `svTipoItemCode`,
`svTributoCode` y `svTaxTreatmentEnumId` del producto al construir
`cuerpoDocumento` y `resumen` para FC/CCF/NC/ND:

- `SvTaxGravada`: llena `ventaGravada` y calcula IVA.
- `SvTaxExenta`: llena `ventaExenta` sin IVA.
- `SvTaxNoSujeta`: llena `ventaNoSuj` sin IVA.
- `SvTaxNoGravada`: llena `noGravado` sin afectar la base imponible.

FSE 14 queda como siguiente flujo especializado porque su schema modela
`compra`, receptor sujeto excluido y retención renta de forma distinta a las
ventas FC/CCF.

## Firmador Java local

El flujo normal de tests usa firmador mock para no depender de procesos externos. Para probar el firmador Java del paquete `dte_documents/`, preparar primero los artefactos locales:

```bash
cd moqui-erp-lab/moqui-framework/runtime/component/moqui-sv-localization
./bin/prepare-firmador.sh
docker compose -f docker/docker-compose.override.yml up -d --build dte-firmador
```

El firmador queda expuesto en `http://localhost:8113`. El endpoint publicado por el JAR de `dte_documents` responde en `/firmardocumento/...`, por eso la propiedad Moqui debe quedar sin `/firma`:

```properties
sv.dte.firmador.url=http://localhost:8113
sv.dte.demo.cert.password=hacienda
```

El contenedor define `CERTIFICATE_HOME=/opt/svfe-api-firmador/uploads`; el JAR empaquetado lo requiere para ubicar `<nit>.crt`.

Para validar sólo el smoke del firmador real:

```bash
./gradlew :runtime:component:moqui-sv-localization:test \
  -Dsv.dte.real.signer.test.enabled=true \
  -Dsv.dte.firmador.url=http://127.0.0.1:8113 \
  -Dsv.dte.real.signer.url=http://127.0.0.1:8113 \
  --tests SvDteRealSignerTest
```

Para validar el cruce Moqui -> firmador Java sobre un DTE generado por el componente:

```bash
./gradlew :runtime:component:moqui-sv-localization:test \
  -Dsv.dte.real.signer.test.enabled=true \
  -Dsv.dte.real.signer.url=http://127.0.0.1:8113 \
  --tests 'SvDteMvpTest.Moqui firma un DTE generado usando firmador Java local'
```

Para apagarlo:

```bash
docker compose -f docker/docker-compose.override.yml down
```

El certificado preparado por `prepare-firmador.sh` es sólo para desarrollo local. Se deriva del demo incluido en `dte_documents`, ajusta el hash de `privateKey.clave` para coincidir con la password local y normaliza las fechas del certificado al formato `Instant` que espera el JAR empaquetado. En ambiente real se debe montar el certificado autorizado por MH y configurar su secreto vía Moqui.

## Dependencias

- mantle-udm 2.2.1
- mantle-usl 3.0.0
- SimpleScreens 2.2.2
- MarbleERP 1.0.1
- moqui-fop 2.0.0

## Registro

Este componente se registra en `myaddons.xml` (no en `addons.xml`).

## Plan de implementación

Ver `../../../../../plan/plan_incial.md` desde este componente.
