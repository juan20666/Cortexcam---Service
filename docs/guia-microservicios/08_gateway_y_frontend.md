# 08 — `gateway-service` (BFF) y `frontend` (React + TypeScript)

Este es el último eslabón: el navegador **nunca** habla con los microservicios ni guarda tokens. Habla con el gateway; el gateway maneja el login (OIDC con Keycloak), guarda la sesión en una cookie `HttpOnly` y reenvía cada petición al servicio correcto con el token.

```
Navegador ──cookie HttpOnly──► gateway-service :8080 ──Bearer JWT──► camera :8091 · alert :8092 · evidence :8093 · notification :8094
        └──── video (WebRTC/HLS) ────────────────────────────────► MediaMTX :8889 / :8888
```
Reemplaza el login de `script.js` (usuario/contraseña en `localStorage`), el `pywebview.api` y el polling de `frame.jpg`.

---

# PARTE A — `gateway-service` (Java · puerto 8080 · sin BD · sin dominio)

Es **infraestructura pura** (excepción documentada a la plantilla hexagonal, ADR). Spring Cloud Gateway **reactivo** (WebFlux).

## A.1 Árbol
```
services/gateway-service/
├── pom.xml  🆕
└── src/main/
    ├── java/com/cortexcam/gateway/
    │   ├── GatewayApplication.java       🆕
    │   ├── security/SecurityConfig.java · CsrfCookieWebFilter.java   🆕
    │   └── web/SessionController.java    🆕
    └── resources/application.yml         🆕
```
**Dependencias** (parent = `cortexcam-parent`, que ya importa el BOM de Spring Cloud): `spring-cloud-starter-gateway-server-webflux`, `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-actuator`. Spring Cloud **2025.1.x** es la serie compatible con Boot 4.1. El *starter* del gateway se renombró en las series recientes: si tu IDE no lo resuelve, genera el esqueleto en start.spring.io (Boot 4.1 + "Gateway") y copia el `artifactId` que te dé. Los prefijos de configuración también cambiaron (`spring.cloud.gateway.server.webflux.*`).

## A.2 `application.yml`
```yaml
server: { port: 8080 }
spring:
  application.name: gateway-service
  config.import: optional:file:.env[.properties]
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: cortexcam-web
            client-secret: ${OIDC_CLIENT_SECRET}
            scope: openid,profile,email
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          keycloak:
            issuer-uri: ${OIDC_ISSUER_URI:http://localhost:8081/realms/cortexcam}
            user-name-attribute: preferred_username
  cloud:
    gateway:
      server:
        webflux:
          default-filters:
            - TokenRelay=                                   # reenvía el access token del usuario a cada servicio
          routes:
            - { id: camera,        uri: "http://localhost:8091", predicates: ["Path=/api/v1/cameras/**"] }
            - { id: alerts,        uri: "http://localhost:8092", predicates: ["Path=/api/v1/alerts/**,/api/v1/alert-rules/**,/api/v1/watchlist/**"] }
            - { id: evidence,      uri: "http://localhost:8093", predicates: ["Path=/api/v1/evidence/**"] }
            - { id: notifications, uri: "http://localhost:8094", predicates: ["Path=/api/v1/notifications/**"] }
management.endpoints.web.exposure.include: health,info
```
`.env`: `OIDC_CLIENT_SECRET=dev-secret-change-me`. Las rutas salen de `connections/gateway-routes.yaml`: mantén ambos sincronizados. Un gateway **no** debe tener lógica de negocio: solo enrutar, autenticar, limitar tasa y CORS (aquí innecesario porque el navegador ve un solo origen).

## A.3 Seguridad (sesión por cookie + CSRF)
```java
@Configuration @EnableWebFluxSecurity
class SecurityConfig {
    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http) {
        // /api/** sin sesión → 401 (el SPA decide); el resto → redirige al login de Keycloak
        var apiEntry = new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);
        var entry = new DelegatingServerAuthenticationEntryPoint(
            new DelegatingServerAuthenticationEntryPoint.DelegateEntry(ServerWebExchangeMatchers.pathMatchers("/api/**"), apiEntry));
        entry.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak"));

        return http
            .authorizeExchange(a -> a.pathMatchers("/actuator/health/**").permitAll().anyExchange().authenticated())
            .oauth2Login(Customizer.withDefaults())
            .exceptionHandling(e -> e.authenticationEntryPoint(entry))
            .csrf(c -> c.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())      // el SPA lee XSRF-TOKEN y lo manda en X-XSRF-TOKEN
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
            .build();
    }
}

@Component
class CsrfCookieWebFilter implements WebFilter {                    // fuerza que la cookie XSRF-TOKEN se emita en cada respuesta
    @Override public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        Mono<CsrfToken> token = ex.getAttribute(CsrfToken.class.getName());
        return token != null ? token.then(chain.filter(ex)) : chain.filter(ex);
    }
}
```
La cookie de sesión de Spring ya sale `HttpOnly`; añade `server.reactive.session.cookie.secure=true` y `same-site=lax` en producción (HTTPS). El *logout* (`POST /logout`) cierra la sesión local; para cerrar también la de Keycloak agrega un `OidcClientInitiatedServerLogoutSuccessHandler`.

## A.4 `GET /api/v1/session` (para que el SPA sepa quién es y qué roles tiene)
```java
@RestController
class SessionController {
    private final JsonMapper json;
    @GetMapping("/api/v1/session")
    Map<String, Object> session(@AuthenticationPrincipal OidcUser user,
                                @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client) {
        var claims = payload(client.getAccessToken().getTokenValue());               // el token ya viene validado por Keycloak: aquí solo se leen sus claims
        Map<String, Object> realm = (Map<String, Object>) claims.getOrDefault("realm_access", Map.of());
        return Map.of("username", user.getPreferredUsername(), "roles", realm.getOrDefault("roles", List.of()),
                      "tenantId", String.valueOf(claims.get("tenant_id")));
    }
    private Map<String, Object> payload(String jwt) {
        var part = jwt.split("\\.")[1];
        return json.readValue(Base64.getUrlDecoder().decode(part), new TypeReference<Map<String, Object>>() {});
    }
}
```
Los roles del SPA solo sirven para **mostrar/ocultar** botones. La autorización real la hace cada servicio (`@PreAuthorize`).

## A.5 Listo cuando
- [ ] Sin sesión, `GET /api/v1/cameras` → 401; abrir `http://localhost:8080/` → redirige a Keycloak.
- [ ] Tras iniciar sesión (`admin`/`admin`), `GET /api/v1/cameras` llega a `camera-service` con el token y responde 200.
- [ ] `POST` sin cabecera `X-XSRF-TOKEN` → 403; con ella → 201.
- [ ] El SSE (`/api/v1/notifications/stream`) atraviesa el gateway sin cortarse a los 30 s.
- [ ] Ningún servicio detrás es alcanzable desde fuera sin pasar por aquí (en producción, solo el gateway publica puerto).

---

# PARTE B — `frontend` (React 18 + TypeScript + Vite)

Misma arquitectura hexagonal, **por funcionalidad**: dentro de cada *feature*, `domain → application → infrastructure → ui`, con las reglas F-01…F-08 de la guía de arquitectura (§3.3).

## B.1 Dependencias
`react`, `react-dom`, `react-router-dom`, `@tanstack/react-query`, `zod`, `hls.js` · dev: `vite`, `@vitejs/plugin-react`, `typescript`, `vitest`, `@testing-library/react`, `msw`, `eslint`, `eslint-plugin-boundaries`.

## B.2 Árbol (✅ existe vacío · 🆕 crear)
```
frontend/
├── index.html 🆕 · package.json ✅ · tsconfig.json ✅ · vite.config.ts ✅ · eslint.config.js 🆕 · .env.example 🆕
├── public/sounds/alert.wav 🆕           ← copia tu alerta1.wav (mi_app_pywebview/web/sonidos_alertas/alerta1.wav)
└── src/
    ├── main.tsx 🆕
    ├── app/
    │   ├── App.tsx · router.tsx 🆕 · composition-root.ts ✅(rellenar) · config/env.ts 🆕 · providers/(QueryProvider · ToastProvider) 🆕
    ├── shared/
    │   ├── kernel/             AppError.ts · Result.ts 🆕
    │   ├── infrastructure/     http/httpClient.ts · sse/EventSourceClient.ts 🆕
    │   └── ui/                 Button · Modal · Toast · EmptyState · Layout(Sidebar) 🆕     ← reutiliza el diseño de tu style.css
    ├── features/
    │   ├── auth/               domain/Session.ts · application/(ports/SessionPort.ts · usecases/GetSession.ts) · infrastructure/BffSessionAdapter.ts · ui/(useSession.ts · ProtectedRoute.tsx)
    │   ├── cameras/            domain/(Camera.ts · rules.ts) · application/(ports/CameraRepository.ts · usecases/{ListCameras,RegisterCamera,UpdateCamera,SetCameraEnabled,GetPlayback}.ts)
    │   │                       infrastructure/(HttpCameraRepository.ts · dto/CameraDto.ts · mappers/cameraMapper.ts) · ui/(hooks/useCameras.ts · components/ · pages/CamerasPage.tsx) · index.ts ✅
    │   ├── live-view/          application/ports/StreamPlayerPort.ts · infrastructure/(WhepPlayer.ts · HlsPlayer.ts) · ui/(LivePlayer.tsx · pages/LiveViewPage.tsx)
    │   ├── alerts/             domain/Alert.ts · application/(ports/{AlertRepository,AlertStreamPort}.ts · usecases/{ListAlerts,AcknowledgeAlert,ResolveAlert,MarkFalsePositive}.ts)
    │   │                       infrastructure/(HttpAlertRepository.ts · SseAlertStream.ts · dto · mappers) · ui/(useAlertStream.ts · AlertToast.tsx · pages/AlertsPage.tsx)
    │   ├── evidence-gallery/   application/(ports/EvidencePort.ts · usecases/{ListEvidence,GetEvidenceUrl}.ts) · infrastructure/HttpEvidenceAdapter.ts · ui/EvidenceGalleryPage.tsx   ← tu "Detecciones"
    │   ├── notification-settings/ (preferencias: canales, severidad mínima, horas silenciosas)
    │   └── feedback/           (el botón "falso positivo" vive en alerts; aquí un resumen para reentrenamiento, opcional)
    └── test/                   fakes en memoria de cada puerto · handlers MSW · utilidades de render
```
Cada `domain/` **no importa** React, `fetch` ni nada del navegador.

## B.3 Configuración
`vite.config.ts` — el navegador ve un solo origen (`localhost:5173`); Vite reenvía al gateway, así las cookies funcionan y el `redirect-uri` del login coincide con el que permite el realm:
```ts
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080", "/oauth2": "http://localhost:8080",
      "/login": "http://localhost:8080", "/logout": "http://localhost:8080",
    },
  },
});
```
`src/app/config/env.ts` (solo valores **públicos** `VITE_*`):
```ts
const schema = z.object({ VITE_API_BASE_URL: z.string().default("") });   // vacío = mismo origen
export const env = schema.parse(import.meta.env);
```

## B.4 Infraestructura compartida
```ts
// shared/kernel/AppError.ts
export class AppError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly traceId?: string) { super(message); }
}

// shared/infrastructure/http/httpClient.ts
const xsrf = () => document.cookie.split("; ").find(c => c.startsWith("XSRF-TOKEN="))?.split("=")[1];

export class HttpClient {
  constructor(private readonly base = "") {}
  async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") { const t = xsrf(); if (t) headers["X-XSRF-TOKEN"] = decodeURIComponent(t); }
    const res = await fetch(this.base + path, { method, headers, credentials: "include", body: body === undefined ? undefined : JSON.stringify(body) });
    if (res.status === 401) { window.location.href = "/oauth2/authorization/keycloak"; throw new AppError(401, "UNAUTHENTICATED", "Sesión requerida"); }
    if (!res.ok) {
      const p = await res.json().catch(() => ({}));                          // ProblemDetail del backend: { code, detail, traceId }
      throw new AppError(res.status, p.code ?? "HTTP_ERROR", p.detail ?? res.statusText, p.traceId);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  }
  get<T>(p: string) { return this.request<T>("GET", p); }
  post<T>(p: string, b?: unknown) { return this.request<T>("POST", p, b); }
  put<T>(p: string, b?: unknown) { return this.request<T>("PUT", p, b); }
  patch<T>(p: string, b?: unknown) { return this.request<T>("PATCH", p, b); }
  delete<T>(p: string) { return this.request<T>("DELETE", p); }
}
```

## B.5 Feature `cameras` (molde para todas las demás)
```ts
// domain/Camera.ts — sin React, sin fetch
export type CameraId = string & { readonly __brand: "CameraId" };
export type CameraStatus = "PENDING" | "ACTIVE" | "OFFLINE" | "DISABLED";
export type DetectorKind = "PERSON" | "WEAPON" | "VEHICLE" | "PLATE";
export interface DetectorSettings { kind: DetectorKind; enabled: boolean; minConfidence: number; }
export interface Camera { id: CameraId; name: string; location?: string; status: CameraStatus; samplingFps: number; detectors: DetectorSettings[]; }
export const isViewable = (c: Camera) => c.status === "ACTIVE";            // regla de presentación pura

// application/ports/CameraRepository.ts — lo define la feature
export interface CameraRepository {
  list(): Promise<Camera[]>;
  register(i: { name: string; location?: string; host: string; port: number; path: string; username?: string; password?: string; samplingFps: number }): Promise<Camera>;
  update(id: CameraId, patch: Partial<Pick<Camera, "name" | "location" | "detectors" | "samplingFps">>): Promise<Camera>;
  setEnabled(id: CameraId, enabled: boolean): Promise<Camera>;
  playback(id: CameraId): Promise<{ webrtcWhepUrl: string; hlsUrl: string }>;
}

// application/usecases/ListCameras.ts
export class ListCameras { constructor(private readonly repo: CameraRepository) {} execute() { return this.repo.list(); } }

// infrastructure/HttpCameraRepository.ts — adaptador (DTO → dominio vía mapper)
export class HttpCameraRepository implements CameraRepository {
  constructor(private readonly http: HttpClient) {}
  async list() { return (await this.http.get<CameraDto[]>("/api/v1/cameras")).map(toCamera); }
  async playback(id: CameraId) { return this.http.get<{ webrtcWhepUrl: string; hlsUrl: string }>(`/api/v1/cameras/${id}/playback`); }
  // register/update/setEnabled: POST/PATCH/POST .../enable|disable
}
```
```ts
// app/composition-root.ts — ÚNICO lugar con "new Adapter()"
const http = new HttpClient(env.VITE_API_BASE_URL);
const cameraRepo = new HttpCameraRepository(http);
const alertRepo  = new HttpAlertRepository(http);
const alertStream = new SseAlertStream("/api/v1/notifications/stream");
const evidence = new HttpEvidenceAdapter(http);

export const services = {
  session:  { get: new GetSession(new BffSessionAdapter(http)) },
  cameras:  { list: new ListCameras(cameraRepo), register: new RegisterCamera(cameraRepo), update: new UpdateCamera(cameraRepo),
              setEnabled: new SetCameraEnabled(cameraRepo), playback: new GetPlayback(cameraRepo) },
  alerts:   { list: new ListAlerts(alertRepo), acknowledge: new AcknowledgeAlert(alertRepo), resolve: new ResolveAlert(alertRepo),
              falsePositive: new MarkFalsePositive(alertRepo), stream: alertStream },
  evidence: { list: new ListEvidence(evidence), url: new GetEvidenceUrl(evidence) },
};

// features/cameras/ui/hooks/useCameras.ts
export const useCameras = () => useQuery({ queryKey: ["cameras"], queryFn: () => services.cameras.list.execute() });
```
**Usa el mismo molde** para `alerts`, `evidence-gallery` y `notification-settings`. Los componentes **solo** llaman hooks; los hooks, casos de uso; los casos de uso, puertos.

## B.6 Video en vivo (reemplaza `frame.jpg` + `setInterval`)
```ts
// features/live-view/application/ports/StreamPlayerPort.ts
export interface StreamPlayerPort { attach(video: HTMLVideoElement, urls: { webrtcWhepUrl: string; hlsUrl: string }): Promise<() => void>; }
```
```ts
// infrastructure/WhepPlayer.ts — WebRTC (latencia ~0,5 s) hablando con MediaMTX
export async function playWhep(video: HTMLVideoElement, whepUrl: string): Promise<() => void> {
  const pc = new RTCPeerConnection();                                       // en LAN/localhost no necesitas STUN
  pc.addTransceiver("video", { direction: "recvonly" });
  pc.ontrack = e => { video.srcObject = e.streams[0]; };
  await pc.setLocalDescription(await pc.createOffer());
  const res = await fetch(whepUrl, { method: "POST", headers: { "Content-Type": "application/sdp" }, body: pc.localDescription!.sdp });
  if (!res.ok) { pc.close(); throw new Error(`WHEP ${res.status}`); }
  await pc.setRemoteDescription({ type: "answer", sdp: await res.text() });
  return () => pc.close();
}

// infrastructure/HlsPlayer.ts — respaldo (latencia 2-6 s, funciona en casi todo)
export async function playHls(video: HTMLVideoElement, url: string): Promise<() => void> {
  if (video.canPlayType("application/vnd.apple.mpegurl")) { video.src = url; return () => { video.removeAttribute("src"); }; }
  const hls = new Hls({ lowLatencyMode: true }); hls.loadSource(url); hls.attachMedia(video);
  return () => hls.destroy();
}
```
`LivePlayer` intenta WebRTC y, si falla, HLS. En desarrollo el navegador habla **directo** con MediaMTX (`:8889`/`:8888`, el `playback` del backend ya trae esas URLs); en producción pásalo por HTTPS/proxy. El botón "ampliar" de tu `script.js` pasa a un `<LivePlayer fullscreen />` con la API `requestFullscreen()`.

## B.7 Alertas en tiempo real y sonido
```ts
// features/alerts/infrastructure/SseAlertStream.ts
export class SseAlertStream implements AlertStreamPort {
  constructor(private readonly url: string) {}
  subscribe(onAlert: (a: AlertNotification) => void): () => void {
    const es = new EventSource(this.url, { withCredentials: true });          // la cookie del gateway autentica
    es.addEventListener("alert", e => onAlert(toNotification(JSON.parse((e as MessageEvent).data))));
    return () => es.close();                                                  // EventSource reconecta solo si se corta
  }
}

// features/alerts/ui/useAlertStream.ts
export function useAlertStream() {
  const toast = useToast(); const qc = useQueryClient();
  useEffect(() => services.alerts.stream.subscribe(a => {
    toast.show({ title: `${a.severity}: ${a.type}`, kind: a.severity === "CRITICAL" ? "error" : "info" });
    new Audio("/sounds/alert.wav").play().catch(() => { /* autoplay bloqueado: se activa tras el primer clic */ });
    qc.invalidateQueries({ queryKey: ["alerts"] });
  }), []);
}
```
Los navegadores bloquean audio hasta que el usuario interactúe: el clic de login/entrada lo desbloquea; si no, muestra un botón "Activar sonido".

## B.8 Galería de evidencias (tu "Detecciones")
Lista alertas (`GET /api/v1/alerts`), y por cada una pide `GET /api/v1/evidence?alertId=` y, **solo al mostrarla** (IntersectionObserver), `GET /api/v1/evidence/{id}/url` para obtener la URL firmada (60 s) y pintarla en un `<img>`. Nunca guardes esas URLs: caducan. Al hacer clic para ampliar, vuelve a pedir una nueva.

## B.9 Qué reutilizar de tu UI actual
Sidebar y paleta de `style.css` → `shared/ui/Layout`; el tutorial interactivo y el modal de bienvenida → un componente `OnboardingTour` (estado en `localStorage` **solo** para "tutorial visto"; nunca credenciales); el modal "nombre de la app de cámara" **desaparece** (ya no se captura una ventana: se registran cámaras por RTSP).

## B.10 Fronteras verificadas (equivalente a ArchUnit)
```js
// eslint.config.js
export default [{
  plugins: { boundaries },
  settings: { "boundaries/elements": [
    { type: "domain",         pattern: "src/features/*/domain/**" },
    { type: "application",    pattern: "src/features/*/application/**" },
    { type: "infrastructure", pattern: "src/features/*/infrastructure/**" },
    { type: "ui",             pattern: "src/features/*/ui/**" },
    { type: "app",            pattern: "src/app/**" },
    { type: "shared",         pattern: "src/shared/**" } ] },
  rules: { "boundaries/element-types": ["error", { default: "disallow", rules: [
    { from: "domain",         allow: ["domain", "shared"] },
    { from: "application",    allow: ["domain", "application", "shared"] },
    { from: "infrastructure", allow: ["domain", "application", "infrastructure", "shared"] },
    { from: "ui",             allow: ["domain", "application", "ui", "shared", "app"] },     // la UI llega a los casos de uso vía composition-root
    { from: "app",            allow: ["domain", "application", "infrastructure", "ui", "shared"] },
    { from: "shared",         allow: ["shared"] } ] }] },
}];
```
Si un `ui` importa `infrastructure`, el lint falla (F-02). Añade `npm run lint` al CI.

## B.11 Pruebas
| Nivel | Qué |
|---|---|
| Dominio / casos de uso | Vitest con **fakes en memoria** de `CameraRepository`, `AlertRepository`, `AlertStreamPort` (sin red) |
| Adaptadores | MSW simula el gateway: `HttpCameraRepository` mapea DTO→dominio; `HttpClient` convierte un `ProblemDetail` 409 en `AppError` con `code`; un 401 redirige |
| UI | Testing Library con el fake de servicios inyectado: `CamerasPage` lista; al llegar una alerta por el stream falso aparece el toast |
| Arquitectura | `npm run lint` (boundaries) |

## B.12 Listo cuando
- [ ] `npm run dev` → `http://localhost:5173` → Keycloak → vuelves logueado (`/api/v1/session` con tu usuario y roles).
- [ ] Registras una cámara y la ves **en vivo** por WebRTC (y por HLS si cortas WebRTC).
- [ ] Una persona en cuadro → toast + sonido en pocos segundos → *acknowledge* / "falso positivo".
- [ ] La galería muestra la imagen con cajas vía URL firmada.
- [ ] Ningún componente importa `infrastructure`; ningún token ni secreto en el bundle (`VITE_*` solo públicos).
