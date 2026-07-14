# Procedimiento de Actualización de Nómina

## Cuándo aplicar este procedimiento

- **Cambio de ley**: El gobierno actualiza tasas de ISSS, AFP, renta o salarios mínimos.
- **Decreto ejecutivo**: Nueva tabla de retención mensual (ej. Decreto Ejecutivo Nº 10 de 2025).
- **Error de captura**: Detectaste un dato incorrecto en la tabla cargada.

---

## Principio rector — ¡crítico para auditoría!

**NUNCA edites un esquema vigente directamente.** Los esquemas (`SvPayrollScheme`)
y salarios mínimos (`SvMinimumWageSector`) son **versionados por
fromDate/thruDate**: cada vigencia es una fila distinta. Cuando algo cambia, se
crea una **nueva vigencia** con fromDate = fecha de inicio del cambio, y la
vigencia anterior se cierra con thruDate = mismo valor (sin solape).

Esto preserva:
1. **Trazabilidad fiscal** — siempre puedes mostrar al fisco qué tabla aplicaste en
   qué período.
2. **Snapshots históricos** — las corridas ya calculadas/aprobadas no se
   recalculan; sus snapshots están congelados.
3. **Cálculos futuros correctos** — el sistema selecciona automáticamente la
   vigencia activa según `asOfDate` de la corrida.

---

## Caso A: Cambio de ley (ISSS, AFP, renta)

### Ejemplo: ISSS sube de 7.5% patronal a 8% efectivo desde 2027-01-01

1. **Navegar** a `Localización SV > Nómina > Reglas (ISSS/AFP/Renta)`.
2. En el bloque **"ISSS aporte patronal"**, clic en **"Nueva vigencia"**.
3. Llenar:
   - **Esquema vigente a cerrar**: selecciona la vigencia actual (auto-listada).
   - **ID nueva vigencia**: `SvPayrollIsssEmpr2027` (convención: `SvPayroll<Tipo><Año>`).
   - **Vigente desde**: `2027-01-01 00:00:00`.
   - **Descripción**: cita el decreto o fuente legal (ej. "ISSS patronal 8%
     según Decreto Legislativo Nº 425 del 2026-12-15").
   - **Copiar tramos**: **Sí** (recomendado) — copia los tramos del esquema
     anterior como punto de partida.
4. Clic en **"Crear nueva vigencia"**.
5. El sistema cierra la vigencia anterior y crea la nueva.
6. **Ajustar tramos**: en la lista, clic en **"Editar tramos"** del nuevo esquema.
   Cambia el porcentaje de `7.5` a `8.0`.

### Resultado:
- Corridas con `asOfDate < 2027-01-01` siguen usando la vigencia anterior.
- Corridas con `asOfDate >= 2027-01-01` automáticamente usan la nueva vigencia.

---

## Caso B: Cambio de salario mínimo sectorial

### Ejemplo: Comercio sube a $450 mensual desde 2027-06-01

1. **Navegar** a `Localización SV > Nómina > Salarios Mínimos`.
2. Clic en **"Nueva vigencia"**.
3. Llenar:
   - **Sector**: Comercio y Servicios.
   - **Vigente desde**: `2027-06-01 00:00:00`.
   - **Salario mensual**: `450.00`.
   - **Salario diario**: `15.00`.
   - **Descripción**: cita el decreto (ej. "Decreto Ejecutivo Nº 50 del 2027-05-15").
4. Clic en **"Crear vigencia"**.

### Resultado:
- La vigencia anterior se cierra con thruDate = 2027-06-01.
- La nueva vigencia activa para Comercio comienza el 2027-06-01.

---

## Caso C: Error de captura en esquema vigente

### Ejemplo: Capturaste 4% en lugar de 3% para ISSS empleado

Hay dos sub-escenarios según si ya hay corridas calculadas con ese esquema.

### C.1: Sin corridas dependientes (recién cargado)

1. Navegar a `Reglas (ISSS/AFP/Renta) > Editar tramos` del esquema.
2. Corregir el porcentaje a 3%.
3. Guardar — el sistema NO bloquea porque no hay corridas afectadas.

### C.2: Con corridas calculadas/aprobadas dependientes

Cuando hay corridas en estado `SvPayCalculated` o `SvPayApproved` cuya `asOfDate`
cae en la vigencia del esquema, el sistema **bloquea la modificación directa**
para proteger la integridad fiscal.

**Procedimiento correcto**:

1. Decide la fecha desde la cual el porcentaje correcto aplica:
   - **¿La ley siempre fue 3% y capturé mal?** → usa el `fromDate` original del
     esquema y modifica con `forceUnsafe=true`. Luego **recalcula manualmente**
     cada corrida afectada.
   - **¿El cambio aplica desde una fecha específica futura?** → usa el flujo de
     **nueva vigencia** (Caso A).

2. Para corregir con `forceUnsafe`:
   - Navega a `Editar tramos`.
   - Verás una banda roja listando las corridas afectadas.
   - Solo un usuario con rol `SvAdmin` puede usar `forceUnsafe`.
   - Después de corregir, vuelve a cada corrida afectada y haz clic en
     **"Calcular"** para regenerar los snapshots.
   - Corridas en estado `SvPayPosted` (contabilizadas) **no se pueden
     recalcular** porque ya generaron asientos contables; usa un ajuste contable
     manual aparte si es necesario.

3. **Auditoría**: registra en las notas de la corrida y de la organización el
   motivo del recálculo y la fecha.

---

## Convenciones de nombres (importantes para auditoría)

| Esquema | Convención de ID |
|---------|------------------|
| ISSS empleado | `SvPayrollIsssEmp<Año>` |
| ISSS empleador | `SvPayrollIsssEmpr<Año>` |
| AFP empleado | `SvPayrollAfpEmp<Año>` |
| AFP empleador | `SvPayrollAfpEmpr<Año>` |
| Renta mensual | `SvPayrollRentaMensual<Año>` |
| Salario mínimo | implícito por (sectorEnumId, fromDate) |

Si en el mismo año hay dos cambios, agregar mes: `SvPayrollIsssEmp2027Jun`.

---

## Verificación post-actualización

Después de cualquier actualización:

1. **Smoke test** con el cálculo preview:
   - `Nómina > Preview salario neto`.
   - Ingresa un salario de prueba (ej. 800 USD) con `asOfDate` posterior a la
     nueva vigencia.
   - Verifica que los porcentajes mostrados coinciden con la nueva tabla.

2. **Verificar lista de vigencias**:
   - `Nómina > Reglas` — debe mostrar la nueva vigencia como "ACTIVA" y la
     anterior con la fecha de cierre.

3. **Backup**:
   - Antes de cargar el cambio: exporta los esquemas actuales (`gradle
     exportSeedData` o similar).
   - Documenta el cambio en el archivo `docs/legal-sources/` con cita del
     decreto.

---

## Tabla de referencia rápida — Estados de corrida vs modificación

| Estado de la corrida | ¿Cambiar tramo del esquema le afecta? | Acción recomendada |
|----------------------|---------------------------------------|--------------------|
| `SvPayCreated` (sin calcular) | No (no hay snapshot) | Modificar libremente |
| `SvPayCalculated` (snapshot persistido) | **Sí, requiere recalculo** | Bloqueado; usar nueva vigencia o forceUnsafe |
| `SvPayApproved` (aprobada) | **Sí, requiere recalculo** | Bloqueado; usar nueva vigencia o forceUnsafe + reaprobar |
| `SvPayPosted` (contabilizada) | No (snapshot congelado por GL) | Permitido modificar tramos sin afectar; cualquier corrección requiere ajuste contable manual |

---

## Servicios CLI/REST disponibles

Para automatización o integración:

```
sv.localization.PayrollSvServices.version#PayrollScheme
  Parámetros:
    sourceSchemeId (opcional, null si es primera vigencia)
    newSchemeId (required)
    effectiveFromDate (required, Timestamp)
    schemeTypeEnumId (required si sourceSchemeId es null)
    description (opcional)
    currencyUomId (default USD)
    copyBrackets (default true)
  Devuelve: newSchemeId, sourceSchemeId, bracketsCopied

sv.localization.PayrollSvServices.version#MinimumWageSector
  Parámetros:
    sectorEnumId (required)
    effectiveFromDate (required, Timestamp)
    monthlyAmount (required)
    dailyAmount (required)
    description (opcional)
  Devuelve: sectorEnumId, effectiveFromDate, previousFromDate

sv.localization.PayrollSvServices.update#PayrollSchemeBracketsSafe
  Parámetros:
    svPayrollSchemeId (required)
    brackets (required, List<Map>)
    forceUnsafe (default false)
  Devuelve: svPayrollSchemeId, bracketsReplaced, affectedRunIds
```

---

## Histórico de cambios — fuentes legales

Cada actualización debe registrarse aquí con:
- Fecha de aplicación
- Decreto / fuente legal
- Esquemas afectados
- Persona responsable

| Fecha | Fuente legal | Esquemas | Responsable |
|-------|--------------|----------|-------------|
| (vacío) | (placeholder) | (placeholder) | (placeholder) |
