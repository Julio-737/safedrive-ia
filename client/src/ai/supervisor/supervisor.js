// =====================================================================
// PANEL DEL SUPERVISOR — datos reales desde Spring Boot + PostgreSQL
// =====================================================================
import { API_URL } from "../../../config.js";

let pollingTimer = null;

export function stopSupervisorPolling() {
  if (pollingTimer) {
    clearInterval(pollingTimer);
    pollingTimer = null;
  }
}

function startPolling(fn, ms) {
  stopSupervisorPolling();
  fn();
  pollingTimer = setInterval(() => {
    // Si el usuario salió del panel (login, conductor, etc.) se detiene solo,
    // así app.js no necesita ningún código de limpieza.
    if (!document.querySelector(".supervisor-layout")) {
      stopSupervisorPolling();
      return;
    }
    fn();
  }, ms);
}

// ---------- utilidades ----------
const $ = (id) => document.getElementById(id);

const esc = (v) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function setText(id, value) {
  const el = $(id);
  if (el) el.textContent = value;
}

async function api(path, options) {
  const res = await fetch(`${API_URL}${path}`, options);
  if (!res.ok) {
    let msg = `Error ${res.status}`;
    try {
      const j = await res.json();
      if (j && j.error) msg = j.error;
    } catch (_) { /* sin cuerpo JSON */ }
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

function setConn(ok, texto) {
  const el = $("connStatus");
  if (!el) return;
  el.className = `conn-status ${ok ? "ok" : "error"}`;
  el.textContent = ok ? `● ${texto}` : "● Sin conexión con el servidor (¿está corriendo Spring Boot?)";
}

function claseEstado(estado) {
  if (estado === "Fatiga") return "fatiga";
  if (estado === "Normal") return "ok";
  return "off";
}

function verConductor(id, navigateTo) {
  sessionStorage.setItem("detalleConductorId", id);
  navigateTo("detalle-conductor");
}

function celdaConductor(f) {
  if (!f.conductor) return '<span class="text-muted">— sin conductor —</span>';
  return f.conductorActual ? esc(f.conductor) : `${esc(f.conductor)} <span class="text-muted">(último)</span>`;
}

// ---------- punto de entrada ----------
export function initSupervisorView(viewName, navigateTo) {
  stopSupervisorPolling();
  switch (viewName) {
    case "dashboard-supervisor": return initDashboard(navigateTo);
    case "flota":                return initFlota(navigateTo);
    case "detalle-conductor":    return initDetalle(navigateTo);
    case "reportes":             return initReportes();
    case "centro-ia":            return initCentroIa();
  }
}

// =====================================================================
// 1) PANEL GENERAL
// =====================================================================
function initDashboard(navigateTo) {
  const nombre = sessionStorage.getItem("nombreConductor") || "Supervisor";
  setText("perfilNombre", `${nombre} (Supervisor)`);
  setText("perfilAvatar", nombre.split(" ").filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join("") || "SV");

  $("tablaFlotaBody")?.addEventListener("click", (e) => {
    const b = e.target.closest("button[data-conductor-id]");
    if (b && !b.disabled) verConductor(b.dataset.conductorId, navigateTo);
  });

  async function cargar() {
    try {
      const d = await api("/api/supervisor/dashboard");
      if (!$("tablaFlotaBody")) return; // el usuario ya cambió de pantalla

      setText("kpiUnidades", `${d.unidadesEnRuta}/${d.unidadesTotal}`);
      setText("kpiUnidadesSub", `${d.porcentajeOperativas}% de la flota en ruta`);
      setText("supKpiAlerts", d.alertasHoy);
      setText("kpiAlertasSub", d.unidadesEnFatiga > 0
        ? `⚠ ${d.unidadesEnFatiga} unidad(es) en fatiga ahora`
        : `${d.alertasUltimaHora} en la última hora`);
      setText("kpiReaccion", d.reaccionPromedio != null ? `${d.reaccionPromedio} s` : "—");
      setText("kpiReaccionSub", `Promedio ${d.reaccionBase}`);
      setText("kpiConductores", d.conductoresTotal);
      setText("kpiConductoresSub", `${d.conductoresEnTurno} con turno activo`);

      $("kpiAlertasCard")?.classList.toggle("alert-border", d.unidadesEnFatiga > 0);

      $("tablaFlotaBody").innerHTML = d.flota.length === 0
        ? `<tr><td colspan="6" style="text-align:center;color:#8fa0c0;">No hay unidades registradas. Ve a "Unidades / Flota" para agregarlas.</td></tr>`
        : d.flota.map((f) => `
          <tr>
            <td><strong>${esc(f.unidad)}</strong></td>
            <td>${celdaConductor(f)}</td>
            <td>${esc(f.ruta)}</td>
            <td><span class="badge-status ${claseEstado(f.estado)}">${esc(f.estado)}</span></td>
            <td>${f.alertasHoy}</td>
            <td><button class="btn-sm" data-conductor-id="${f.conductorId ?? ""}" ${f.conductorId ? "" : "disabled"}>Ver detalle</button></td>
          </tr>`).join("");

      $("feedAlertasSupervisor").innerHTML = d.ultimasAlertas.length === 0
        ? `<li class="feed-empty">No hay alertas de fatiga registradas en los últimos 7 días.</li>`
        : d.ultimasAlertas.map((a) => `
          <li>
            <span class="time">${a.esHoy ? esc(a.hora) : esc(a.fecha) + " " + esc(a.hora)}</span>
            <strong>Unidad ${esc(a.unidad)}:</strong> ${esc(a.tipoAlerta)} — ${esc(a.conductor)}${a.ear != null ? ` (EAR: ${a.ear})` : ""}${a.tramo ? ` · ${esc(a.tramo)}` : ""}
          </li>`).join("");

      setConn(true, `Conectado a la base de datos · actualizado ${d.actualizado}`);
    } catch (err) {
      console.warn("Dashboard:", err);
      setConn(false);
    }
  }

  startPolling(cargar, 3000); // refresco en vivo cada 3 s
}

// =====================================================================
// 2) UNIDADES / FLOTA
// =====================================================================
function initFlota(navigateTo) {
  const dlg = $("dlgUnidad");

  $("btnNuevaUnidad")?.addEventListener("click", () => {
    setText("dlgError", "");
    dlg?.showModal();
  });
  $("btnCancelarUnidad")?.addEventListener("click", () => dlg?.close());

  $("formUnidad")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await api("/api/supervisor/unidades", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ codigo: $("inCodigo").value, placa: $("inPlaca").value })
      });
      dlg.close();
      $("formUnidad").reset();
      cargar();
    } catch (err) {
      setText("dlgError", err.message);
    }
  });

  $("unitsGrid")?.addEventListener("click", async (e) => {
    const ver = e.target.closest("button[data-conductor-id]");
    if (ver && !ver.disabled) return verConductor(ver.dataset.conductorId, navigateTo);

    const del = e.target.closest("button[data-del]");
    if (del && confirm(`¿Eliminar la unidad ${del.dataset.codigo}?`)) {
      try {
        await api(`/api/supervisor/unidades/${del.dataset.del}`, { method: "DELETE" });
        cargar();
      } catch (err) {
        alert(err.message);
      }
    }
  });

  async function cargar() {
    try {
      const d = await api("/api/supervisor/flota");
      if (!$("unitsGrid")) return;

      setText("flotaTotal", d.total);
      $("unitsGrid").innerHTML = d.unidades.length === 0
        ? `<p class="text-muted">No hay unidades registradas. Usa "+ Registrar Unidad".</p>`
        : d.unidades.map((u) => `
          <div class="unit-card ${u.estado === "Fatiga" ? "alert-border" : ""}">
            <div class="u-header">
              <strong>Unidad ${esc(u.unidad)}</strong>
              <span class="badge-status ${claseEstado(u.estado)}">${esc(u.estado)}</span>
            </div>
            <p>${celdaConductor(u)}</p>
            <small class="text-muted">${esc(u.ruta)}</small>
            <div class="u-meta">
              <span>${u.tiempoEnRuta ? esc(u.tiempoEnRuta) + " EN RUTA" : "SIN TURNO"}</span>
              <span class="${u.alertasHoy > 0 ? "text-red" : ""}">${u.alertasHoy} ALERTAS HOY</span>
              <span class="${u.bateria != null && u.bateria < 30 ? "text-red" : "text-green"}">${u.bateria != null ? u.bateria + "% BATERÍA" : "—"}</span>
            </div>
            <div class="u-actions">
              <button class="btn-sm" data-conductor-id="${u.conductorId ?? ""}" ${u.conductorId ? "" : "disabled"}>Ver conductor</button>
              <button class="btn-sm btn-del" data-del="${u.id}" data-codigo="${esc(u.unidad)}">Eliminar</button>
            </div>
          </div>`).join("");

      setConn(true, "Conectado a la base de datos");
    } catch (err) {
      console.warn("Flota:", err);
      setConn(false);
    }
  }

  startPolling(cargar, 5000);
}

// =====================================================================
// 3) DETALLE DE CONDUCTOR
// =====================================================================
function initDetalle(navigateTo) {
  const id = sessionStorage.getItem("detalleConductorId");

  $("btnVolverDash")?.addEventListener("click", () => navigateTo("dashboard-supervisor"));

  if (!id) {
    setText("dtNombre", "Selecciona un conductor");
    setText("dtMeta", "Entra desde el Panel General o Unidades / Flota con “Ver detalle”.");
    return;
  }

  async function cargar() {
    try {
      const d = await api(`/api/supervisor/conductor/${encodeURIComponent(id)}`);
      if (!$("dtTurnos")) return;

      setText("dtNombre", `${d.nombre} — DNI: ${d.dni}`);
      const partes = [];
      if (d.unidad) partes.push(`Unidad ${d.unidad}`);
      partes.push(d.turnoActivo ? "Turno activo" : "Sin turno activo");
      if (d.licencia) partes.push(`Licencia ${d.licencia}`);
      setText("dtMeta", partes.join(" · "));

      setText("dtAlertasMes", d.alertasMes);
      setText("dtHoras", d.horasMes != null ? `${d.horasMes} h` : "—");
      setText("dtEar", d.reaccionPromedio != null ? `${d.reaccionPromedio} s` : "—");
      setText("dtDesde", d.antiguedad != null ? `${d.antiguedad} años` : "—");

      $("dtTurnos").innerHTML = d.turnos.length === 0
        ? `<tr><td colspan="5" style="text-align:center;color:#8fa0c0;">Sin turnos registrados.</td></tr>`
        : d.turnos.map((t) => `
          <tr>
            <td>${esc(t.fecha)}</td>
            <td>${esc(t.unidad)}</td>
            <td>${esc(t.duracion)}</td>
            <td>${t.alertas}</td>
            <td><span class="badge-status ${t.enCurso ? "ok" : (t.alertas >= 2 ? "fatiga" : "off")}">${t.enCurso ? "En curso" : (t.alertas >= 2 ? "Con alertas" : "Finalizado")}</span></td>
          </tr>`).join("");

      $("dtAlertas").innerHTML = d.alertasRecientes.length === 0
        ? `<li class="feed-empty">Este conductor no tiene alertas registradas.</li>`
        : d.alertasRecientes.map((a) => `
          <li><span class="time">${esc(a.fecha)} ${esc(a.hora)}</span> ${esc(a.tipoAlerta)} — Unidad ${esc(a.unidad)}${a.ear != null ? ` (EAR: ${a.ear})` : ""}</li>`).join("");

      setConn(true, "Conectado a la base de datos");
    } catch (err) {
      console.warn("Detalle conductor:", err);
      setConn(false);
    }
  }

  startPolling(cargar, 5000);
}

// =====================================================================
// 4) REPORTES
// =====================================================================
function initReportes() {
  let dias = 7;

  const marcar = () => {
    $("btnSemana")?.classList.toggle("active", dias === 7);
    $("btnMes")?.classList.toggle("active", dias === 30);
  };
  $("btnSemana")?.addEventListener("click", () => { dias = 7; marcar(); cargar(); });
  $("btnMes")?.addEventListener("click", () => { dias = 30; marcar(); cargar(); });

  $("btnExportar")?.addEventListener("click", () => {
    const a = document.createElement("a");
    a.href = `${API_URL}/api/supervisor/reportes/exportar?dias=${dias}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
  });

  const lista = (items, campo) => items.length === 0
    ? `<p class="text-muted">Sin datos en este período.</p>`
    : items.map((i) => `<div class="rank-row"><span>${esc(i[campo])}</span><strong>${i.total}</strong></div>`).join("");

  async function cargar() {
    try {
      const d = await api(`/api/supervisor/reportes?dias=${dias}`);
      if (!$("repBars")) return;

      setText("repTitulo", dias === 7 ? "Alertas por Día (Últimos 7 días)" : "Alertas por Día (Últimos 30 días)");
      setText("repResumen", `Total: ${d.total} alertas · Promedio: ${d.promedioDiario} por día · ${d.desde} al ${d.hasta}`);

      const bars = $("repBars");
      bars.classList.toggle("dense", dias > 7);
      bars.innerHTML = d.porDia.map((x) => {
        const pct = d.maxDia > 0 ? Math.max(Math.round((x.total / d.maxDia) * 100), x.total > 0 ? 8 : 2) : 2;
        const esMax = d.maxDia > 0 && x.total === d.maxDia;
        return `<div class="bar-col" title="${esc(x.fecha)}: ${x.total} alertas">
                  <div class="bar ${esMax ? "red" : ""}" style="height:${pct}%">${x.total > 0 ? x.total : ""}</div>
                  <span>${esc(x.etiqueta)}</span>
                </div>`;
      }).join("");

      $("repTipos").innerHTML = d.porTipo.length === 0
        ? `<li class="feed-empty">Sin alertas en este período.</li>`
        : d.porTipo.map((t) => `<li><strong>${t.porcentaje}%</strong> ${esc(t.tipo)} (${t.total})</li>`).join("");

      $("repUnidades").innerHTML = lista(d.porUnidad, "unidad");
      $("repConductores").innerHTML = lista(d.porConductor, "conductor");

      setConn(true, "Conectado a la base de datos");
    } catch (err) {
      console.warn("Reportes:", err);
      setConn(false);
    }
  }

  marcar();
  startPolling(cargar, 10000);
}

// =====================================================================
// 5) CENTRO IA
// =====================================================================
function claseCalor(v) {
  if (v <= 2) return "h-low";
  if (v <= 4) return "h-med";
  if (v <= 7) return "h-high";
  return "h-crit";
}

function initCentroIa() {
  async function cargar() {
    try {
      const d = await api("/api/supervisor/centro-ia");
      if (!$("heatHead")) return;

      $("heatHead").innerHTML =
        `<tr><th>TRAMO</th>${d.franjas.map((f) => `<th>${esc(f)}</th>`).join("")}<th>TOTAL</th></tr>`;

      $("heatBody").innerHTML = d.matriz.length === 0
        ? `<tr><td colspan="${d.franjas.length + 2}" style="text-align:center;color:#8fa0c0;">Sin datos.</td></tr>`
        : d.matriz.map((fila) => `
          <tr>
            <td class="unit-col">${esc(fila.tramo)}</td>
            ${fila.valores.map((v) => `<td class="${claseCalor(v)}">${v}</td>`).join("")}
            <td class="total-col">${fila.total}</td>
          </tr>`).join("");

      setText("hallazgo", `* Hallazgo automático: ${d.hallazgo}`);

      $("recomendaciones").innerHTML = d.recomendaciones.map((r) =>
        `<li class="nivel-${esc(r.nivel)}"><strong>${esc(r.titulo)}:</strong> ${esc(r.detalle)}</li>`).join("");

      setConn(true, "Conectado a la base de datos");
    } catch (err) {
      console.warn("Centro IA:", err);
      setConn(false);
    }
  }

  startPolling(cargar, 10000);
}
