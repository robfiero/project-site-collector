# signal-sentinel

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

Latest coverage snapshot (from `mvn clean verify` on February 12, 2026):

- `core`: instruction 96.6%, line 97.5%
- `collectors`: instruction 90.0%, line 91.0%
- `service`: instruction 80.3%, line 77.9%
