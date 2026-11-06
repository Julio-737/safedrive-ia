package com.safedrive.server.controller;

import com.safedrive.server.model.Turno;
import com.safedrive.server.repository.TurnoRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/turnos")
@CrossOrigin(origins = "*")
public class TurnoController {

    private final TurnoRepository turnoRepository;

    public TurnoController(TurnoRepository turnoRepository) {
        this.turnoRepository = turnoRepository;
    }

    // inicio -> El conductor presiona "INICIAR TURNO"
    @PostMapping("/inicio")
    public ResponseEntity<Turno> iniciarTurno(@RequestBody Turno turno) {
        Turno nuevoTurno = turnoRepository.save(turno);
        System.out.println("🚩 [TURNO INICIADO]: Conductor " + nuevoTurno.getConductorId() + " en unidad " + nuevoTurno.getUnidadId());
        return ResponseEntity.ok(nuevoTurno);
    }

    // fin -> El conductor presiona "FINALIZAR TURNO"
    @PutMapping("/{id}/fin")
    public ResponseEntity<Turno> finalizarTurno(@PathVariable Long id) {
        return turnoRepository.findById(id).map(turno -> {
            turno.setHoraFin(LocalDateTime.now());
            Turno actualizado = turnoRepository.save(turno);
            System.out.println("🏁 [TURNO FINALIZADO]: ID " + id);
            return ResponseEntity.ok(actualizado);
        }).orElse(ResponseEntity.notFound().build());
    }

    // GET activos -> Ver conductores actualmente en ruta para el supervisor
    @GetMapping("/activos")
    public ResponseEntity<List<Turno>> listarTurnosActivos() {
        return ResponseEntity.ok(turnoRepository.findByEstado("EN_RUTA"));
    }
}