import groovy.transform.Field
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.sql.Date
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

Object ctx(String name) {
    Binding b = getBinding()
    return b?.hasVariable(name) ? b.getVariable(name) : null
}

String clean(Object raw) { return raw == null ? null : raw.toString().trim() }

LocalDate toLocalDate(Object raw) {
    if (raw == null) return null
    if (raw instanceof LocalDate) return raw
    if (raw instanceof Timestamp) return ((Timestamp) raw).toLocalDateTime().toLocalDate()
    if (raw instanceof Date) return ((Date) raw).toLocalDate()
    String s = clean(raw)
    return s ? LocalDate.parse(s.substring(0, 10)) : null
}

/** Domingo de Pascua por el cómputo gregoriano anónimo (algoritmo de Gauss/Meeus). */
LocalDate easterSunday(int year) {
    int a = year % 19, b = (int) (year / 100), c = year % 100
    int d = (int) (b / 4), e = b % 4, f = (int) ((b + 8) / 25), g = (int) ((b - f + 1) / 3)
    int h = (19 * a + b - d - g + 15) % 30, i = (int) (c / 4), k = c % 4
    int l = (32 + 2 * e + 2 * i - h - k) % 7
    int m = (int) ((a + 11 * h + 22 * l) / 451)
    int month = (int) ((h + l - 7 * m + 114) / 31)
    int day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
}

/** Feriados nacionales de El Salvador para un año (fijos + Jueves/Viernes/Sábado Santo). */
Set<LocalDate> nationalHolidays(int year) {
    Set<LocalDate> h = new HashSet<>()
    h.add(LocalDate.of(year, 1, 1))    // Año Nuevo
    h.add(LocalDate.of(year, 5, 1))    // Día del Trabajo
    h.add(LocalDate.of(year, 5, 10))   // Día de la Madre
    h.add(LocalDate.of(year, 6, 17))   // Día del Padre
    h.add(LocalDate.of(year, 8, 6))    // Día del Salvador del Mundo (fiestas agostinas)
    h.add(LocalDate.of(year, 9, 15))   // Independencia
    h.add(LocalDate.of(year, 11, 2))   // Día de los Difuntos
    h.add(LocalDate.of(year, 12, 25))  // Navidad
    LocalDate easter = easterSunday(year)
    h.add(easter.minusDays(3))         // Jueves Santo
    h.add(easter.minusDays(2))         // Viernes Santo
    h.add(easter.minusDays(1))         // Sábado Santo
    return h
}

boolean isHolidayDate(ExecutionContext ec, LocalDate d) {
    if (nationalHolidays(d.year).contains(d)) return true
    return ec != null && ec.entity.find('sv.localization.tax.SvHoliday')
            .condition('holidayDate', Date.valueOf(d)).count() > 0
}

boolean isBusinessDayLd(ExecutionContext ec, LocalDate d) {
    if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return false
    return !isHolidayDate(ec, d)
}

/** n-ésimo día hábil de un mes (1-based). Para "10 primeros días hábiles del mes siguiente". */
LocalDate nthBusinessDayOfMonth(ExecutionContext ec, YearMonth ym, int n) {
    LocalDate d = ym.atDay(1)
    int count = 0
    while (true) {
        if (isBusinessDayLd(ec, d)) {
            count++
            if (count == n) return d
        }
        d = d.plusDays(1)
        if (d.monthValue != ym.monthValue && d.year != ym.year && count < n) {
            // mes sin suficientes días hábiles (no debería pasar con n<=10): devuelve el último
            return d.minusDays(1)
        }
    }
}

// ===== Servicios públicos =====

Object isBusinessDay() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    LocalDate d = toLocalDate(ctx('date'))
    if (d == null) { ec.message.addError('date requerida'); return [businessDay: false] }
    return [businessDay: isBusinessDayLd(ec, d), holiday: isHolidayDate(ec, d)]
}

/** Tipos de DTE cuya invalidación va a 10 días hábiles del mes siguiente (Cuadro 6, Normativa v2.0:
 *  CCFE 03, NRE 04, NCE 05, NDE 06, CRE 07, CLE 08, DCLE 09 y CDE 15 — Comprobante de Donación). */
@Field final List<String> CREDIT_FISCAL_TYPES = ['03', '04', '05', '06', '07', '08', '09', '15']

/**
 * check#DtePlazo — Calcula el plazo oficial (Normativa DTE v2.0, ver plan/13) para un evento y
 * dice si la fecha actual lo respeta. NO bloquea: el llamador decide (la UI advierte y permite).
 *
 * eventType: invalidacion | retorno | operaciones_especiales | diferida | contingencia_dte
 * referenceDate: fecha base del plazo (sello del documento / fecha de generación / sello del
 *                evento de contingencia, según el evento).
 */
Object checkDtePlazo() {
    ExecutionContext ec = (ExecutionContext) ctx('ec')
    String eventType = clean(ctx('eventType'))
    String tipoDteCode = clean(ctx('tipoDteCode'))
    boolean esMedicamento = ctx('esMedicamento') == true || clean(ctx('esMedicamento')) == 'true'
    LocalDate refDate = toLocalDate(ctx('referenceDate'))
    Timestamp refTs = ctx('referenceDate') instanceof Timestamp ? (Timestamp) ctx('referenceDate') : null
    LocalDate now = (ctx('nowOverride') != null ? toLocalDate(ctx('nowOverride'))
            : ec.user.nowTimestamp.toInstant().atZone(java.time.ZoneId.of('America/El_Salvador')).toLocalDate())

    if (!eventType) { ec.message.addError('eventType requerido'); return [:] }
    if (refDate == null) { ec.message.addError('referenceDate requerida'); return [:] }

    LocalDate deadline = null
    String descripcion = null

    switch (eventType) {
        case 'invalidacion':
            if (tipoDteCode in CREDIT_FISCAL_TYPES) {
                deadline = nthBusinessDayOfMonth(ec, YearMonth.from(refDate).plusMonths(1), 10)
                descripcion = '10 primeros días hábiles del mes tributario siguiente al sello'
            } else if (esMedicamento && tipoDteCode in ['01', '11']) {
                deadline = refDate.plusYears(2)
                descripcion = '2 años desde el sello (medicamentos perecederos)'
            } else {
                deadline = refDate.plusMonths(3)
                descripcion = '3 meses desde el sello (FE/FEX/FSE)'
            }
            break
        case 'retorno':
            deadline = refDate.plusMonths(3)
            descripcion = '3 meses desde el sello del documento a relacionar'
            break
        case 'operaciones_especiales':
            deadline = nthBusinessDayOfMonth(ec, YearMonth.from(refDate).plusMonths(1), 10)
            descripcion = '10 primeros días hábiles del mes siguiente a la operación'
            break
        case 'diferida':
            deadline = refDate.plusDays(1)
            descripcion = '1 día posterior a la generación y entrega (transmisión diferida normal)'
            break
        case 'contingencia_dte':
            // 72 horas desde el sello del evento de contingencia
            Timestamp base = refTs ?: Timestamp.valueOf(refDate.atStartOfDay())
            Timestamp limit = new Timestamp(base.time + 72L * 3600L * 1000L)
            boolean within72 = ec.user.nowTimestamp.time <= limit.time
            return [eventType: eventType, withinPlazo: within72, deadline: limit,
                    plazoDescripcion: '72 horas desde el sello del evento de contingencia',
                    message: within72 ? null : "Plazo vencido: los DTE en contingencia debían transmitirse dentro de 72 horas del sello del evento de contingencia (límite ${limit})."]
    }

    boolean within = !now.isAfter(deadline)
    String message = within ? null :
            "Plazo de ${eventType} vencido: ${descripcion}. Fecha límite ${deadline}, hoy ${now}."
    return [eventType: eventType, withinPlazo: within, deadline: Date.valueOf(deadline),
            plazoDescripcion: descripcion, message: message]
}
