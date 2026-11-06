package com.safedrive.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "turnos")
public class Turno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conductor_id", nullable = false)
    private Integer conductorId;

    @Column(name = "unidad_id", nullable = false)
    private Integer unidadId;

    @Column(name = "ruta", nullable = false)
    private String ruta;

    @Column(name = "hora_inicio", nullable = false)
    private LocalDateTime horaInicio;

    @Column(name = "hora_fin")
    private LocalDateTime horaFin;

    @Column(name = "duracion_minutos")
    private Integer duracionMinutos;

    @Column(name = "horas_descanso_previas")
    private Float horasDescansoPrevias;

    @Column(name = "estado_turno", length = 20)
    private String estadoTurno; 

    public Turno() {
        this.horaInicio = LocalDateTime.now();
        this.estadoTurno = "activo";
        this.duracionMinutos = 0;
        this.horasDescansoPrevias = 7.5f;
    }

    public Turno(Integer conductorId, Integer unidadId, String ruta) {
        this.conductorId = conductorId;
        this.unidadId = unidadId;
        this.ruta = ruta;
        this.horaInicio = LocalDateTime.now();
        this.estadoTurno = "activo";
        this.duracionMinutos = 0;
        this.horasDescansoPrevias = 7.5f;
    }

    // Getters y Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getConductorId() { return conductorId; }
    public void setConductorId(Integer conductorId) { this.conductorId = conductorId; }

    public Integer getUnidadId() { return unidadId; }
    public void setUnidadId(Integer unidadId) { this.unidadId = unidadId; }

    public String getRuta() { return ruta; }
    public void setRuta(String ruta) { this.ruta = ruta; }

    public LocalDateTime getHoraInicio() { return horaInicio; }
    public void setHoraInicio(LocalDateTime horaInicio) { this.horaInicio = horaInicio; }

    public LocalDateTime getHoraFin() { return horaFin; }
    public void setHoraFin(LocalDateTime horaFin) { this.horaFin = horaFin; }

    public Integer getDuracionMinutos() { return duracionMinutos; }
    public void setDuracionMinutos(Integer duracionMinutos) { this.duracionMinutos = duracionMinutos; }

    public Float getHorasDescansoPrevias() { return horasDescansoPrevias; }
    public void setHorasDescansoPrevias(Float horasDescansoPrevias) { this.horasDescansoPrevias = horasDescansoPrevias; }

    public String getEstadoTurno() { return estadoTurno; }
    public void setEstadoTurno(String estadoTurno) { this.estadoTurno = estadoTurno; }
}