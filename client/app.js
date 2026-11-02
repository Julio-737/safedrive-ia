import { initAI, startCamera, stopCamera } from "./src/ai/fatigueDetector.js";

const viewport = document.getElementById("app-viewport");
let shiftTimerInterval = null;
let shiftStartTime = null;
let supervisorPollingInterval = null;
let currentTurnoId = null;

// Enrutador centralizado
export async function navigateTo(viewName) {
  try {
    // Si salimos de monitoreo, apagar cámara
    if (viewName !== "monitoreo") {
      stopCamera();
      if (shiftTimerInterval && viewName !== "fin-turno") {
        clearInterval(shiftTimerInterval);
      }
    }

    // Limpiar polling del supervisor si cambiamos a otra pantalla
    if (viewName !== "dashboard-supervisor" && supervisorPollingInterval) {
      clearInterval(supervisorPollingInterval);
      supervisorPollingInterval = null;
    }

    const response = await fetch(`views/${viewName}.html`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    
    viewport.innerHTML = await response.text();
    bindEvents(viewName);
  } catch (err) {
    console.error(`Error al cargar views/${viewName}.html:`, err);
    viewport.innerHTML = `
      <div style="padding:2rem;text-align:center;">
        <h3>Error al cargar: views/${viewName}.html</h3>
        <p class="text-muted">Asegúrate de que el archivo exista en la carpeta views/</p>
      </div>`;
  }
}

function bindEvents(viewName) {
  // 1. LOGIN
  if (viewName === "login") {
    let role = "conductor";
    const btnCond = document.getElementById("btnRoleConductor");
    const btnSup = document.getElementById("btnRoleSupervisor");
    const form = document.getElementById("formLogin");

    btnCond?.addEventListener("click", () => {
      role = "conductor";
      btnCond.classList.add("active");
      btnSup?.classList.remove("active");
    });

    btnSup?.addEventListener("click", () => {
      role = "supervisor";
      btnSup.classList.add("active");
      btnCond?.classList.remove("active");
    });

    form?.addEventListener("submit", async (e) => {
      e.preventDefault();

      const inputDni = document.getElementById("inputDni") || document.querySelector("input[type='text']");
      const inputPassword = document.getElementById("inputPassword") || document.querySelector("input[type='password']");

      const dni = inputDni ? inputDni.value.trim() : "";
      const password = inputPassword ? inputPassword.value.trim() : "";

      const dniFinal = dni || (role === "conductor" ? "76543210" : "10457812");
      const passwordFinal = password || (role === "conductor" ? "123456" : "admin123");

      try {
        const res = await fetch("http://localhost:8080/api/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ dni: dniFinal, password: passwordFinal })
        });

        if (res.ok) {
          const data = await res.json();
          console.log("✅ Sesión autenticada en PostgreSQL/Supabase:", data);

          sessionStorage.setItem("usuarioId", data.id);
          sessionStorage.setItem("dniConductor", data.dni);
          sessionStorage.setItem("nombreConductor", data.nombreCompleto);
          sessionStorage.setItem("rol", data.rol);
          sessionStorage.setItem("conductorId", "1");
          sessionStorage.setItem("unidadId", "1");
          sessionStorage.setItem("codigoUnidad", "302-A");

          if (data.rol === "supervisor") {
            navigateTo("dashboard-supervisor");
          } else {
            navigateTo("onboarding");
          }
          return;
        } else {
          console.warn("Credenciales no válidas en BD, ingresando en modo demo local.");
        }
      } catch (err) {
        console.warn("Servidor Spring Boot offline: continuando en modo demo local.", err);
      }

      // Fallback si la API no está lista
      sessionStorage.setItem("usuarioId", "1");
      sessionStorage.setItem("conductorId", "1");
      sessionStorage.setItem("unidadId", "1");
      sessionStorage.setItem("nombreConductor", role === "conductor" ? "Juan Pérez Gómez" : "Mitsy Paz Mendoza");
      sessionStorage.setItem("codigoUnidad", "302-A");

      if (role === "conductor") {
        navigateTo("onboarding");
      } else {
        navigateTo("dashboard-supervisor");
      }
    });
  }

    // 2. ONBOARDING
  if (viewName === "onboarding") {
    const btnStart = document.getElementById("btnOnboardingStart");
    if (btnStart) {
      btnStart.onclick = (e) => {
        e.preventDefault();
        navigateTo("checklist");
      };
    }
  }
  

// 3. CHECKLIST
  if (viewName === "checklist") {
    // A. Cargar datos del conductor asignado en la sesión
    const driverEl = document.getElementById("checklistDriver");
    const unitEl = document.getElementById("checklistUnit");

    if (driverEl) driverEl.innerText = sessionStorage.getItem("nombreConductor") || "Juan Pérez Gómez";
    if (unitEl) unitEl.innerText = sessionStorage.getItem("codigoUnidad") || "302-A";

    // B. Reloj y fecha en vivo respetando el diseño original (formato 07:45 AM)
    function updateClock() {
      const now = new Date();
      const timeEl = document.getElementById("checklistTime");
      const dateEl = document.getElementById("checklistDate");

      if (timeEl) {
        let hours = now.getHours();
        const minutes = now.getMinutes().toString().padStart(2, "0");
        const ampm = hours >= 12 ? "PM" : "AM";
        hours = hours % 12;
        hours = hours ? hours : 12;
        const strHours = hours.toString().padStart(2, "0");
        
        timeEl.innerText = `${strHours}:${minutes} ${ampm}`;
      }

      if (dateEl) {
        const opcionesFecha = { weekday: "long", day: "2-digit", month: "long", year: "numeric" };
        const fechaFormateada = now.toLocaleDateString("es-PE", opcionesFecha);
        dateEl.innerText = fechaFormateada.charAt(0).toUpperCase() + fechaFormateada.slice(1);
      }
    }

    updateClock();
    const clockInterval = setInterval(updateClock, 1000);

    // C. Manejo del inicio de turno
    document.getElementById("btnStartShift")?.addEventListener("click", async () => {
      clearInterval(clockInterval);

      try {
        const res = await fetch("http://localhost:8080/api/turnos/inicio", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            dniConductor: sessionStorage.getItem("dniConductor") || "76543210",
            nombreConductor: sessionStorage.getItem("nombreConductor") || "Juan Pérez Gómez",
            unidad: sessionStorage.getItem("codigoUnidad") || "302-A",
            ruta: "SJL → Cercado de Lima"
          })
        });

        if (res.ok) {
          const turnoCreado = await res.json();
          currentTurnoId = turnoCreado.id;
          sessionStorage.setItem("turnoId", currentTurnoId.toString());
        } else {
          sessionStorage.setItem("turnoId", "1");
        }
      } catch (err) {
        console.warn("Servidor offline: usando ID de turno local", err);
        sessionStorage.setItem("turnoId", "1");
      }

      shiftStartTime = new Date();
      navigateTo("monitoreo");
    });
  }



  // 4. CABINA DE MONITOREO
  if (viewName === "monitoreo") {
    initAI();
    startCamera();

    const timerEl = document.getElementById("driveTimer");
    if (timerEl && shiftStartTime) {
      clearInterval(shiftTimerInterval);
      shiftTimerInterval = setInterval(() => {
        const diff = Math.floor((new Date() - shiftStartTime) / 1000);
        const h = Math.floor(diff / 3600).toString().padStart(2, "0");
        const m = Math.floor((diff % 3600) / 60).toString().padStart(2, "0");
        const s = (diff % 60).toString().padStart(2, "0");
        timerEl.innerText = `${h}:${m}:${s}`;
      }, 1000);
    }

    document.getElementById("btnEndShift")?.addEventListener("click", async () => {
      if (currentTurnoId) {
        try {
          await fetch(`http://localhost:8080/api/turnos/${currentTurnoId}/fin`, {
            method: "PUT"
          });
          console.log(" Turno finalizado correctamente en BD");
        } catch (err) {
          console.warn("Error al finalizar turno en servidor", err);
        }
      }
      navigateTo("fin-turno");
    });
    document.getElementById("btnNavAlertas")?.addEventListener("click", () => {
      navigateTo("alertas-conductor");
    });
    document.getElementById("btnNavAjustes")?.addEventListener("click", () => {
      navigateTo("config-admin");
    });
  }

  // 5. ALERTAS CONDUCTOR
  if (viewName === "alertas-conductor") {
    document.getElementById("btnBackToMonitoreo")?.addEventListener("click", () => {
      navigateTo("monitoreo");
    });
  }

  // 6. FIN DE TURNO
  if (viewName === "fin-turno") {
    document.getElementById("btnEndSession")?.addEventListener("click", () => {
      navigateTo("login");
    });
  }

  // 7. CONFIG ADMIN
  if (viewName === "config-admin") {
    const range = document.getElementById("rangeEar");
    const val = document.getElementById("valEar");
    range?.addEventListener("input", (e) => {
      if (val) val.innerText = e.target.value;
    });
    document.getElementById("btnSaveConfig")?.addEventListener("click", () => {
      navigateTo("monitoreo");
    });
  }

  // 8. SUPERVISOR (Navegación del Menú Lateral)
  document.getElementById("menuDash")?.addEventListener("click", () => navigateTo("dashboard-supervisor"));
  document.getElementById("menuFlota")?.addEventListener("click", () => navigateTo("flota"));
  document.getElementById("menuReportes")?.addEventListener("click", () => navigateTo("reportes"));
  document.getElementById("menuCentroIa")?.addEventListener("click", () => navigateTo("centro-ia"));
  document.getElementById("btnSupervisorLogout")?.addEventListener("click", () => navigateTo("login"));

  // 9. VISTA DETALLE CONDUCTOR
  if (viewName === "detalle-conductor") {
    document.getElementById("btnVolverDash")?.addEventListener("click", () => {
      navigateTo("dashboard-supervisor");
    });
  }

  // 10. DASHBOARD SUPERVISOR (Consumo de datos reales desde Spring Boot)
  if (viewName === "dashboard-supervisor") {
    document.getElementById("btnVerConductor")?.addEventListener("click", () => {
      navigateTo("detalle-conductor");
    });

    // Función asíncrona para consultar el resumen consolidado
    async function sincronizarDashboardSupervisor() {
      try {
        const res = await fetch("http://localhost:8080/api/alertas/dashboard-resumen");
        if (!res.ok) throw new Error("Error en servidor Spring Boot");

        const data = await res.json();

        // A. Actualizar KPI numérico de alertas de hoy
        const kpiAlerts = document.getElementById("supKpiAlerts");
        if (kpiAlerts) kpiAlerts.innerText = data.totalAlertasHoy;

        // B. Renderizar la tabla de flota con datos de cada unidad
        const tbody = document.getElementById("tablaFlotaBody");
        if (tbody && data.flota) {
          tbody.innerHTML = data.flota.map(item => `
            <tr>
              <td><strong>${item.unidad}</strong></td>
              <td>${item.conductor}</td>
              <td>${item.ruta}</td>
              <td>
                <span class="badge-status ${item.estado === 'Fatiga' ? 'fatiga' : 'ok'}">
                  ${item.estado}
                </span>
              </td>
              <td>
                <button class="btn-sm" onclick="alert('Unidad: ${item.unidad}\\nConductor: ${item.conductor}\\nTotal Alertas Registradas: ${item.totalAlertas}')">
                  Ver Estado
                </button>
              </td>
            </tr>
          `).join("");
        }

        // C. Renderizar feed de incidentes en tiempo real
        const feed = document.getElementById("feedAlertasSupervisor");
        if (feed && data.ultimasAlertas) {
          if (data.ultimasAlertas.length === 0) {
            feed.innerHTML = "<li style='color:#8fa0c0;'>No hay alertas de fatiga registradas hoy.</li>";
          } else {
            feed.innerHTML = data.ultimasAlertas.map(a => {
              const hora = a.fechaHora ? a.fechaHora.split("T")[1].substring(0, 5) : "--:--";
              return `
                <li>
                  <span class="time">${hora}</span>
                  <strong>Unidad ${a.unidad}:</strong> ${a.tipoAlerta} detectado (EAR: ${a.earCalculado})
                </li>
              `;
            }).join("");
          }
        }
      } catch (error) {
        console.warn("Spring Boot no disponible o en proceso de carga:", error);
      }
    }

    // Primera carga al entrar al panel
    sincronizarDashboardSupervisor();

    // Actualización automática cada 3 segundos para refresco en vivo
    supervisorPollingInterval = setInterval(sincronizarDashboardSupervisor, 3000);
  }
}

// Iniciar aplicación
navigateTo("login");