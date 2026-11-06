package com.safedrive.server.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PANEL DEL SUPERVISOR — lee las tablas REALES de Supabase con SQL:
 * alertas_fatiga, turnos, unidades, conductores y usuarios.
 * (No usa las clases @Entity, así que no depende de cómo estén mapeadas.)
 */
@RestController
@RequestMapping("/api/supervisor")
@CrossOrigin(origins = "*")
public class SupervisorController {

    private static final int VENTANA_FATIGA_MIN = 5;   // unidad en "Fatiga" si hubo alerta en los últimos 5 min
    private static final int HORAS_TURNO_MAX = 14;     // un turno abierto más viejo que esto se considera abandonado
    private static final String RUTA_DEFECTO = "SJL -> Cercado de Lima";

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FECHA_LARGA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] DIAS = {"Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"};
    private static final String[] FRANJAS = {"0-4h", "4-8h", "8-12h", "12-16h", "16-20h", "20-24h"};
    private static final List<String> TRAMOS_BASE = List.of("SJL Inicio", "Puente Nuevo", "El Agustino", "Cercado");

    private final JdbcTemplate jdbc;

    public SupervisorController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ---------- registros internos (filas ya "armadas" desde la base) ----------
    private record Alerta(long id, long unidadId, String unidad, long conductorId, String conductor,
                          String tipo, Double ear, Double reaccion, String tramo, LocalDateTime fecha) {}

    private record TurnoInfo(long id, long conductorId, String conductor, String dni, long unidadId,
                             String unidad, String ruta, LocalDateTime inicio, LocalDateTime fin, Integer duracionMin) {}

    private static final String SQL_ALERTAS = """
            SELECT a.id, a.unidad_id, u.codigo_unidad, a.conductor_id, us.nombre_completo, a.tipo_alerta,
                   COALESCE(a.valor_ear::float8, a.ear_calculado) AS ear,
                   a.tiempo_reaccion_segundos::float8 AS reaccion, a.tramo_ruta, a.fecha_hora
            FROM alertas_fatiga a
            JOIN unidades u ON u.id = a.unidad_id
            JOIN conductores c ON c.id = a.conductor_id
            JOIN usuarios us ON us.id = c.usuario_id
            WHERE a.fecha_hora > ?
            ORDER BY a.fecha_hora DESC
            """;

    private static final String SQL_TURNOS = """
            SELECT t.id, t.conductor_id, us.nombre_completo, us.dni, t.unidad_id, u.codigo_unidad,
                   t.ruta, t.hora_inicio, t.hora_fin, t.duracion_minutos
            FROM turnos t
            JOIN unidades u ON u.id = t.unidad_id
            JOIN conductores c ON c.id = t.conductor_id
            JOIN usuarios us ON us.id = c.usuario_id
            """;

    private static final String SQL_ULTIMO_TURNO_POR_UNIDAD = """
            SELECT DISTINCT ON (t.unidad_id)
                   t.id, t.conductor_id, us.nombre_completo, us.dni, t.unidad_id, u.codigo_unidad,
                   t.ruta, t.hora_inicio, t.hora_fin, t.duracion_minutos
            FROM turnos t
            JOIN unidades u ON u.id = t.unidad_id
            JOIN conductores c ON c.id = t.conductor_id
            JOIN usuarios us ON us.id = c.usuario_id
            ORDER BY t.unidad_id, t.hora_inicio DESC
            """;

    // =====================================================================
    // 1) PANEL GENERAL
    // =====================================================================
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime inicioHoy = LocalDate.now().atStartOfDay();

        List<Alerta> alertas = alertasDesde(inicioHoy.minusDays(7));
        List<Map<String, Object>> flota = armarFlota(alertas);

        long activas = flota.stream().filter(f -> Boolean.TRUE.equals(f.get("activa"))).count();
        long enFatiga = flota.stream().filter(f -> "Fatiga".equals(f.get("estado"))).count();

        List<Alerta> deHoy = alertas.stream().filter(a -> !a.fecha().isBefore(inicioHoy)).collect(Collectors.toList());
        long ultimaHora = alertas.stream().filter(a -> a.fecha().isAfter(ahora.minusHours(1))).count();
        boolean reaccionDeHoy = !deHoy.isEmpty();
        Double reaccion = promedio(reaccionDeHoy ? deHoy : alertas, Alerta::reaccion);

        Long conductoresTotal = jdbc.queryForObject("SELECT count(*) FROM conductores", Long.class);
        long conductoresEnTurno = turnosVigentes().stream().map(TurnoInfo::conductorId).distinct().count();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("unidadesTotal", flota.size());
        r.put("unidadesEnRuta", activas);
        r.put("porcentajeOperativas", flota.isEmpty() ? 0 : Math.round(100.0 * activas / flota.size()));
        r.put("unidadesEnFatiga", enFatiga);
        r.put("alertasHoy", deHoy.size());
        r.put("alertasUltimaHora", ultimaHora);
        r.put("reaccionPromedio", reaccion);
        r.put("reaccionBase", reaccionDeHoy ? "de hoy" : "últimos 7 días");
        r.put("conductoresTotal", conductoresTotal == null ? 0 : conductoresTotal);
        r.put("conductoresEnTurno", conductoresEnTurno);
        r.put("flota", flota);
        r.put("ultimasAlertas", alertas.stream().limit(8).map(a -> alertaResumen(a, inicioHoy)).collect(Collectors.toList()));
        r.put("actualizado", ahora.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        return r;
    }

    // =====================================================================
    // 2) UNIDADES / FLOTA
    // =====================================================================
    @GetMapping("/flota")
    public Map<String, Object> flota() {
        List<Map<String, Object>> flota = armarFlota(alertasDesde(LocalDate.now().atStartOfDay().minusDays(7)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", flota.size());
        r.put("unidades", flota);
        return r;
    }

    @PostMapping("/unidades")
    public ResponseEntity<?> registrarUnidad(@RequestBody Map<String, String> body) {
        String codigo = limpiar(body.get("codigo"));
        String placa = limpiar(body.get("placa"));
        if (codigo.isEmpty() || placa.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El código y la placa son obligatorios"));
        }
        if (codigo.length() > 20 || placa.length() > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "Código máx. 20 caracteres y placa máx. 10"));
        }
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO unidades (codigo_unidad, placa) VALUES (?, ?) RETURNING id",
                    Long.class, codigo.toUpperCase(), placa.toUpperCase());
            return ResponseEntity.ok(Map.of("id", id == null ? 0L : id, "codigo", codigo.toUpperCase()));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya existe una unidad con ese código o esa placa"));
        }
    }

    @DeleteMapping("/unidades/{id}")
    public ResponseEntity<?> eliminarUnidad(@PathVariable("id") long id) {
        try {
            int n = jdbc.update("DELETE FROM unidades WHERE id = ?", id);
            return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "La unidad tiene turnos o alertas registrados y no se puede eliminar"));
        }
    }

    // =====================================================================
    // 3) DETALLE DE CONDUCTOR
    // =====================================================================
    @GetMapping("/conductor/{id}")
    public ResponseEntity<Map<String, Object>> conductor(@PathVariable("id") long id) {
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT c.id, c.licencia, c.telefono, c.horas_monitoreadas_mes, c.antiguedad_anios,
                       us.nombre_completo, us.dni
                FROM conductores c JOIN usuarios us ON us.id = c.usuario_id
                WHERE c.id = ?
                """, id);
        if (filas.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> c = filas.get(0);

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime inicioMes = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        List<Alerta> alertas = alertasDesde(LocalDate.now().atStartOfDay().minusDays(90)).stream()
                .filter(a -> a.conductorId() == id).collect(Collectors.toList());
        long alertasMes = alertas.stream().filter(a -> !a.fecha().isBefore(inicioMes)).count();

        List<TurnoInfo> turnos = jdbc.query(
                SQL_TURNOS + " WHERE t.conductor_id = ? ORDER BY t.hora_inicio DESC LIMIT 8",
                (rs, i) -> mapTurno(rs), id);

        TurnoInfo activo = turnos.stream().filter(t -> esVigente(t, ahora)).findFirst().orElse(null);
        TurnoInfo ultimo = turnos.isEmpty() ? null : turnos.get(0);

        List<Map<String, Object>> historial = new ArrayList<>();
        for (TurnoInfo t : turnos) {
            LocalDateTime fin = t.fin() != null ? t.fin() : ahora;
            long n = alertas.stream()
                    .filter(a -> !a.fecha().isBefore(t.inicio()) && !a.fecha().isAfter(fin)).count();
            long minutos = (t.fin() != null && t.duracionMin() != null && t.duracionMin() > 0)
                    ? t.duracionMin() : minutos(t.inicio(), fin);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fecha", t.inicio().format(FECHA));
            m.put("unidad", t.unidad());
            m.put("duracion", formatoDuracion(minutos));
            m.put("alertas", n);
            m.put("enCurso", esVigente(t, ahora));
            historial.add(m);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("nombre", c.get("nombre_completo"));
        r.put("dni", c.get("dni"));
        r.put("licencia", c.get("licencia"));
        r.put("telefono", c.get("telefono"));
        r.put("turnoActivo", activo != null);
        r.put("unidad", activo != null ? activo.unidad() : (ultimo != null ? ultimo.unidad() : null));
        r.put("alertasMes", alertasMes);
        r.put("horasMes", c.get("horas_monitoreadas_mes") instanceof Number n ? n.doubleValue() : null);
        r.put("reaccionPromedio", promedio(alertas, Alerta::reaccion));
        r.put("antiguedad", c.get("antiguedad_anios") instanceof Number n ? n.doubleValue() : null);
        r.put("turnos", historial);
        r.put("alertasRecientes", alertas.stream().limit(8)
                .map(a -> alertaResumen(a, LocalDate.now().atStartOfDay())).collect(Collectors.toList()));
        return ResponseEntity.ok(r);
    }

    // =====================================================================
    // 4) REPORTES
    // =====================================================================
    @GetMapping("/reportes")
    public Map<String, Object> reportes(@RequestParam(name = "dias", defaultValue = "7") int dias) {
        int d = Math.max(1, Math.min(dias, 90));
        LocalDate hoy = LocalDate.now();
        LocalDate desde = hoy.minusDays(d - 1L);
        List<Alerta> alertas = alertasDesde(desde.atStartOfDay().minusSeconds(1));

        List<Map<String, Object>> porDia = new ArrayList<>();
        long max = 0;
        for (int i = 0; i < d; i++) {
            LocalDate dia = desde.plusDays(i);
            long n = alertas.stream().filter(a -> a.fecha().toLocalDate().equals(dia)).count();
            max = Math.max(max, n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("etiqueta", d <= 7 ? DIAS[dia.getDayOfWeek().getValue() - 1] : String.valueOf(dia.getDayOfMonth()));
            m.put("fecha", dia.format(FECHA));
            m.put("total", n);
            porDia.add(m);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dias", d);
        r.put("desde", desde.format(FECHA));
        r.put("hasta", hoy.format(FECHA));
        r.put("total", alertas.size());
        r.put("promedioDiario", Math.round(10.0 * alertas.size() / d) / 10.0);
        r.put("maxDia", max);
        r.put("porDia", porDia);
        r.put("porTipo", ranking(alertas, a -> a.tipo() == null ? "Sin tipo" : a.tipo(), 10, "tipo"));
        r.put("porUnidad", ranking(alertas, Alerta::unidad, 5, "unidad"));
        r.put("porConductor", ranking(alertas, Alerta::conductor, 5, "conductor"));
        return r;
    }

    @GetMapping("/reportes/exportar")
    public ResponseEntity<byte[]> exportarCsv(@RequestParam(name = "dias", defaultValue = "7") int dias) {
        int d = Math.max(1, Math.min(dias, 90));
        List<Alerta> alertas = alertasDesde(LocalDate.now().minusDays(d - 1L).atStartOfDay().minusSeconds(1));

        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM: Excel lee bien las tildes
        sb.append("ID;Fecha;Hora;Unidad;Conductor;Tipo de alerta;EAR;Tiempo de reaccion (s);Tramo\n");
        for (Alerta a : alertas) {
            sb.append(a.id()).append(';')
              .append(a.fecha().toLocalDate().format(FECHA_LARGA)).append(';')
              .append(a.fecha().format(HORA)).append(';')
              .append(csv(a.unidad())).append(';')
              .append(csv(a.conductor())).append(';')
              .append(csv(a.tipo())).append(';')
              .append(a.ear() == null ? "" : String.format(Locale.ROOT, "%.2f", a.ear())).append(';')
              .append(a.reaccion() == null ? "" : String.format(Locale.ROOT, "%.2f", a.reaccion())).append(';')
              .append(csv(a.tramo())).append('\n');
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte_alertas_" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // =====================================================================
    // 5) CENTRO IA (análisis automático de los últimos 7 días)
    // =====================================================================
    @GetMapping("/centro-ia")
    public Map<String, Object> centroIa() {
        List<Alerta> alertas = alertasDesde(LocalDate.now().minusDays(6).atStartOfDay().minusSeconds(1));
        int nF = FRANJAS.length;

        Set<String> tramos = new LinkedHashSet<>(TRAMOS_BASE);
        alertas.stream().map(Alerta::tramo).filter(Objects::nonNull).forEach(tramos::add);

        int[] totalesFranja = new int[nF];
        List<Map<String, Object>> matriz = new ArrayList<>();
        int mejorCelda = 0;
        String mejorTramo = null;
        int mejorFranja = 0;

        for (String tramo : tramos) {
            int[] fila = new int[nF];
            for (Alerta a : alertas) {
                if (tramo.equalsIgnoreCase(a.tramo())) fila[a.fecha().getHour() / 4]++;
            }
            for (int i = 0; i < nF; i++) {
                totalesFranja[i] += fila[i];
                if (fila[i] > mejorCelda) { mejorCelda = fila[i]; mejorTramo = tramo; mejorFranja = i; }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tramo", tramo);
            m.put("valores", Arrays.stream(fila).boxed().collect(Collectors.toList()));
            m.put("total", Arrays.stream(fila).sum());
            matriz.add(m);
        }

        int total = alertas.size();
        int pico = indiceMaximo(totalesFranja);

        String hallazgo;
        if (total == 0) {
            hallazgo = "Aún no hay alertas registradas en los últimos 7 días.";
        } else {
            hallazgo = String.format("El %d%% de las alertas (%d de %d) se concentra entre las %d:00 y las %d:00.",
                    Math.round(100.0 * totalesFranja[pico] / total), totalesFranja[pico], total, pico * 4, pico * 4 + 4);
            if (mejorTramo != null) {
                hallazgo += " El punto más crítico es «" + mejorTramo + "» entre las " + (mejorFranja * 4)
                        + ":00 y las " + (mejorFranja * 4 + 4) + ":00 (" + mejorCelda + " alertas).";
            }
        }

        List<Map<String, Object>> recomendaciones = new ArrayList<>();
        if (mejorTramo != null && mejorCelda >= 3) {
            recomendaciones.add(recomendacion("Tramo " + mejorTramo,
                    mejorCelda + " alertas entre las " + (mejorFranja * 4) + ":00 y las " + (mejorFranja * 4 + 4)
                            + ":00. Programar una pausa obligatoria antes de ese tramo en ese horario.", mejorCelda));
        }
        for (Map.Entry<String, List<Alerta>> e : agrupar(alertas, Alerta::unidad, 4)) {
            int f = indiceMaximo(franjas(e.getValue()));
            recomendaciones.add(recomendacion("Unidad " + e.getKey(),
                    e.getValue().size() + " alertas en 7 días, la mayoría entre las " + (f * 4) + ":00 y las "
                            + (f * 4 + 4) + ":00. Revisar la asignación de horarios.", e.getValue().size()));
        }
        for (Map.Entry<String, List<Alerta>> e : agrupar(alertas, Alerta::conductor, 4)) {
            int f = indiceMaximo(franjas(e.getValue()));
            recomendaciones.add(recomendacion("Conductor " + e.getKey(),
                    e.getValue().size() + " alertas en 7 días, la mayoría entre las " + (f * 4) + ":00 y las "
                            + (f * 4 + 4) + ":00. Evaluar descanso adicional o cambio de turno.", e.getValue().size()));
        }
        if (recomendaciones.isEmpty()) {
            recomendaciones.add(recomendacion("Sin patrones de riesgo",
                    total == 0 ? "No hay alertas en los últimos 7 días."
                               : "Ninguna unidad, conductor o tramo supera 3 alertas en los últimos 7 días.", 0));
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("franjas", Arrays.asList(FRANJAS));
        r.put("matriz", matriz);
        r.put("totalAlertas", total);
        r.put("hallazgo", hallazgo);
        r.put("recomendaciones", recomendaciones);
        return r;
    }

    // =====================================================================
    // MÉTODOS AUXILIARES
    // =====================================================================
    private List<Alerta> alertasDesde(LocalDateTime desde) {
        return jdbc.query(SQL_ALERTAS, (rs, i) -> new Alerta(
                rs.getLong("id"), rs.getLong("unidad_id"), rs.getString("codigo_unidad"),
                rs.getLong("conductor_id"), rs.getString("nombre_completo"), rs.getString("tipo_alerta"),
                dbl(rs, "ear"), dbl(rs, "reaccion"), rs.getString("tramo_ruta"),
                rs.getTimestamp("fecha_hora").toLocalDateTime()), Timestamp.valueOf(desde));
    }

    private TurnoInfo mapTurno(ResultSet rs) throws SQLException {
        Timestamp fin = rs.getTimestamp("hora_fin");
        int dur = rs.getInt("duracion_minutos");
        return new TurnoInfo(rs.getLong("id"), rs.getLong("conductor_id"), rs.getString("nombre_completo"),
                rs.getString("dni"), rs.getLong("unidad_id"), rs.getString("codigo_unidad"), rs.getString("ruta"),
                rs.getTimestamp("hora_inicio").toLocalDateTime(), fin == null ? null : fin.toLocalDateTime(),
                rs.wasNull() ? null : dur);
    }

    private List<TurnoInfo> turnosVigentes() {
        return jdbc.query(SQL_TURNOS + " WHERE t.hora_fin IS NULL AND t.hora_inicio > ? ORDER BY t.hora_inicio DESC",
                (rs, i) -> mapTurno(rs), Timestamp.valueOf(LocalDateTime.now().minusHours(HORAS_TURNO_MAX)));
    }

    private boolean esVigente(TurnoInfo t, LocalDateTime ahora) {
        return t.fin() == null && t.inicio().isAfter(ahora.minusHours(HORAS_TURNO_MAX));
    }

    /** Une unidades + turnos abiertos + alertas y calcula el estado de cada unidad. */
    private List<Map<String, Object>> armarFlota(List<Alerta> alertas) {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime inicioHoy = LocalDate.now().atStartOfDay();

        Map<Long, TurnoInfo> vigentes = new HashMap<>();
        for (TurnoInfo t : turnosVigentes()) vigentes.putIfAbsent(t.unidadId(), t); // vienen del más nuevo al más viejo
        Map<Long, TurnoInfo> ultimos = new HashMap<>();
        for (TurnoInfo t : jdbc.query(SQL_ULTIMO_TURNO_POR_UNIDAD, (rs, i) -> mapTurno(rs))) ultimos.put(t.unidadId(), t);

        List<Map<String, Object>> unidades = jdbc.queryForList(
                "SELECT id, codigo_unidad, estado_operativo, nivel_bateria_camara FROM unidades ORDER BY codigo_unidad");

        List<Map<String, Object>> flota = new ArrayList<>();
        for (Map<String, Object> u : unidades) {
            long id = ((Number) u.get("id")).longValue();
            String operativo = u.get("estado_operativo") == null ? "" : u.get("estado_operativo").toString();

            TurnoInfo vigente = vigentes.get(id);
            TurnoInfo ultimo = ultimos.get(id);
            List<Alerta> deUnidad = alertas.stream().filter(a -> a.unidadId() == id).collect(Collectors.toList());
            Alerta ultimaAlerta = deUnidad.isEmpty() ? null : deUnidad.get(0); // la lista viene de más reciente a más antigua

            boolean fatiga = ultimaAlerta != null && ultimaAlerta.fecha().isAfter(ahora.minusMinutes(VENTANA_FATIGA_MIN));
            boolean activa = fatiga || vigente != null || "en_ruta".equals(operativo);
            String estado;
            if (fatiga) estado = "Fatiga";
            else if (activa) estado = "Normal";
            else if ("en_descanso".equals(operativo)) estado = "En descanso";
            else if ("taller".equals(operativo)) estado = "Taller";
            else estado = "Inactivo";

            Long conductorId = null;
            String conductor = null;
            boolean conductorActual = false;
            if (vigente != null) {
                conductorId = vigente.conductorId(); conductor = vigente.conductor(); conductorActual = true;
            } else if (ultimaAlerta != null) {
                conductorId = ultimaAlerta.conductorId(); conductor = ultimaAlerta.conductor();
            } else if (ultimo != null) {
                conductorId = ultimo.conductorId(); conductor = ultimo.conductor();
            }

            String ruta = vigente != null ? vigente.ruta() : (ultimo != null ? ultimo.ruta() : null);
            if (ruta == null || ruta.isBlank()) ruta = RUTA_DEFECTO;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("unidad", u.get("codigo_unidad"));
            m.put("estado", estado);
            m.put("activa", activa);
            m.put("enTurno", vigente != null);
            m.put("conductorId", conductorId);
            m.put("conductor", conductor);
            m.put("conductorActual", conductorActual);
            m.put("ruta", ruta.replace("->", "→"));
            m.put("tiempoEnRuta", vigente != null ? formatoDuracion(minutos(vigente.inicio(), ahora)) : null);
            m.put("bateria", u.get("nivel_bateria_camara"));
            m.put("alertasHoy", deUnidad.stream().filter(a -> !a.fecha().isBefore(inicioHoy)).count());
            m.put("alertasSemana", deUnidad.size());
            m.put("ultimaAlerta", ultimaAlerta == null ? null : ultimaAlerta.fecha().format(HORA));
            flota.add(m);
        }
        return flota;
    }

    private Map<String, Object> alertaResumen(Alerta a, LocalDateTime inicioHoy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("unidad", a.unidad());
        m.put("conductor", a.conductor());
        m.put("tipoAlerta", a.tipo());
        m.put("ear", a.ear() == null ? null : Math.round(a.ear() * 100.0) / 100.0);
        m.put("tramo", a.tramo());
        m.put("hora", a.fecha().format(HORA));
        m.put("fecha", a.fecha().format(FECHA));
        m.put("esHoy", !a.fecha().isBefore(inicioHoy));
        return m;
    }

    private List<Map<String, Object>> ranking(List<Alerta> alertas, Function<Alerta, String> clave,
                                              int limite, String campo) {
        Map<String, Long> conteo = alertas.stream().map(clave).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        long total = conteo.values().stream().mapToLong(Long::longValue).sum();
        return conteo.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .limit(limite)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(campo, e.getKey());
                    m.put("total", e.getValue());
                    m.put("porcentaje", total == 0 ? 0 : Math.round(100.0 * e.getValue() / total));
                    return m;
                }).collect(Collectors.toList());
    }

    /** Agrupa por clave y deja solo los grupos con 3 o más alertas (de mayor a menor). */
    private List<Map.Entry<String, List<Alerta>>> agrupar(List<Alerta> alertas, Function<Alerta, String> clave, int limite) {
        return alertas.stream().filter(a -> clave.apply(a) != null)
                .collect(Collectors.groupingBy(clave))
                .entrySet().stream()
                .filter(e -> e.getValue().size() >= 3)
                .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                .limit(limite)
                .collect(Collectors.toList());
    }

    private int[] franjas(List<Alerta> alertas) {
        int[] f = new int[FRANJAS.length];
        for (Alerta a : alertas) f[a.fecha().getHour() / 4]++;
        return f;
    }

    private int indiceMaximo(int[] v) {
        int idx = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[idx]) idx = i;
        return idx;
    }

    private Map<String, Object> recomendacion(String titulo, String detalle, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titulo", titulo);
        m.put("detalle", detalle);
        m.put("nivel", total >= 5 ? "alta" : (total >= 3 ? "media" : "info"));
        return m;
    }

    private Double promedio(List<Alerta> alertas, Function<Alerta, Double> campo) {
        OptionalDouble avg = alertas.stream().map(campo).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average();
        return avg.isPresent() ? Math.round(avg.getAsDouble() * 100.0) / 100.0 : null;
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private long minutos(LocalDateTime a, LocalDateTime b) {
        return Math.max(0, Duration.between(a, b).toMinutes());
    }

    private String formatoDuracion(long minutos) {
        return (minutos / 60) + "h " + String.format("%02d", minutos % 60) + "m";
    }

    private String limpiar(String s) {
        return s == null ? "" : s.trim();
    }

    private String csv(String s) {
        if (s == null) return "";
        String limpio = s.replace("\"", "\"\"");
        return (limpio.contains(";") || limpio.contains("\n") || limpio.contains("\"")) ? "\"" + limpio + "\"" : limpio;
    }
}
