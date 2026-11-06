package com.safedrive.server.controller;

import com.safedrive.server.model.AlertaFatiga;
import com.safedrive.server.repository.AlertaFatigaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/alertas")
@CrossOrigin(origins = "*") // Permite peticiones desde el Live Server (http://127.0.0.1:5500)
public class AlertaController {

    private final AlertaFatigaRepository alertaRepository;

    public AlertaController(AlertaFatigaRepository alertaRepository) {
        this.alertaRepository = alertaRepository;
    }

    // Endpoint POST: Recibe la alerta de la cámara en vivo (lo dejas tal cual)
    @PostMapping
    public ResponseEntity<AlertaFatiga> registrarAlerta(@RequestBody AlertaFatiga alerta) {
        if (alerta.getFechaHora() == null) {
            alerta.setFechaHora(LocalDateTime.now());
        }
        AlertaFatiga guardada = alertaRepository.save(alerta);
        System.out.println("🚨 [ALERTA GUARDADA]: Unidad " + guardada.getUnidadId());
        return ResponseEntity.ok(guardada);
    }

    // Endpoint GET: Obtener todas las alertas en bruto
    @GetMapping
    public ResponseEntity<List<AlertaFatiga>> listarAlertas() {
        return ResponseEntity.ok(alertaRepository.findAll());
    }

    // Endpoint GET nuevo: Datos consolidados para el Dashboard del Supervisor
    @GetMapping("/dashboard-resumen")
    public ResponseEntity<Map<String, Object>> obtenerResumenDashboard() {
        Map<String, Object> respuesta = new HashMap<>();

        // Traemos todas las alertas de la base de datos
        List<AlertaFatiga> todas = alertaRepository.findAll();

        // 1. Contar alertas ocurridas el día de hoy
        LocalDate hoy = LocalDate.now();
        long alertasHoy = todas.stream()
                .filter(a -> a.getFechaHora() != null && a.getFechaHora().toLocalDate().isEqual(hoy))
                .count();

        // 2. Últimas 5 alertas para el feed en vivo (las más recientes primero)
        List<AlertaFatiga> ultimas = new ArrayList<>(todas);
        Collections.reverse(ultimas);
        if (ultimas.size() > 5) {
            ultimas = ultimas.subList(0, 5);
        }

        // 3. Determinar el estado de cada unidad de la flota (San Juan de Lurigancho)
        LocalDateTime haceDiezMinutos = LocalDateTime.now().minusMinutes(10);
        
        List<Map<String, Object>> flota = Arrays.asList(
            armarFilaUnidad("302-A", "J. Pérez Gómez", "SJL - Cercado", todas, haceDiezMinutos),
            armarFilaUnidad("302-B", "L. Ramos Quispe", "SJL - Cercado", todas, haceDiezMinutos),
            armarFilaUnidad("302-C", "M. Ticona Flores", "Cercado - SJL", todas, haceDiezMinutos),
            armarFilaUnidad("302-D", "A. Rojas Medina", "SJL - Cercado", todas, haceDiezMinutos),
            armarFilaUnidad("302-E", "R. Huamán Silva", "Cercado - SJL", todas, haceDiezMinutos)
        );

        respuesta.put("totalAlertasHoy", alertasHoy);
        respuesta.put("ultimasAlertas", ultimas);
        respuesta.put("flota", flota);

        return ResponseEntity.ok(respuesta);
    }

    // Método auxiliar para cruzar datos de cada bus con sus alertas en Supabase
    private Map<String, Object> armarFilaUnidad(String unidad, String conductor, String ruta, List<AlertaFatiga> todas, LocalDateTime limite) {
        Map<String, Object> map = new HashMap<>();
        map.put("unidad", unidad);
        map.put("conductor", conductor);
        map.put("ruta", ruta);

        long incidentesUnidad = todas.stream()
                .filter(a -> a.getUnidadId() != null && String.valueOf(a.getUnidadId()).equalsIgnoreCase(unidad))
                .count();

        boolean tieneFatigaActiva = todas.stream()
                .filter(a -> a.getUnidadId() != null && String.valueOf(a.getUnidadId()).equalsIgnoreCase(unidad))
                .anyMatch(a -> a.getFechaHora() != null && a.getFechaHora().isAfter(limite));

        map.put("estado", tieneFatigaActiva ? "Fatiga" : "Normal");
        map.put("totalAlertas", incidentesUnidad);
        return map;
    }
}