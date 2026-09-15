import { create } from 'zustand'

const AUTH_BASE_URL = 'http://localhost:8080/api/v1/auth'
const STORAGE_KEY = 'diagram-app:auth'

interface AuthResponse {
  token: string
  userId: string
  email: string
  fullName: string
}

interface StoredSession {
  token: string
  userId: string
  email: string
  fullName: string
}

interface AuthState {
  token: string | null
  userId: string | null
  email: string | null
  fullName: string | null
  status: 'idle' | 'loading' | 'error'
  error: string | null
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

/** Decodifica el claim "exp" (epoch-seconds, RFC 7519) del payload de un JWT sin validar la firma. */
function decodeJwtExpirationMs(token: string): number | null {
  try {
    const payloadSegment = token.split('.')[1]
    if (!payloadSegment) return null
    const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/')
    const json = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='))
    const payload = JSON.parse(json) as { exp?: number }
    return typeof payload.exp === 'number' ? payload.exp * 1000 : null
  } catch {
    return null
  }
}

function isTokenValid(token: string): boolean {
  const expiresAtMs = decodeJwtExpirationMs(token)
  return expiresAtMs !== null && expiresAtMs > Date.now()
}

// sessionStorage, NO localStorage: localStorage se comparte entre TODAS las
// pestañas/ventanas del mismo origen, así que con una sola clave global
// ("diagram-app:auth") dos pestañas logueadas como usuarios distintos (p.ej.
// para probar colaboración) se pisaban la sesión entre sí -- la última que
// escribía "ganaba", y cualquier recarga posterior de la OTRA pestaña la hacía
// reautenticarse silenciosamente como el usuario equivocado (mismo diagrama,
// pero de repente como otro userId), lo que explicaba las "vistas divergentes"
// y los rechazos de ADD_RELATIONSHIP/UPDATE_RELATIONSHIP reportados. sessionStorage
// está aislado POR PESTAÑA incluso en el mismo origen, y sigue sobreviviendo a
// un F5 de esa misma pestaña (que es lo único que pedía el bug de persistencia
// original), así que resuelve ambos requisitos a la vez.
function readStoredSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as StoredSession
    if (!session.token || !isTokenValid(session.token)) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return session
  } catch {
    return null
  }
}

function writeStoredSession(session: StoredSession): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  } catch {
    // sessionStorage no disponible (modo privado, cuota llena, etc.): la sesión
    // sigue funcionando en memoria, solo no persiste entre recargas.
  }
}

function clearStoredSession(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // noop
  }
}

// Restaura la sesión guardada (si el JWT todavía no expiró) de forma síncrona al
// crear el store, para que la primera renderización de App.tsx ya sepa si hay un
// usuario autenticado y no muestre el LoginScreen innecesariamente en cada recarga.
const restored = readStoredSession()

export const useAuthStore = create<AuthState>((set) => ({
  token: restored?.token ?? null,
  userId: restored?.userId ?? null,
  email: restored?.email ?? null,
  fullName: restored?.fullName ?? null,
  status: 'idle',
  error: null,

  login: async (email, password) => {
    set({ status: 'loading', error: null })
    try {
      const response = await fetch(`${AUTH_BASE_URL}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      })

      if (!response.ok) {
        const message = response.status === 401 ? 'Email o contraseña incorrectos' : `Error del servidor (${response.status})`
        set({ status: 'error', error: message })
        return
      }

      const auth: AuthResponse = await response.json()
      writeStoredSession(auth)
      set({
        token: auth.token,
        userId: auth.userId,
        email: auth.email,
        fullName: auth.fullName,
        status: 'idle',
        error: null,
      })
    } catch {
      set({ status: 'error', error: 'No se pudo contactar al backend en http://localhost:8080' })
    }
  },

  logout: () => {
    clearStoredSession()
    set({ token: null, userId: null, email: null, fullName: null, status: 'idle', error: null })
  },
}))
