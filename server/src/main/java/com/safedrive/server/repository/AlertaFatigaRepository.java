package com.safedrive.server.repository;

import com.safedrive.server.model.AlertaFatiga;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AlertaFatigaRepository extends JpaRepository<AlertaFatiga, Long> {

    // Obtener las últimas alertas ordenadas por fecha reciente
    List<AlertaFatiga> findTop10ByOrderByFechaHoraDesc();

    // Contar alertas ocurridas hoy
    long countByFechaHoraAfter(LocalDateTime fecha);

    // Obtener todas las alertas de una unidad específica
    List<AlertaFatiga> findByUnidadIdOrderByFechaHoraDesc(Integer unidadId);
}