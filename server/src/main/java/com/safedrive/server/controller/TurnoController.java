package com.safedrive.server.controller;

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
 * Turnos del conductor sobre la tabla REAL turnos (conductor_id + unidad_id).
 * Acepta el mismo JSON que ya envía app.js al presionar "INICIAR TURNO".
 */
@RestController
@RequestMapping("/api/turnos")
@CrossOrigin(origins = "*")
public class TurnoController {

    private final JdbcTemplate jdbc;

    public TurnoController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // inicio -> El conductor presiona "INICIAR TURNO"
    @PostMapping("/inicio")
    public ResponseEntity<?> iniciarTurno(@RequestBody Map<String, Object> body) {
        String dni = texto(body.get("dniConductor"));
        String nombre = texto(body.get("nombreConductor"));
        String codigo = texto(body.get("unidad"));
        if (codigo == null) codigo = texto(body.get("codigoUnidad"));
        String ruta = texto(body.get("ruta"));
        if (ruta == null) ruta = "SJL -> Cercado de Lima";

        Long conductorId = null;
        if (dni != null) {
            conductorId = buscarId("""
                    SELECT c.id FROM conductores c JOIN usuarios u ON u.id = c.usuario_id
                    WHERE u.dni = ?""", dni);
        }
        if (conductorId == null && nombre != null) {
            conductorId = buscarId("""
                    SELECT c.id FROM conductores c JOIN usuarios u ON u.id = c.usuario_id
                    WHERE lower(u.nombre_completo) = lower(?)""", nombre);
        }
        Long unidadId = codigo == null ? null
                : buscarId("SELECT id FROM unidades WHERE lower(codigo_unidad) = lower(?)", codigo);

        if (conductorId == null || unidadId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se encontró el conductor o la unidad"));
        }

        Timestamp ahora = Timestamp.valueOf(LocalDateTime.now());

        // Si el conductor dejó un turno abierto, se cierra antes de abrir el nuevo
        jdbc.update("""
                UPDATE turnos SET hora_fin = ?, estado_turno = 'finalizado', estado = 'FINALIZADO'
                WHERE conductor_id = ? AND hora_fin IS NULL""", ahora, conductorId);

        Long id = jdbc.queryForObject("""
                INSERT INTO turnos (conductor_id, unidad_id, ruta, hora_inicio, estado_turno, estado)
                VALUES (?, ?, ?, ?, 'activo', 'EN_RUTA')
                RETURNING id""", Long.class, conductorId, unidadId, ruta, ahora);

        System.out.println("🚩 [TURNO INICIADO] id=" + id + " conductor=" + nombre + " unidad=" + codigo);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("conductorId", conductorId);
        r.put("unidadId", unidadId);
        r.put("unidad", codigo);
        r.put("ruta", ruta);
        r.put("estado", "EN_RUTA");
        return ResponseEntity.ok(r);
    }

    // fin -> El conductor presiona "FINALIZAR TURNO"
    @PutMapping("/{id}/fin")
    public ResponseEntity<?> finalizarTurno(@PathVariable("id") long id) {
        Timestamp ahora = Timestamp.valueOf(LocalDateTime.now());
        int n = jdbc.update("""
                UPDATE turnos
                SET hora_fin = ?, estado_turno = 'finalizado', estado = 'FINALIZADO',
                    duracion_minutos = GREATEST(0, (EXTRACT(EPOCH FROM (?::timestamp - hora_inicio)) / 60)::int)
                WHERE id = ?""", ahora, ahora, id);
        if (n == 0) return ResponseEntity.notFound().build();
        System.out.println("🏁 [TURNO FINALIZADO] id=" + id);
        return ResponseEntity.ok(Map.of("id", id, "estado", "FINALIZADO"));
    }

    // GET activos -> conductores actualmente en ruta
    @GetMapping("/activos")
    public List<Map<String, Object>> listarTurnosActivos() {
        return jdbc.queryForList("""
                SELECT t.id, us.nombre_completo AS "nombreConductor", us.dni AS "dniConductor",
                       u.codigo_unidad AS unidad, t.ruta,
                       to_char(t.hora_inicio, 'YYYY-MM-DD"T"HH24:MI:SS') AS "horaInicio"
                FROM turnos t
                JOIN unidades u ON u.id = t.unidad_id
                JOIN conductores c ON c.id = t.conductor_id
                JOIN usuarios us ON us.id = c.usuario_id
                WHERE t.hora_fin IS NULL AND t.hora_inicio > ?
                ORDER BY t.hora_inicio DESC""", Timestamp.valueOf(LocalDateTime.now().minusHours(14)));
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
}
