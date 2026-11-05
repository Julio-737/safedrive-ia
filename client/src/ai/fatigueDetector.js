import { FaceLandmarker, FilesetResolver } from "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/+esm";

let faceLandmarker = null;
let cameraStream = null;
let lastVideoTime = -1;
let eyesClosedStart = null;
let isFatigued = false;
let alarmInterval = null;

// para la detección de fatiga
const EAR_THRESHOLD = 0.22;       // Umbral para la detección de ojos cerrados
const FATIGUE_MS = 1500;          // 1.5 segundos de ojos cerrados para activar la alerta
let mouthOpenStart = null;        // Para la detección de bostezos
let isYawning = false;            // Estado de detección de bostezo

// Indices de referencia de los ojos según el modelo de MediaPipe Face 
const LEFT_EYE = [33, 160, 158, 133, 153, 144];
const RIGHT_EYE = [362, 385, 387, 263, 373, 380];
const MOUTH_POINTS = [13, 14, 78, 308];

// para el beep de alerta
let audioCtx = null;

function getAudioContext() {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  if (audioCtx.state === "suspended") {
    audioCtx.resume();
  }
  return audioCtx;
}

function beep() {
  try {
    const ctx = getAudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sawtooth";
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    gain.gain.setValueAtTime(0.3, ctx.currentTime);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.2);
  } catch (e) {
    console.warn("Audio bloqueado por el navegador hasta la interacción del usuario", e);
  }
}

export async function initAI() { 
  if (faceLandmarker) return;
  const statusPill = document.getElementById("aiStatusPill");
  if (statusPill) statusPill.innerText = "Cargando IA...";

  try {
    const resolver = await FilesetResolver.forVisionTasks(
      "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm"
    );
    faceLandmarker = await FaceLandmarker.createFromOptions(resolver, {
      baseOptions: {
        modelAssetPath: "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
      },
      runningMode: "VIDEO",  
      numFaces: 1
    });

    if (statusPill) {
      statusPill.innerText = "● IA Activa";
      statusPill.style.color = "#4ade80";
    }
  } catch (err) {
    console.error("Error en inicialitzar MediaPipe:", err);
    if (statusPill) statusPill.innerText = "Error IA";
  }
}

export async function startCamera() {
  const video = document.getElementById("webcam");
  if (!video) return;

  // para asegurar que la IA esté inicializada antes de empezar la cámara
  if (!faceLandmarker) {
    await initAI();
  }

  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: true
    });
    video.srcObject = cameraStream;
    
    video.onloadedmetadata = () => {
      video.play();
      requestAnimationFrame(runDetection);
    };
  } catch (err) {
    console.warn("Intento de acceso a la cámara fallido:", err);
    try {
      cameraStream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 } }
      });
      video.srcObject = cameraStream;
      
      video.onloadedmetadata = () => {
        video.play();
        requestAnimationFrame(runDetection);
      };
    } catch (fallbackErr) {
      alert("No se ha podido acceder a la cámara. Asegúrate de cerrar otros programas como OBS: " + fallbackErr.message);
    }
  }
}

export function stopCamera() {
  if (cameraStream) {
    cameraStream.getTracks().forEach((track) => track.stop());
    cameraStream = null;
  }
  if (alarmInterval) {
    clearInterval(alarmInterval);
    alarmInterval = null;
  }
  isFatigued = false;
  eyesClosedStart = null;
}

function dist(p1, p2) {
  return Math.hypot(p1.x - p2.x, p1.y - p2.y);
}

function calcEAR(pts, idx) {
  const v = dist(pts[idx[1]], pts[idx[5]]) + dist(pts[idx[2]], pts[idx[4]]);
  const h = 2.0 * dist(pts[idx[0]], pts[idx[3]]);
  return v / h;
}

function calcMAR(pts) {
  const v = dist(pts[MOUTH_POINTS[0]], pts[MOUTH_POINTS[1]]) ;
  const h = dist(pts[MOUTH_POINTS[2]], pts[MOUTH_POINTS[3]]);
  return v / h;
}

function runDetection() {
  const video = document.getElementById("webcam");
  if (!video || !cameraStream || video.paused || video.ended) return;

  if (faceLandmarker && lastVideoTime !== video.currentTime) {
    lastVideoTime = video.currentTime;
    
    try {
      const res = faceLandmarker.detectForVideo(video, performance.now());

      if (res.faceLandmarks && res.faceLandmarks.length > 0) {
        const earL = calcEAR(res.faceLandmarks[0], LEFT_EYE);
        const earR = calcEAR(res.faceLandmarks[0], RIGHT_EYE);
        const avg = (earL + earR) / 2.0;

        const marVal = calcMAR(res.faceLandmarks[0]);
        const earLVal = document.getElementById("earLeft");
        const earRVal = document.getElementById("earRight");
        if (earLVal) earLVal.innerText = earL.toFixed(2);
        if (earRVal) earRVal.innerText = earR.toFixed(2);

        checkFatigue(avg, marVal);
      } else {
        resetAlarm();
      }
    } catch (err) {
      console.warn("Error durante la detección del fotograma:", err);
    }
  }

  requestAnimationFrame(runDetection);
}


function checkFatigue(ear, mar) {
  const now = Date.now();

  // 1. Detección de Microsueño (Ojos cerrados)
  if (ear < EAR_THRESHOLD) {
    if (!eyesClosedStart) eyesClosedStart = now;
    else if (now - eyesClosedStart >= FATIGUE_MS && !isFatigued) {
      triggerAlarm(ear, "Microsueño", "Microsueño detectado en cabina (> 1.5s)");
    }
  } else {
    eyesClosedStart = null;
  }

  // 2. Detección de Bostezo (Boca abierta sostenida)
  if (mar > MAR_THRESHOLD) {
    if (!mouthOpenStart) mouthOpenStart = now;
    else if (now - mouthOpenStart >= YAWN_MS && !isFatigued) {
      triggerAlarm(mar, "Bostezo Profundo", "Bostezo frecuente / fatiga inicial detectada");
    }
  } else {
    mouthOpenStart = null;
  }

  // Si ambos están normales, restablecer alarma
  if (ear >= EAR_THRESHOLD && mar <= MAR_THRESHOLD && isFatigued) {
    resetAlarm();
  }
}

function triggerAlarm(valorNum, tipoAlerta = "Microsueño", mensaje = "") {
  isFatigued = true;
  document.getElementById("alertOverlay")?.classList.remove("hidden");
  
  const statusEl = document.getElementById("driverStatus");
  if (statusEl) {
    statusEl.innerText = `¡${tipoAlerta.toUpperCase()}!`;
    statusEl.className = "status-warn";
  }

  const alertCounterEl = document.getElementById("alertCount");
  if (alertCounterEl) {
    alertCounterEl.innerText = parseInt(alertCounterEl.innerText || 0) + 1;
  }

  const banner = document.getElementById("alertBanner");
  if (banner) {
    banner.className = "alert-banner critico";
    banner.innerHTML = `<span class="icon">⚠</span><div><strong>Nivel de alerta: CRÍTICO</strong><p>${mensaje}</p></div>`;
  }

  if (!alarmInterval) {
    beep();
    alarmInterval = setInterval(beep, 400);
  }

  const turnoId = parseInt(sessionStorage.getItem("turnoId") || "1");
  const unidadId = parseInt(sessionStorage.getItem("unidadId") || "1");
  const conductorId = parseInt(sessionStorage.getItem("conductorId") || "1");
  const codigoUnidad = sessionStorage.getItem("codigoUnidad") || "302-A";
  const nombreConductor = sessionStorage.getItem("nombreConductor") || "Juan Pérez Gómez";

fetch("https://safedrive-ia.onrender.com/api/alertas", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    turnoId: turnoId,
    unidadId: unidadId,
    conductorId: conductorId,
    codigoUnidad: codigoUnidad,
    nombreConductor: nombreConductor,
    tipoAlerta: tipoAlerta,
    valorEar: parseFloat(valorNum.toFixed(2)),    
    duracionSegundos: tipoAlerta === "Microsueño" ? 2.0 : 1.5,
    
  })
})


  .then(res => res.json())
  .then(data => console.log(`Alerta de ${tipoAlerta} registrada en BD:`, data))
  .catch(err => console.warn("Error al enviar alerta:", err));
}

function resetAlarm() {
  eyesClosedStart = null;
  mouthOpenStart = null;
  if (isFatigued) {
    isFatigued = false;
    document.getElementById("alertOverlay")?.classList.add("hidden");
    
    const statusEl = document.getElementById("driverStatus");
    if (statusEl) {
      statusEl.innerText = "Normal";
      statusEl.className = "status-ok";
    }

    const banner = document.getElementById("alertBanner");
    if (banner) {
      banner.className = "alert-banner normal";
      banner.innerHTML = `<span class="icon">✔</span><div><strong>Nivel de alerta: BAJO</strong><p>Sin señales de fatiga detectadas</p></div>`;
    }

    if (alarmInterval) {
      clearInterval(alarmInterval);
      alarmInterval = null;
    }
  }
}