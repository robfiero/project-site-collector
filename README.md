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

Latest coverage snapshot (from `mvn clean verify` on February 15, 2026):

- `core`: instruction 89.0%, line 93.9%
- `collectors`: instruction 90.0%, line 91.0%
- `service`: instruction 76.5%, line 73.0%

## UI Routes

- `#/` Home dashboard (default)
  - Weather, News, Sites
  - Air Quality / Local Happenings / Markets framework cards (demo-capable)
  - Places (ZIP list) and Markets watchlist editors
- `#/admin` Admin / Diagnostics
  - Live event feed (filters + search + expandable JSON)
  - Metrics, Collector Status, Defaults, and read-only Config panels

## Phase 5 Notes

- No login/auth yet.
- Places ZIPs and Markets watchlist are client-side only for now:
  - stored in browser `localStorage`
  - not persisted server-side
- AQI/Local/Markets cards may show demo data until Phase 6 data integrations are enabled.

## Replacing Demo Sources

Default demo config ships with trusted HTTPS endpoints (for example `https://www.mozilla.org/en-US/` and `https://blog.mozilla.org/feed/`) so collectors work in a stock JDK truststore.

To customize:
- edit `backend/config/sites.json` and `backend/config/rss.json` (and `backend/service/config/*` if you run directly from `backend/service`)
- restart backend after config changes
