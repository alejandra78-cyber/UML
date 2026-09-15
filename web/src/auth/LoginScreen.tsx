import { useState, type FormEvent } from 'react'
import { useAuthStore } from './useAuthStore'
import './LoginScreen.css'

export function LoginScreen() {
  const login = useAuthStore((state) => state.login)
  const status = useAuthStore((state) => state.status)
  const error = useAuthStore((state) => state.error)

  const [email, setEmail] = useState('tester@umlapp.dev')
  const [password, setPassword] = useState('')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    login(email, password)
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        <h1>Diagramador UML/ER</h1>
        <p className="login-subtitle">Inicia sesión para entrar al lienzo colaborativo</p>

        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          autoFocus
        />

        <label htmlFor="password">Contraseña</label>
        <input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />

        {error && <p className="login-error">{error}</p>}

        <button type="submit" disabled={status === 'loading'}>
          {status === 'loading' ? 'Ingresando…' : 'Ingresar'}
        </button>
      </form>
    </div>
  )
}
