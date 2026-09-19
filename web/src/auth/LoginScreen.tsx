import { useState, type FormEvent } from 'react'
import { useAuthStore } from './useAuthStore'
import './LoginScreen.css'

export function LoginScreen() {
  const login = useAuthStore((state) => state.login)
  const register = useAuthStore((state) => state.register)
  const status = useAuthStore((state) => state.status)
  const error = useAuthStore((state) => state.error)

  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (mode === 'register') {
      register(email, password, fullName)
    } else {
      login(email, password)
    }
  }

  function toggleMode() {
    setMode((current) => (current === 'login' ? 'register' : 'login'))
    setFullName('')
  }

  const isLoading = status === 'loading'

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        {/* Identidad visual mínima, sin depender de un asset externo: dos cajas
            de clase conectadas, el mismo vocabulario visual que ya usan
            UmlClassNode (rectángulo con barra de header) y las relaciones del
            lienzo -- reusa los colores reales de la app (celeste/azul), no el
            --accent violeta de index.css (resto del template de Vite sin usar
            en ningún componente real). */}
        <div className="login-card__brand" aria-hidden="true">
          <svg width="40" height="40" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="3" y="4" width="14" height="10" rx="2" fill="#eaf6fe" stroke="#38bdf8" strokeWidth="1.6" />
            <rect x="3" y="4" width="14" height="4" rx="2" fill="#38bdf8" />
            <rect x="23" y="22" width="14" height="10" rx="2" fill="#e8edfc" stroke="#2f5fdb" strokeWidth="1.6" />
            <rect x="23" y="22" width="14" height="4" rx="2" fill="#2f5fdb" />
            <path d="M10 14 L10 20 L30 20 L30 22" stroke="#94a3b8" strokeWidth="1.6" fill="none" />
          </svg>
        </div>
        <h1>Diagramador UML/ER</h1>
        <p className="login-subtitle">
          {mode === 'register'
            ? 'Creá tu cuenta para entrar al lienzo colaborativo'
            : 'Inicia sesión para entrar al lienzo colaborativo'}
        </p>

        {mode === 'register' && (
          <>
            <label htmlFor="fullName">Nombre completo</label>
            <input
              id="fullName"
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              required
              maxLength={150}
              autoFocus
            />
          </>
        )}

        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          autoFocus={mode === 'login'}
        />

        <label htmlFor="password">Contraseña</label>
        <input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={mode === 'register' ? 8 : undefined}
        />

        {error && <p className="login-error">{error}</p>}

        <button type="submit" disabled={isLoading}>
          {mode === 'register'
            ? isLoading
              ? 'Creando cuenta…'
              : 'Crear cuenta'
            : isLoading
              ? 'Ingresando…'
              : 'Ingresar'}
        </button>

        <button type="button" className="login-toggle" onClick={toggleMode} disabled={isLoading}>
          {mode === 'register' ? '¿Ya tenés cuenta? Iniciá sesión' : '¿No tenés cuenta? Registrate'}
        </button>
      </form>
    </div>
  )
}
