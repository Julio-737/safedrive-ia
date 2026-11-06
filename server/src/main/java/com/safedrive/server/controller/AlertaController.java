package com.safedrive.server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recibe las alertas del detector de fatiga y las guarda en la tabla REAL alertas_fatiga
 * (con unidad_id y conductor_id). Acepta el mismo JSON que ya envía fatigueDetector.js.
 */
@RestController
@RequestMapping("/api/alertas")
@CrossOrigin(origins = "*")
public class AlertaController {

    private final JdbcTemplate jdbc;

    public AlertaController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @PostMapping
    public ResponseEntity<?> registrarAlerta(@RequestBody Map<String, Object> body) {
        // 1) Unidad: primero por código ("302-A"), si no por unidadId
        Long unidadId = null;
        String codigo = texto(body.get("codigoUnidad"));
        if (codigo == null) codigo = texto(body.get("unidad"));
        if (codigo != null) {
            unidadId = buscarId("SELECT id FROM unidades WHERE lower(codigo_unidad) = lower(?)", codigo);
        }
        if (unidadId == null) unidadId = numero(body.get("unidadId"));

        // 2) Conductor: primero por nombre (viene del login), si no por conductorId
        Long conductorId = null;
        String nombre = texto(body.get("nombreConductor"));
        if (nombre == null) nombre = texto(body.get("conductor"));
        if (nombre != null) {
            conductorId = buscarId("""
                    SELECT c.id FROM conductores c JOIN usuarios u ON u.id = c.usuario_id
                    WHERE lower(u.nombre_completo) = lower(?)""", nombre);
        }
        if (conductorId == null) conductorId = numero(body.get("conductorId"));

        if (unidadId == null || conductorId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se encontró la unidad o el conductor de la alerta"));
        }

        // 3) Turno (opcional): solo si existe, para no romper la clave foránea
        Long turnoId = numero(body.get("turnoId"));
        if (turnoId != null && buscarId("SELECT id FROM turnos WHERE id = ?", turnoId) == null) turnoId = null;

        String tipo = normalizarTipo(texto(body.get("tipoAlerta")));
        Double ear = decimal(body.get("valorEar"));
        if (ear == null) ear = decimal(body.get("earCalculado"));
        if (ear != null && (ear < 0 || ear > 99)) ear = null;
        Double reaccion = decimal(body.get("tiempoReaccionSegundos"));
        String tramo = texto(body.get("tramoRuta"));

        Long id = jdbc.queryForObject("""
                INSERT INTO alertas_fatiga
                    (turno_id, unidad_id, conductor_id, tipo_alerta, valor_ear, ear_calculado,
                     tiempo_reaccion_segundos, tramo_ruta, fecha_hora)
                VALUES (?::integer, ?::integer, ?::integer, ?, ?::numeric, ?::float8,
                        COALESCE(?::numeric, 1.2), COALESCE(?::varchar, 'Puente Nuevo'), ?)
                RETURNING id""",
                Long.class, turnoId, unidadId, conductorId, tipo, ear, ear, reaccion, tramo,
                Timestamp.valueOf(LocalDateTime.now()));

        System.out.println("🚨 [ALERTA GUARDADA] id=" + id + " unidad=" + codigo + " conductor=" + nombre + " tipo=" + tipo);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("unidadId", unidadId);
        r.put("conductorId", conductorId);
        r.put("tipoAlerta", tipo);
        r.put("valorEar", ear);
        return ResponseEntity.status(HttpStatus.OK).body(r);
    }

    // Últimas 100 alertas (con nombres de unidad y conductor)
    @GetMapping
    public List<Map<String, Object>> listarAlertas() {
        return jdbc.queryForList("""
                SELECT a.id, u.codigo_unidad AS unidad, us.nombre_completo AS conductor, a.tipo_alerta AS "tipoAlerta",
                       COALESCE(a.valor_ear::float8, a.ear_calculado) AS ear, a.tramo_ruta AS tramo,
                       to_char(a.fecha_hora, 'YYYY-MM-DD"T"HH24:MI:SS') AS "fechaHora"
                FROM alertas_fatiga a
                JOIN unidades u ON u.id = a.unidad_id
                JOIN conductores c ON c.id = a.conductor_id
                JOIN usuarios us ON us.id = c.usuario_id
                ORDER BY a.fecha_hora DESC
                LIMIT 100""");
    }

    // ---------- auxiliares ----------
    /** La base solo acepta estos 3 tipos (CHECK constraint). */
    private String normalizarTipo(String t) {
        if (t == null) return "Microsueño";
        String s = t.toLowerCase();
        if (s.contains("bostez")) return "Bostezo frecuente";
        if (s.contains("inclin")) return "Inclinacion cabeza";
        return "Microsueño";
    }

    private Long buscarId(String sql, Object arg) {
        List<Long> r = jdbc.query(sql, (rs, i) -> rs.getLong(1), arg);
        return r.isEmpty() ? null : r.get(0);
    }

    private static String texto(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long numero(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? null : Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double decimal(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? null : Double.parseDouble(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}
