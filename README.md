# Today's Overview

Today's Overview is a personal full-stack engineering project that explores a calm, operator-style dashboard for real-time signals such as news, local events, markets, weather, and environmental data. It is intentionally built as a production-style system rather than a prototype, with an emphasis on resilience, observability, incremental development, modern Java concurrency, and an AI-assisted engineering workflow.

This README is structured to work for multiple audiences:
- A recruiter who wants a quick, clear overview
- An engineer who wants architecture and implementation detail
- A user who wants to run the project locally
- A reviewer who wants to understand the scope and development approach

## Screenshots

![Dashboard](docs/screenshots/dashboard.png)
![Settings](docs/screenshots/settings.png)
![Admin / Diagnostics](docs/screenshots/admin-diagnostics.png)
![About](docs/screenshots/about.png)

## Key capabilities

- Real-time signals dashboard
- Collector-based ingestion architecture
- Server-Sent Events (SSE) streaming updates
- Admin / Diagnostics operational tooling
- Authentication with JWT + HttpOnly cookies
- Secure password hashing (Argon2id / PBKDF2 fallback)
- Password reset workflow
- User preferences storage
- Demo-safe operational diagnostics
- AI-assisted development workflow

## Architecture overview

High-level flow:

```
Collectors
→ Scheduler Runtime
→ Event Store
→ REST + SSE Service
→ React Dashboard
```

The backend is structured as a collector pipeline that ingests signals on a schedule, stores snapshots/events, and streams live updates to the React UI. The UI renders both the real-time feed and an admin diagnostics view for operational visibility.

## Technology stack

**Backend**
- Java 25 (preview features enabled)
- Maven multi-module build
- REST + SSE service
- JSON persistence for users, preferences, and signals

**Frontend**
- React + Vite
- TypeScript
- SSE via `EventSource('/api/stream')`

## AI-Assisted Engineering Workflow

ChatGPT and Codex were used throughout development to help:

- brainstorm approaches
- refine implementation details
- generate targeted code patches
- improve test coverage
- accelerate incremental UX polish passes

These tools acted as development accelerators and thought partners during implementation. Architecture decisions, product direction, engineering tradeoffs, and final code review remained intentional and hands-on throughout development. One of the goals of this project is to demonstrate how modern engineers can use AI tools responsibly: not as a substitute for judgment, but as a force multiplier for iteration, clarity, and delivery. All AI-generated code was reviewed, adjusted, and integrated using the same engineering standards applied to human-written code.

## Project goals

- Build a calm, operator-style dashboard instead of a noisy scrolling feed
- Treat operational visibility as a core product feature
- Stress-test reliability patterns across flaky upstream sources
- Maintain a production-style posture: resilience, observability, and safe defaults
- Use modern Java concurrency primitives in a real system
- Iterate with a disciplined, AI-assisted engineering workflow

## Admin / Diagnostics

The Admin / Diagnostics panel exists to demonstrate operational visibility as part of the product. It includes tooling for collector health, live activity, email diagnostics, and dev outbox visibility. Sensitive values are sanitized before reaching the browser so the page is safe to show publicly.

## Security notes

- Diagnostics are intentionally visible for demo purposes.
- Sensitive fields such as recipient emails and reset-related values are sanitized before reaching the browser.
- Auth uses JWT stored in HttpOnly cookies.
- Password reset uses hashed, expiring, one-time-use tokens.

## Requirements

- Java 25
- Maven 3.9+
- Node.js 18+ (for the UI)

## Running locally

### Backend

From repo root:

```bash
cd backend
./run-service.sh
```

The backend defaults to `http://localhost:8080`.

### UI

```bash
cd ui
npm install
npm run dev
```

The UI defaults to `http://localhost:5173` and expects the backend at `http://localhost:8080`.

## Repository scripts

All scripts are at repo root and wrap common backend commands.

### Build

- `./build.sh`

What it does:
- Compiles and packages all backend modules.
- Skips tests (`mvn -DskipTests package`).

### Run all tests

- `./test.sh`

What it does:
- Runs all backend tests in all modules (`mvn test`).

### Run subset tests

- `./test-core.sh`
  - Runs only `core` module tests (`mvn -pl core test`).

- `./test-collectors.sh`
  - Runs `collectors` tests and also builds required dependencies (`mvn -pl collectors -am test`).

- `./test-service.sh`
  - Runs `service` tests and also builds required dependencies (`mvn -pl service -am test`).

### Coverage baseline (backend + UI)

- `./coverage-baseline.sh`

What it does:
- Runs backend tests (`mvn -q test`) and generates JaCoCo reports.
- Runs UI coverage (`npm run test:coverage`).
- Prints a concise baseline summary:
  - backend/core line + branch
  - backend/collectors line + branch
  - backend/service line + branch
  - overall backend weighted line + branch
  - UI line + branch + function

### Run backend service

- `./backend/run-service.sh`

What it does:
- Builds backend module artifacts needed by `service` and starts it from `backend/`.
- Ensures Java preview is enabled for the Maven JVM and runs `exec:java`.

### Clean outputs

- `./clean.sh`

What it does:
- Removes backend compiled outputs via Maven clean (`mvn clean`).
- Removes `ui/dist` if present.

## Maven commands (direct)

From repo root:

```bash
cd backend
```

### Build commands

- `mvn package`
  - Build all modules and run tests.

- `mvn -DskipTests package`
  - Build all modules, skip tests.

### Test commands

- `mvn test`
  - Run all backend tests.

- `mvn clean test`
  - Clean and run all backend tests.

- `mvn -pl core test`
  - Run only `core` tests.

- `mvn -pl collectors -am test`
  - Run `collectors` tests and build required dependent modules.

- `mvn -pl service -am test`
  - Run `service` tests and build required dependent modules.

- `mvn -pl service test`
  - Run only `service` module tests (uses currently available module artifacts).

### Run backend

- `mvn -pl service -am -DskipTests exec:java`
  - Runs `exec:java` for the `service` module.
  - Assumes module artifacts are already available.

- `mvn -pl service -am -DskipTests package exec:java`
  - Reliable one-command startup from `backend/`: builds module jars, then launches service.

- `./run-service.sh`
  - Reliable convenience launcher from `backend/`.

- `mvn clean verify`
  - Clean, run all tests, and generate JaCoCo module + aggregate coverage reports.

### Clean command

- `mvn clean`
  - Remove compiled outputs (`target/`) for backend modules.

## Smoke test endpoints

Run these after starting backend (default `http://localhost:8080`):

- `curl -sS http://localhost:8080/api/health`
- `curl -sS http://localhost:8080/api/signals`
- `curl -sS "http://localhost:8080/api/events?limit=10"`
- `curl -N --max-time 5 http://localhost:8080/api/stream`
- `curl -sS http://localhost:8080/api/metrics`
- `curl -sS http://localhost:8080/api/collectors/status`
- `curl -sS http://localhost:8080/api/catalog/defaults`
- `curl -sS http://localhost:8080/api/config`

## TLS diagnostics and truststore

If Java HttpClient reports certificate path errors (for example `PKIX path building failed`), use the built-in TLS checker:

- `cd backend && mvn -pl service -am -DskipTests exec:java -Dexec.mainClass=com.signalsentinel.service.tls.TlsCheckMain -Dexec.args="https://example.com"`

What it prints:
- Presented certificate chain (subject + issuer per certificate)
- Whether that chain validates against the default JVM truststore

Optional custom truststore for backend collectors:
- `TRUSTSTORE_PATH` (path to `.jks` or `.p12`/`.pfx`/`.pkcs12`)
- `TRUSTSTORE_PASSWORD` (required when `TRUSTSTORE_PATH` is set)

Example run:

```bash
cd backend
TRUSTSTORE_PATH=/path/to/custom-truststore.p12 \
TRUSTSTORE_PASSWORD=changeit \
./run-service.sh
```

Create a truststore containing a server certificate (example):

```bash
keytool -importcert \
  -alias target-cert \
  -file /path/to/server-cert.pem \
  -keystore /path/to/custom-truststore.p12 \
  -storetype PKCS12
```

The backend keeps secure defaults:
- no trust-all SSL context
- no hostname verification bypass

## Environment data (`/api/env`)

Backend environment status endpoint:

- `GET /api/env?zips=02108,98101`
- If `zips` is omitted, backend uses default ZIPs from catalog defaults.

What it returns per ZIP:

- NOAA weather summary (`temperatureF`, `forecast`, `windSpeed`, timestamps)
- AirNow AQI (`aqi`, `category`) when available
- fallback message `"AQI unavailable"` when AirNow key is not configured

ZIP geocoding:

- ZIP -> lat/lon is resolved on-demand via TIGERweb ZCTA query
- Coordinates are cached server-side in `backend/data/zip-geo.json`
- Resolver prefers `INTPTLAT/INTPTLON` and falls back to `CENTLAT/CENTLON`

Optional env vars:

- `AIRNOW_API_KEY` (optional; without it, weather still works and AQI is marked unavailable)
- `NOAA_USER_AGENT` (optional; defaults to `todays-overview/0.2.0-dev (contact: support@example.com)`)

## Local What's Happening (Ticketmaster)

`Local What’s Happening` is powered by the Ticketmaster Discovery API v2 and the UI includes attribution text: `Powered by Ticketmaster`.

Required env vars:

- `TICKETMASTER_API_KEY`

Optional env vars:

- `TICKETMASTER_BASE_URL` (default: `https://app.ticketmaster.com/discovery/v2`)
- `TICKETMASTER_RADIUS_MILES` (default: `25`)
- `TICKETMASTER_CLASSIFICATIONS` (comma-separated, for example `music,sports,arts`)

Location source:

- Local events are fetched per ZIP code using the same effective ZIP set as Environment (authenticated user preferences merged with catalog defaults; anonymous users use catalog defaults).

Startup behavior:

- If `TICKETMASTER_API_KEY` is missing, `localEventsCollector` is disabled and startup logs a clear warning.

## Top news RSS sources

Configured RSS sources in `backend/config/rss.json` include:

- `nbc` (`https://feeds.nbcnews.com/nbcnews/public/news`)
- `cbs` (`https://www.cbsnews.com/latest/rss/main`)
- `abc`, `cnn`, `fox`, `wsj`, `verge`, and NPR/NYT sources already in config

Reliability note:

- AP (`feeds.apnews.com`) was removed due to recurring feed instability and replaced with NBC News + CBS News.
- Politico (`www.politico.com/rss/politicopicks.xml`) was removed due to recurring 403/access restrictions and replaced with ABC News.

## Notes on Java 25 preview

This project is configured for preview APIs (Structured Concurrency) across:

- compile (`maven-compiler-plugin`)
- test (`maven-surefire-plugin`)
- run (`exec-maven-plugin` / Maven JVM config)

So standard Maven/script commands run with `--enable-preview` consistently.

## Coverage reports (JaCoCo)

Where reports are generated:

- Aggregate HTML report:
  - `backend/target/site/jacoco-aggregate/index.html`
- Per-module HTML reports:
  - `backend/core/target/site/jacoco/index.html`
  - `backend/collectors/target/site/jacoco/index.html`
  - `backend/service/target/site/jacoco/index.html`

Run backend coverage manually:

- `cd backend && mvn test`
- `cd backend && mvn -pl core,collectors,service -am -DskipTests jacoco:report`

Run UI coverage manually:

- `cd ui && npm run test:coverage`

UI HTML report location:

- `ui/coverage/index.html`

Run combined backend + UI baseline:

- `./coverage-baseline.sh`

## UI routes

- `#/` Home dashboard (default)
- `#/auth?mode=login` and `#/auth?mode=signup`
- `#/forgot`, `#/reset?token=...`
- `#/settings` (authenticated users only; anonymous users are redirected to `#/auth?mode=login`)
- `#/admin` Admin / Diagnostics
- `#/about` About

## Auth + preferences

- Anonymous users can access Home/Admin with defaults.
- Authenticated users get:
  - Settings route in nav
  - server-side preferences via `/api/me/preferences`
  - persisted UI preferences in `/api/me/preferences`: `themeMode` and `accent`
  - scoped settings reset via `/api/settings/reset` with `scope=ui|collectors|all`
  - account menu with sign-out and delete-account actions
- Auth/session:
  - JWT in HttpOnly cookie
  - SameSite=Lax
  - Secure cookie enabled in prod mode
  - `APP_ENV` supports `dev|prod` (default `dev`; unknown values warn and default to `dev`)
  - `AUTH_ENABLED` supports `true|false` (default `true`)
    - when `AUTH_ENABLED=false`, auth endpoints are disabled (`404`) and Home/Admin still work anonymously

### Passwords

- Argon2id hashing via Jargon2 RI backend
- On Apple Silicon/macOS arm64, `com.kosprov.jargon2` native RI binaries may be unavailable
- In `APP_ENV=dev`, service startup automatically falls back to a secure PBKDF2 hasher (pure Java) when native Argon2 is unavailable
- In `APP_ENV=prod`, Argon2 native backend remains required and startup fails fast when unavailable
- The native `PasswordHasherTest` may skip by design on unsupported architectures; this is expected
- A portable PBKDF2 password hasher test always runs to validate hash/verify behavior
- Quick check: `cd backend && mvn -pl service -am test -Dtest=PasswordHasherTest`
- Service-only tests: `cd backend && mvn -pl service test`
- Surefire reports (including skip reasons): `backend/service/target/surefire-reports/`
- Startup also validates availability:
  - `APP_ENV=prod` + auth enabled: fail fast if unavailable
  - `APP_ENV=dev` + auth enabled: auto-fallback to PBKDF2 when unavailable
  - `ALLOW_INSECURE_AUTH_HASHER` is legacy; no longer required for normal dev startup

### Password reset

- `POST /api/auth/forgot` returns generic `200` for both existing and non-existing emails
- reset tokens are hashed, expiring, and one-time-use
- dev outbox stores reset emails locally for demo/diagnostics; reset links are sanitized in API responses

### Email / Dev outbox

- default dev outbox sender (zero setup)
- optional SMTP sender via environment variables

## Replacing demo sources

Default demo config ships with trusted HTTPS endpoints (for example `https://www.mozilla.org/en-US/` and `https://blog.mozilla.org/feed/`) so collectors work in a stock JDK truststore.

To customize:
- edit `backend/config/sites.json` and `backend/config/rss.json` (and `backend/service/config/*` if you run directly from `backend/service`)
- restart backend after config changes

## Phase 6 demo

An end-to-end auth/reset/preferences demo script is included:

```bash
./demo-phase6.sh
```

Optional environment overrides:

```bash
BASE_URL=http://localhost:8080 \
EMAIL=demo+custom@example.com \
PASSWORD='Demo!Phase6#Pass123' \
NEW_PASSWORD='Demo!Phase6#Pass456' \
./demo-phase6.sh
```

What it verifies:
- anonymous `/api/health`
- signup + cookie login
- `/api/me` success while authenticated
- preferences PUT/GET round-trip
- logout then `/api/me` returns `401`
- forgot-password + dev outbox reset-link extraction
- password reset with token
- login using new password and `/api/me` success

Notes:
- backend must be running first (`cd backend && ./run-service.sh`)
- script uses `/api/dev/outbox` (dev mode required)
- uses `jq` when available; otherwise falls back to `python3` JSON parsing

## Future improvements

- Containerization / Docker
- AWS deployment
- Richer dashboard refinements
- Continued observability and collector improvements

## Project status

This is a personal engineering project and active learning system. It is not a production deployment.
