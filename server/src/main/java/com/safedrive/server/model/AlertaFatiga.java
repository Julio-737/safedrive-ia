package com.safedrive.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alertas_fatiga")
public class AlertaFatiga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turno_id")
    private Integer turnoId;

    @Column(name = "unidad_id", nullable = false)
    private Integer unidadId;

    @Column(name = "conductor_id", nullable = false)
    private Integer conductorId;

    @Column(name = "tipo_alerta", nullable = false)
    private String tipoAlerta; 

    @Column(name = "valor_ear")
    private Float valorEar; // Valor del EAR al disparar

    @Column(name = "duracion_segundos")
    private Float duracionSegundos;

    @Column(name = "tiempo_reaccion_segundos")
    private Float tiempoReaccionSegundos;

    @Column(name = "fecha_hora")
    private LocalDateTime fechaHora;

    public AlertaFatiga() {
        this.fechaHora = LocalDateTime.now();
    }

    // Getters y Setters completos para que Spring Boot pueda armar el objeto
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getTurnoId() { return turnoId; }
    public void setTurnoId(Integer turnoId) { this.turnoId = turnoId; }

    public Integer getUnidadId() { return unidadId; }
    public void setUnidadId(Integer unidadId) { this.unidadId = unidadId; }

    public Integer getConductorId() { return conductorId; }
    public void setConductorId(Integer conductorId) { this.conductorId = conductorId; }

    public String getTipoAlerta() { return tipoAlerta; }
    public void setTipoAlerta(String tipoAlerta) { this.tipoAlerta = tipoAlerta; }

    public Float getValorEar() { return valorEar; }
    public void setValorEar(Float valorEar) { this.valorEar = valorEar; }

    public Float getDuracionSegundos() { return duracionSegundos; }
    public void setDuracionSegundos(Float duracionSegundos) { this.duracionSegundos = duracionSegundos; }

    public Float getTiempoReaccionSegundos() { return tiempoReaccionSegundos; }
    public void setTiempoReaccionSegundos(Float tiempoReaccionSegundos) { this.tiempoReaccionSegundos = tiempoReaccionSegundos; }

    public LocalDateTime getFechaHora() { return fechaHora; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
}