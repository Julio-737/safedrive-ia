package com.safedrive.server.repository;

import com.safedrive.server.model.Turno;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TurnoRepository extends JpaRepository<Turno, Long> {
    List<Turno> findByEstadoTurno(String estadoTurno);
}