# Today's Overview

Always-on signals platform backend (Java 25, Maven multi-module) with collectors, stores, scheduler runtime, and REST/SSE service.

## Requirements

- Java 25
- Maven 3.9+

## Repository Scripts

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

What it does:
- Runs only `core` module tests (`mvn -pl core test`).

- `./test-collectors.sh`

What it does:
- Runs `collectors` tests and also builds required dependencies (`mvn -pl collectors -am test`).

- `./test-service.sh`

What it does:
- Runs `service` tests and also builds required dependencies (`mvn -pl service -am test`).

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

## Maven Commands (Direct)

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

## Smoke Test Endpoints

Run these after starting backend (default `http://localhost:8080`):

- `curl -sS http://localhost:8080/api/health`
- `curl -sS http://localhost:8080/api/signals`
- `curl -sS "http://localhost:8080/api/events?limit=10"`
- `curl -N --max-time 5 http://localhost:8080/api/stream`
- `curl -sS http://localhost:8080/api/metrics`
- `curl -sS http://localhost:8080/api/collectors/status`
- `curl -sS http://localhost:8080/api/catalog/defaults`
- `curl -sS http://localhost:8080/api/config`

## TLS Diagnostics and Truststore

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

## Environment Data (`/api/env`)

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
- `NOAA_USER_AGENT` (optional; defaults to `todays-overview/0.1 (contact: support@example.com)`)

## Notes on Java 25 Preview

This project is configured for preview APIs (Structured Concurrency) across:

- compile (`maven-compiler-plugin`)
- test (`maven-surefire-plugin`)
- run (`exec-maven-plugin` / Maven JVM config)

So standard Maven/script commands run with `--enable-preview` consistently.

## Coverage Reports (JaCoCo)

Where reports are generated:

- Aggregate HTML report:
  - `backend/target/site/jacoco-aggregate/index.html`
- Per-module HTML reports:
  - `backend/core/target/site/jacoco/index.html`
  - `backend/collectors/target/site/jacoco/index.html`
  - `backend/service/target/site/jacoco/index.html`

Latest coverage snapshot (from `mvn clean verify` on February 17, 2026):

- `core`: instruction 77.3%, line 81.9%
- `collectors`: instruction 90.0%, line 91.0%
- `service`: instruction 77.8%, line 74.7%

## UI Routes

- `#/` Home dashboard (default)
- `#/login`, `#/signup`, `#/forgot`, `#/reset?token=...`
- `#/settings` (authenticated users only; anonymous users are redirected to `#/login`)
- `#/admin` Admin / Diagnostics

## Auth + Preferences (Phase 6)

- Anonymous users can access Home/Admin with defaults.
- Authenticated users get:
  - Settings route in nav
  - server-side preferences via `/api/me/preferences`
  - account menu with sign-out
- Auth/session:
  - JWT in HttpOnly cookie
  - SameSite=Lax
  - Secure cookie enabled in prod mode
  - `APP_ENV` supports `dev|prod` (default `dev`; unknown values warn and default to `dev`)
  - `AUTH_ENABLED` supports `true|false` (default `true`)
    - when `AUTH_ENABLED=false`, auth endpoints are disabled (`404`) and Home/Admin still work anonymously
- Passwords:
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
- Password reset:
  - `POST /api/auth/forgot` returns generic `200` for both existing and non-existing emails
  - reset tokens are hashed, expiring, and one-time-use
- Email:
  - default dev outbox sender (zero setup)
  - optional SMTP sender via environment variables

## Replacing Demo Sources

Default demo config ships with trusted HTTPS endpoints (for example `https://www.mozilla.org/en-US/` and `https://blog.mozilla.org/feed/`) so collectors work in a stock JDK truststore.

To customize:
- edit `backend/config/sites.json` and `backend/config/rss.json` (and `backend/service/config/*` if you run directly from `backend/service`)
- restart backend after config changes

## Phase 6 Demo

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
