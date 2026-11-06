package com.safedrive.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alertas_fatiga")
public class AlertaFatiga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String unidad; 

    @Column(nullable = false)
    private String conductor; 

    @Column(nullable = false)
    private String tipoAlerta; 

    private Float valorEar; // Valor del EAR al disparar

    private LocalDateTime fechaHora;

    public AlertaFatiga() {
        this.fechaHora = LocalDateTime.now();
    }

    public AlertaFatiga(String unidad, String conductor, String tipoAlerta, Float valorEar) {
        this.unidad = unidad;
        this.conductor = conductor;
        this.tipoAlerta = tipoAlerta;
        this.valorEar = valorEar;
        this.fechaHora = LocalDateTime.now();
    }

    // Getters y Setters
    public Long getId() { return id; }
    public String getUnidad() { return unidad; }
    public void setUnidad(String unidad) { this.unidad = unidad; }
    public String getConductor() { return conductor; }
    public void setConductor(String conductor) { this.conductor = conductor; }
    public String getTipoAlerta() { return tipoAlerta; }
    public void setTipoAlerta(String tipoAlerta) { this.tipoAlerta = tipoAlerta; }
    public Float getValorEar() { return valorEar; }
    public void setValorEar(Float valorEar) { this.valorEar = valorEar; }
    public LocalDateTime getFechaHora() { return fechaHora; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
}