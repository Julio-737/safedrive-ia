import { initAI, startCamera, stopCamera } from "./src/ai/fatigueDetector.js";
import { initSupervisorView } from "./src/ai/supervisor/supervisor.js";

const API_URL = "http://127.0.0.1:8080";

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

      if (!dni || !password) {
        alert("Por favor, ingresa tu DNI y contraseña.");
        return;
      }

      try {
        const res = await fetch(`${API_URL}/api/auth/login`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ dni, password })
        });

        if (res.status === 401) {
          alert("❌ Credenciales incorrectas. Verifique el DNI o la contraseña.");
          return;
        }

        if (!res.ok) {
          alert(`⚠️ Error del servidor (${res.status}): No se pudo autenticar.`);
          return;
        }

        const data = await res.json();
        console.log("✅ Sesión autenticada en PostgreSQL/Supabase:", data);

        // Guardar datos reales devueltos por la BD
        sessionStorage.setItem("usuarioId", data.id);
        sessionStorage.setItem("dniConductor", data.dni);
        sessionStorage.setItem("nombreConductor", data.nombreCompleto);
        sessionStorage.setItem("rol", data.rol);
        sessionStorage.setItem("conductorId", "1");
        sessionStorage.setItem("unidadId", "1");
        sessionStorage.setItem("codigoUnidad", "302-A");

        alert(`¡Bienvenido(a) ${data.nombreCompleto}!`);

        if (data.rol === "supervisor" || role === "supervisor") {
          navigateTo("dashboard-supervisor");
        } else {
          navigateTo("onboarding");
        }

      } catch (err) {
        console.error("Error al conectar con el backend:", err);
        const usarDemo = confirm("⚠️ No se pudo conectar con la base de datos (Backend Offline).\n\n¿Deseas ingresar en MODO DEMO LOCAL para probar la interfaz?");
        
        if (usarDemo) {
          sessionStorage.setItem("usuarioId", "1");
          sessionStorage.setItem("conductorId", "1");
          sessionStorage.setItem("unidadId", "1");
          sessionStorage.setItem("nombreConductor", role === "conductor" ? "Juan Pérez Gómez" : "Mitsy Paz Mendoza");
          sessionStorage.setItem("codigoUnidad", "302-A");

          if (role === "conductor") navigateTo("onboarding");
          else navigateTo("dashboard-supervisor");
        }
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

    // B. Reloj y fecha en vivo  (formato 07:45 AM)
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
        const res = await fetch(`${API_URL}/api/turnos/inicio`, {
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
      sessionStorage.setItem("turnoInicio", shiftStartTime.toISOString());
      sessionStorage.setItem("alertasTurno", "0");
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
          await fetch(`${API_URL}/api/turnos/${currentTurnoId}/fin`, {
            method: "PUT"
          });
          console.log(" Turno finalizado correctamente en BD");
        } catch (err) {
          console.warn("Error al finalizar turno en servidor", err);
        }
        sessionStorage.setItem("turnoFin", new Date().toISOString());
        navigateTo("fin-turno");
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

    async function cargarAlertasConductor() {
      const contenedor = document.getElementById("listaAlertasConductor");
      const cntHoy = document.getElementById("cntHoy");
      const cntSemana = document.getElementById("cntSemana");

      try {
        console.log("🔍 Solicitando alertas a Render...");
        const res = await fetch(`${API_URL}/api/alertas`);
        
        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const data = await res.json();
        console.log("📦 Alertas obtenidas desde Render:", data);

        const alertas = Array.isArray(data) ? data : [];

        if (cntHoy) cntHoy.innerText = alertas.length;
        if (cntSemana) cntSemana.innerText = alertas.length;

        if (alertas.length > 0 && contenedor) {
          contenedor.innerHTML = alertas.map(a => {
            let horaFormateada = "Reciente";
            
            if (a.fechaHora) {
              const d = new Date(a.fechaHora);
              if (!isNaN(d.getTime())) {
                horaFormateada = d.toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit", hour12: true });
              }
            }

            const tipo = a.tipoAlerta || "Microsueño";
            const esBostezo = tipo.toLowerCase().includes("bostezo");
            const icono = esBostezo ? "🥱" : "⏱";
            const unidadCodigo = a.unidad || "302-A";

            return `
              <div class="turn-summary-card p-3 d-flex justify-content-between align-items-center mb-2" style="background: #132238; border-radius: 12px; padding: 14px;">
                <div class="d-flex align-items-center gap-3">
                  <span style="font-size: 1.5rem;">${icono}</span>
                  <div class="text-start">
                    <strong style="color: #f8fafc; display: block; font-size: 0.95rem;">${tipo}</strong>
                    <span style="font-size: 0.8rem; color: #94a3b8;">
                      Duración: ${a.duracionSegundos || 1.5}s · Cabina Unidad ${unidadCodigo}
                    </span>
                  </div>
                </div>
                <span style="color: #38bdf8; font-weight: bold; font-size: 0.85rem;">${horaFormateada}</span>
              </div>
            `;
          }).join("");
        } else if (contenedor) {
          contenedor.innerHTML = `
            <div class="turn-summary-card p-3 text-center text-muted" style="background: #132238; border-radius: 12px; padding: 15px;">
              No hay eventos de fatiga registrados en este turno.
            </div>`;
        }
      } catch (err) {
        console.warn("⚠️ Error cargando alertas:", err);
        if (cntHoy) cntHoy.innerText = "0";
        if (cntSemana) cntSemana.innerText = "0";
        if (contenedor) {
          contenedor.innerHTML = `
            <div class="turn-summary-card p-3 text-center text-muted" style="background: #132238; border-radius: 12px; padding: 15px;">
              No hay eventos de fatiga registrados en este turno.
            </div>`;
        }
      }
    }

    // Pequeña espera para asegurar que la vista esté renderizada en el DOM
    setTimeout(cargarAlertasConductor, 50);
  }


  // 6. FIN DE TURNO
  
  if (viewName === "fin-turno") {
    const inicio = new Date(sessionStorage.getItem("turnoInicio") || Date.now());
    const fin = new Date(sessionStorage.getItem("turnoFin") || Date.now());
    const alertas = parseInt(sessionStorage.getItem("alertasTurno") || "0");

    const totalSeg = Math.max(1, Math.floor((fin - inicio) / 1000));
    const h = Math.floor(totalSeg / 3600);
    const m = Math.floor((totalSeg % 3600) / 60);
    const s = totalSeg % 60;

    // Cada alerta se registra con 2.5 s de duración (igual que fatigueDetector.js)
    const segFatiga = alertas * 2.5;
    const pctNormal = Math.max(0, 100 - (segFatiga / totalSeg) * 100);

    const hora = (d) => d.toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit", hour12: true });
    const poner = (id, txt) => { const el = document.getElementById(id); if (el) el.innerText = txt; };

    poner("finNombre", sessionStorage.getItem("nombreConductor") || "conductor");
    poner("finDuracion", h > 0 ? `${h}h ${m}m` : (m > 0 ? `${m}m ${s}s` : `${s}s`));
    poner("finTurnoAlerts", alertas);
    poner("finNormal", `${pctNormal.toFixed(pctNormal >= 99.95 ? 0 : 1)}%`);
    poner("finInicio", hora(inicio));
    poner("finFin", hora(fin));
    poner("finUnidad", sessionStorage.getItem("codigoUnidad") || "302-A");

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



      // 10. PANEL DEL SUPERVISOR (datos reales) - la lógica está en src/supervisor/supervisor.js
  if (["dashboard-supervisor", "flota", "detalle-conductor", "reportes", "centro-ia"].includes(viewName)) {
    initSupervisorView(viewName, navigateTo);
  }

  }


// Iniciar aplicación
navigateTo("login");