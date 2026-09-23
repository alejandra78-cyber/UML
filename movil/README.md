# modelcollab_mobile

App Flutter que consume el mismo backend Spring Boot de ModelCollab (`../backend`) — mismo contrato de API que usa el frontend web (`../web`), sin tocar el backend.

## Configurar la URL del backend

La URL base del backend **no está hardcodeada**: se configura en runtime desde la pantalla de login (ícono ⚙ en la barra superior), y queda persistida entre sesiones. Hay dos presets y una entrada libre:

| Escenario | URL | Notas |
|---|---|---|
| Emulador Android | `http://10.0.2.2:8080` | `10.0.2.2` es el alias fijo que usa el emulador para llegar al `localhost` de la PC host. |
| Celular físico por USB, con `adb reverse` | `http://127.0.0.1:8080` | Ver el paso manual más abajo — es la forma recomendada, no depende de la red Wi-Fi. |
| Celular físico por USB, sin `adb reverse` | `http://<IP-LAN-de-la-PC>:8080` | Se escribe a mano en el campo libre del diálogo. La IP cambia si cambiás de red o el router se la reasigna — por eso se prefiere `adb reverse`. |

### Probar en celular físico con `adb reverse` (recomendado)

Con el celular conectado por USB y la depuración USB habilitada, antes de abrir la app corré:

```
adb reverse tcp:8080 tcp:8080
```

Esto hace que **el celular** reenvíe su propio `127.0.0.1:8080` al `127.0.0.1:8080` de la PC — el tráfico va por el cable USB, no por la red Wi-Fi. Ventajas sobre usar la IP LAN directamente:

- No depende de que el celular y la PC estén en la misma red Wi-Fi.
- No se rompe si la IP LAN de la PC cambia (DHCP, cambio de router, etc.).
- Funciona igual si la PC no tiene Wi-Fi o está en una red sin acceso entre dispositivos (común en redes de oficina/facultad).

`adb reverse` se resetea cada vez que se desconecta el cable o se reinicia el `adb server` — hay que volver a correr el comando en ese caso. Después, en la app, elegí el preset **"Celular por USB (adb reverse)"** en el diálogo de configuración.

Se usa `127.0.0.1`, no `localhost`: en algunos dispositivos Android `localhost` resuelve primero a `::1` (loopback IPv6) antes de caer a `127.0.0.1`, y `adb reverse` solo reenvía el loopback IPv4 — la IP literal evita ese intento fallido o más lento.

### Nota sobre HTTP en texto plano (`usesCleartextTraffic`)

El `AndroidManifest.xml` tiene `android:usesCleartextTraffic="true"` porque el backend corre HTTP plano (sin TLS) en desarrollo. Esto **sigue siendo necesario con `adb reverse`**: el bloqueo de tráfico en texto plano de Android (por defecto desde API 28) se aplica según el esquema de la URL (`http://` vs `https://`), no según el destino — no hay una excepción automática para `127.0.0.1`/loopback. Si en algún momento el backend pasa a servir HTTPS, este flag se puede sacar.

## Getting Started (Flutter)

Este proyecto es un punto de partida estándar de Flutter.

- [Learn Flutter](https://docs.flutter.dev/get-started/learn-flutter)
- [Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Flutter learning resources](https://docs.flutter.dev/reference/learning-resources)

Para más ayuda, ver la [documentación oficial de Flutter](https://docs.flutter.dev/).
