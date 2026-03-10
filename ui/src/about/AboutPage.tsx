const REPO_BASE = 'https://github.com/robfiero/project-site-collector/blob/main/';

function SourceLink(props: { path: string; label: string }) {
  return (
    <a href={`${REPO_BASE}${props.path}`} target="_blank" rel="noreferrer">
      {props.label}
    </a>
  );
}

export default function AboutPage() {
  return (
    <main className="settings-page">
      <section className="card">
        <h2>About Today&apos;s Overview</h2>
        <div className="about-author">
          <div className="about-author-panel">
            <p className="about-author-title">Created by <strong>Robert Fiero</strong></p>
            <p className="meta about-author-subtitle">
              Built with an AI-assisted design and implementation workflow that combines modern engineering practices with iterative experimentation.
            </p>
            <div className="about-author-links">
              <a className="about-author-link" href="https://www.linkedin.com/in/robert-fiero/" target="_blank" rel="noreferrer">
                View LinkedIn profile →
              </a>
              <a className="about-author-link" href="https://github.com/robfiero/project-site-collector" target="_blank" rel="noreferrer">
                View GitHub repository →
              </a>
            </div>
          </div>
        </div>
        <p>
          Today&apos;s Overview is a personal engineering project focused on building a calm, operator-style dashboard for real-time signals. It brings together news, local events, markets, and environmental data into a compact view designed to be scanned quickly.
        </p>
        <p>
          The goal is to build something closer to a small control room than a traditional feed: focused, data-rich, and resilient when upstream systems behave imperfectly.
        </p>
        <p>
          This project is intentionally designed as a production-style system rather than a prototype, emphasizing observability, resilience, and incremental development.
        </p>
      </section>

      <section className="settings-grid">
        <section className="card">
          <h3>Why I built this</h3>
          <p className="meta section-description">A production-style system built to prove out real-world outcomes.</p>
          <ul>
            <li>Shipping a full-stack system with real data ingestion and delivery.</li>
            <li>Stress-testing reliability patterns such as polling, backoff, and graceful failure.</li>
            <li>Building a dashboard that stays readable while underlying data changes continuously.</li>
            <li>Exploring modern Java concurrency and system observability in a real project.</li>
          </ul>
        </section>

        <section className="card">
          <h3>Key goals</h3>
          <p className="meta section-description">Principles that shaped the system&apos;s structure and evolution.</p>
          <ul>
            <li>Build a complete end-to-end system from data ingestion through UI presentation.</li>
            <li>Treat operational visibility as a core product feature.</li>
            <li>Design collectors that tolerate unreliable upstream APIs.</li>
            <li>Keep the UI readable even as data streams update continuously.</li>
            <li>Build a system that can evolve through small, safe iterations.</li>
          </ul>
        </section>
      </section>

      <section className="settings-grid">
        <section className="card">
          <h3>Tech stack</h3>
          <p className="meta section-description">A focused stack chosen for reliability and clarity.</p>
          <h4>Backend</h4>
          <ul>
            <li>Java 25 services using structured concurrency and virtual threads.</li>
            <li>JSON-based persistence for user state and system signals.</li>
            <li>Event-driven collectors for news, markets, weather, and local events.</li>
            <li>Server-Sent Events (SSE) streaming for live diagnostics and updates.</li>
          </ul>
          <h4>Frontend</h4>
          <ul>
            <li>React + Vite UI.</li>
            <li>Accessible light/dark themes with customizable accents.</li>
            <li>Streaming updates from backend event feeds.</li>
            <li>Diagnostics and operational visibility built directly into the UI.</li>
          </ul>
        </section>

        <section className="card">
          <h3>Architecture and engineering themes</h3>
          <p className="meta section-description">Patterns that keep the system resilient and observable.</p>
          <ul>
            <li>Collector-based ingestion model.</li>
            <li>Clear boundaries between ingestion, storage, and presentation.</li>
            <li>Operational visibility built into the product.</li>
            <li>Resilience to imperfect upstream systems.</li>
          </ul>
        </section>
      </section>

      <section className="settings-grid">
        <section className="card">
          <h3>What I learned</h3>
          <p className="meta section-description">The polish comes from small, repeatable decisions.</p>
          <ul>
            <li>Balancing immediate UI feedback with eventual consistency in background refresh.</li>
            <li>Designing authentication flows that remain clear even when backend systems respond slowly.</li>
            <li>Treating observability and diagnostics as core product features rather than afterthoughts.</li>
            <li>Working with modern Java concurrency primitives in a real system.</li>
            <li>Building systems that evolve through small, iterative improvements.</li>
          </ul>
        </section>

        <section className="card">
          <h3>Why this project matters</h3>
          <p className="meta section-description">A credible demo of engineering rigor and product thinking.</p>
          <ul>
            <li>Demonstrates end-to-end ownership across data collection, storage, UI, and operational readiness.</li>
            <li>Shows attention to failure modes, diagnostics, and safe defaults.</li>
            <li>Built to be demo-friendly without sacrificing realistic architecture.</li>
            <li>Active learning project evolving through incremental releases.</li>
          </ul>
        </section>
      </section>

      <section className="card">
        <p className="meta section-description">
          The sections below highlight specific Java features, libraries, and tooling used throughout the codebase.
          These references are included for engineers who want to explore implementation details.
        </p>
        <details className="admin-collapsible">
          <summary><span className="caret" aria-hidden="true">▶</span> Java features used</summary>

            <section>
              <h4>Structured concurrency and virtual threads</h4>
              <p>
                Uses StructuredTaskScope for coordinated fan-out / fan-in collector runs and Executors.newVirtualThreadPerTaskExecutor()
                for lightweight concurrency in the scheduler.
              </p>
              <p className="meta"><SourceLink path="backend/service/src/main/java/com/signalsentinel/service/runtime/SchedulerService.java" label="View SchedulerService.java" /></p>
            </section>

            <section>
              <h4>CompletableFuture-based async pipelines</h4>
              <p>
                Collectors fan out per source or location using CompletableFuture, including allOf, thenApply, handle, timeouts, and exception recovery.
              </p>
              <p className="meta">
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/rss/RssNewsCollector.java" label="View RssNewsCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/weather/WeatherCollector.java" label="View WeatherCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/events/TicketmasterEventsCollector.java" label="View TicketmasterEventsCollector.java" />
              </p>
            </section>

            <section>
              <h4>Streams, lambdas, and collectors</h4>
              <p>
                Extensive use of stream(), map, filter, collect, toList, groupingBy, counting, lambdas, and method references for data shaping and statistics.
              </p>
              <p className="meta">
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/weather/WeatherCollector.java" label="View WeatherCollector.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/runtime/SchedulerService.java" label="View SchedulerService.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/rss/RssNewsCollector.java" label="View RssNewsCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/site/SiteCollector.java" label="View SiteCollector.java" />
              </p>
            </section>

            <section>
              <h4>Records</h4>
              <p>
                Uses record types for immutable data carriers such as scheduled collectors, poll outcomes, and stored event wrappers.
              </p>
              <p className="meta">
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/runtime/SchedulerService.java" label="View SchedulerService.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/weather/WeatherCollector.java" label="View WeatherCollector.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/store/EventCodec.java" label="View EventCodec.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/events/TicketmasterEventsCollector.java" label="View TicketmasterEventsCollector.java" />
              </p>
            </section>

            <section>
              <h4>Java HTTP client</h4>
              <p>
                Uses the java.net.http client for collector I/O and integration tests, including HttpClient, HttpRequest, and HttpResponse.
              </p>
              <p className="meta">
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/rss/RssNewsCollector.java" label="View RssNewsCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/site/SiteCollector.java" label="View SiteCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/events/TicketmasterEventsCollector.java" label="View TicketmasterEventsCollector.java" />
              </p>
            </section>

            <section>
              <h4>java.time API</h4>
              <p>
                Extensive use of Instant, Duration, OffsetDateTime, ZonedDateTime, LocalDate, and Clock for time handling across collectors and storage.
              </p>
              <p className="meta">
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/rss/RssNewsCollector.java" label="View RssNewsCollector.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/events/TicketmasterEventsCollector.java" label="View TicketmasterEventsCollector.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/runtime/SchedulerService.java" label="View SchedulerService.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/store/EventCodec.java" label="View EventCodec.java" />
              </p>
            </section>

            <section>
              <h4>Optional and null-safety patterns</h4>
              <p>
                Uses Optional to represent absent values and guide lookups, parsing, and configuration-driven flows.
              </p>
              <p className="meta">
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/auth/AuthService.java" label="View AuthService.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/store/JsonFileSignalStore.java" label="View JsonFileSignalStore.java" /> ·{' '}
                <SourceLink path="backend/collectors/src/main/java/com/signalsentinel/collectors/rss/RssNewsCollector.java" label="View RssNewsCollector.java" />
              </p>
            </section>

            <section>
              <h4>Concurrency primitives and collections</h4>
              <p>
                Uses ConcurrentHashMap, CopyOnWriteArrayList, and ReentrantLock for safe shared state, broadcast coordination, and file-backed stores.
              </p>
              <p className="meta">
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/store/JsonFileSignalStore.java" label="View JsonFileSignalStore.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/store/JsonlEventStore.java" label="View JsonlEventStore.java" /> ·{' '}
                <SourceLink path="backend/service/src/main/java/com/signalsentinel/service/api/SseBroadcaster.java" label="View SseBroadcaster.java" />
              </p>
            </section>
        </details>
        <details className="admin-collapsible">
          <summary><span className="caret" aria-hidden="true">▶</span> Libraries and tooling</summary>
          <p className="meta section-description">
            Dependency versions are included for reproducibility and to make it easier for engineers to explore the project.
          </p>
          <h4>Backend runtime</h4>
          <ul>
            <li>Jackson Databind — 2.18.3 — JSON serialization and configuration handling</li>
            <li>Jackson JSR-310 — 2.18.3 — Java time support for JSON</li>
            <li>Jargon2 — 1.1.1 — Argon2 password hashing</li>
            <li>JNA — 5.14.0 — Native access used by the Argon2 backend</li>
            <li>Java-JWT — 4.5.0 — Token-based authentication</li>
            <li>Jakarta Mail — 2.0.1 — Email delivery and diagnostics</li>
          </ul>
          <h4>Backend testing</h4>
          <ul>
            <li>JUnit Jupiter — 5.12.0 — Unit and integration testing framework</li>
          </ul>
          <h4>Frontend runtime</h4>
          <ul>
            <li>React — 18.3.1 — UI framework</li>
            <li>React DOM — 18.3.1 — DOM rendering for React</li>
          </ul>
          <h4>Frontend development and testing</h4>
          <ul>
            <li>Vite — 6.3.5</li>
            <li>Vitest — 3.2.4</li>
            <li>@vitest/coverage-v8 — 3.2.4</li>
            <li>@testing-library/react — 16.3.0</li>
            <li>jsdom — 26.1.0</li>
            <li>TypeScript — 5.8.2</li>
            <li>@types/react — 18.3.18</li>
            <li>@types/react-dom — 18.3.5</li>
          </ul>
          <h4>Build and tooling</h4>
          <ul>
            <li>Maven Compiler Plugin — 3.14.0</li>
            <li>Maven Surefire Plugin — 3.5.2</li>
            <li>JaCoCo Maven Plugin — 0.8.14</li>
            <li>Exec Maven Plugin — 3.5.0</li>
          </ul>
          <p className="meta">
            <a href="https://github.com/robfiero/project-site-collector/blob/main/backend/pom.xml" target="_blank" rel="noreferrer">View backend pom.xml</a>
            {' '}·{' '}
            <a href="https://github.com/robfiero/project-site-collector/blob/main/ui/package.json" target="_blank" rel="noreferrer">View UI package.json</a>
          </p>
        </details>
      </section>
    </main>
  );
}
