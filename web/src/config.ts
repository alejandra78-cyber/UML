// Fuente única de verdad para la URL del backend. Antes estaba hardcodeada por
// triplicado (API_BASE en diagramBootstrap.ts, BACKEND_HTTP_URL en
// stompClient.ts, AUTH_BASE_URL en useAuthStore.ts), cada una con su propio
// literal 'http://localhost:8080' -- cambiar de entorno (staging, otro puerto
// local, etc.) requería tocar los 3 archivos a mano y era fácil dejar alguno
// desactualizado. `VITE_API_URL` se lee de `.env` (ver .env.example) y Vite la
// expone vía `import.meta.env` en build time; sin la variable definida, cae al
// mismo default de siempre para no romper un checkout sin `.env`.
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'
