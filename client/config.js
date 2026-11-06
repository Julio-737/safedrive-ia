// URL del servidor Spring Boot.
// - Abierto desde Live Server (localhost / 127.0.0.1)  -> usa tu Spring Boot local (puerto 8080)
// - Abierto desde cualquier otro sitio (Render, etc.)   -> usa el servidor desplegado en Render
const esLocal = ["localhost", "127.0.0.1"].includes(window.location.hostname);

export const API_URL = esLocal
  ? "http://localhost:8080"
  : "https://safedrive-ia.onrender.com";
