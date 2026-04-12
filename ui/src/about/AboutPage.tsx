import { useState, useEffect, useCallback } from 'react';

const REPO_BASE = 'https://github.com/robfiero/project-site-collector/blob/main/';

function SourceLink(props: { path: string; label: string }) {
  return (
    <a href={`${REPO_BASE}${props.path}`} target="_blank" rel="noreferrer">
      {props.label}
    </a>
  );
}

export default function AboutPage() {
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [lightboxAlt, setLightboxAlt] = useState('');
  const [zoomed, setZoomed] = useState(false);

  const openLightbox = useCallback((src: string, alt: string) => {
    setLightboxSrc(src);
    setLightboxAlt(alt);
    setZoomed(false);
  }, []);

  const closeLightbox = useCallback(() => {
    setLightboxSrc(null);
    setZoomed(false);
  }, []);

  useEffect(() => {
    if (!lightboxSrc) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeLightbox(); };
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
    };
  }, [lightboxSrc, closeLightbox]);

  return (
    <>
    <main className="settings-page">
      <section className="card">
        <h2>About Today&apos;s Overview</h2>
        <div className="about-author">
          <div className="about-author-panel">
            <p className="about-author-title">Created by <strong>Robert Fiero</strong></p>
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
          Today&apos;s Overview is a personal engineering project focused on building an operator-style dashboard for real-time signals. It brings together six signal types — site monitoring, news, weather, air quality, local events, and market quotes — into a compact view designed to be scanned quickly.
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
          <p className="meta section-description">A production-style system designed to explore real-world engineering outcomes.</p>
          <ul>
            <li>Shipping a full-stack system with real data ingestion and delivery.</li>
            <li>Stress-testing reliability patterns such as polling, backoff, and graceful failure.</li>
            <li>Building a dashboard that stays readable while underlying data changes continuously.</li>
            <li>Exploring modern Java concurrency and system observability in a real project.</li>
            <li>Building and operating a complete auth system — signup, login, password reset, and JWT-based sessions.</li>
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
          <h3>Backend</h3>
          <p className="meta section-description">Java 25 service with structured concurrency, event-driven collectors, and file-based persistence.</p>
          <ul>
            <li>Java 25 with structured concurrency (<code>StructuredTaskScope</code>) and virtual threads for lightweight, high-concurrency collector execution.</li>
            <li>Three-module Maven build: <code>core</code> (models, events), <code>collectors</code> (data plugins), <code>service</code> (API, auth, scheduling).</li>
            <li>Pub/Sub event bus using <code>ConcurrentHashMap</code> and <code>CopyOnWriteArrayList</code> for thread-safe fan-out to subscribers.</li>
            <li>Collector plugin pattern — each source implements a single <code>poll()</code> interface returning a <code>CompletableFuture</code>.</li>
            <li>JWT + Argon2id authentication with server-side user and preference storage.</li>
            <li>Server-Sent Events (SSE) streaming via a custom broadcaster with 15-second keepalive heartbeats.</li>
            <li>JSONL append-only event store for operational replay, trend analysis, and diagnostics.</li>
            <li>File-based JSON persistence for signals and user state; S3-compatible in production.</li>
            <li>Deployed on AWS App Runner (Docker / openjdk 25) with an EBS-backed data volume.</li>
          </ul>
        </section>

        <section className="card">
          <h3>Frontend</h3>
          <p className="meta section-description">React 18 + TypeScript UI with real-time SSE updates and no external state library.</p>
          <ul>
            <li>React 18 + TypeScript 5.8 — hooks-based state management (<code>useState</code>, <code>useEffect</code>, <code>useRef</code>) with no external state library.</li>
            <li>Vite 6 for builds and HMR, with a <code>/api</code> proxy to the Java backend during development.</li>
            <li>Real-time updates via <code>EventSource</code> SSE listener on <code>/api/stream</code>, with automatic reconnect and backoff.</li>
            <li>TypeScript discriminated union types across all signal and event model types for type-safe parsing and rendering.</li>
            <li>Dual-mode preferences — <code>localStorage</code> for anonymous users, server-side persistence for authenticated users.</li>
            <li>Accessible light/dark themes with customizable accent colors, driven by CSS variables.</li>
            <li>Admin and diagnostics dashboard built directly into the UI — collector health, live event feed, trend charts, and config viewer.</li>
            <li>Deployed as a static build to AWS S3 + CloudFront CDN.</li>
          </ul>
        </section>
      </section>

      <section className="card">
        <h3>Architecture and engineering themes</h3>
        <p className="meta section-description">Patterns that keep the system resilient, observable, and easy to extend.</p>
        <ul>
          <li><strong>Pub/Sub event bus</strong> — collectors publish domain events; the SSE broadcaster and event store subscribe independently. Ingestion, delivery, and storage are fully decoupled.</li>
          <li><strong>Collector plugin pattern</strong> — each data source implements a single <code>poll()</code> interface. New signal types can be added without changing core infrastructure.</li>
          <li><strong>JSONL append-only event store</strong> — all system events are logged in line-delimited JSON, enabling operational replay, trend analysis, and debugging without a database.</li>
          <li><strong>SSE broadcaster</strong> — maintains persistent client connections with 15-second heartbeats, pushing updates the moment they are published to the event bus.</li>
          <li><strong>Graceful degradation</strong> — upstream API failures produce empty results, not crashes. The dashboard stays readable throughout collector failures and slow upstreams.</li>
          <li><strong>Clear module boundaries</strong> — ingestion (<code>collectors</code>), models and events (<code>core</code>), and delivery plus auth (<code>service</code>) are compiled as separate Maven modules with explicit dependencies.</li>
          <li><strong>Observability as a product feature</strong> — the diagnostics dashboard, event feed, and trend analysis are built into the UI, not added as an afterthought.</li>
        </ul>
      </section>

      <section className="card about-architecture">
        <h3>Architecture</h3>
        <p className="meta section-description">
          This project is designed as a production-style system. The diagrams below illustrate both the internal service architecture and the AWS deployment used to host the backend.
        </p>
        <div className="about-architecture-diagrams">
          <div className="about-architecture-diagram">
            <h4>System Architecture</h4>
            <button
              className="about-diagram-trigger"
              onClick={() => openLightbox('/architecture/system-architecture-diagram.svg', 'System architecture diagram')}
              aria-label="View system architecture diagram full screen"
            >
              <img
                src="/architecture/system-architecture-diagram.svg"
                alt="System architecture diagram"
                loading="lazy"
              />
              <span className="about-diagram-zoom-hint" aria-hidden="true">Click to enlarge</span>
            </button>
          </div>
          <div className="about-architecture-diagram">
            <h4>AWS Deployment Architecture</h4>
            <button
              className="about-diagram-trigger"
              onClick={() => openLightbox('/architecture/aws-deployment-diagram.png', 'AWS deployment architecture diagram')}
              aria-label="View AWS deployment architecture diagram full screen"
            >
              <img
                src="/architecture/aws-deployment-diagram.png"
                alt="AWS deployment architecture diagram"
                loading="lazy"
              />
              <span className="about-diagram-zoom-hint" aria-hidden="true">Click to enlarge</span>
            </button>
          </div>
        </div>
      </section>

      <section className="card">
        <h3>AI-Assisted Engineering Workflow</h3>
        <p className="meta section-description">Three AI tools contributed at different stages and in different ways throughout development.</p>
        <p>
          <strong>ChatGPT</strong> was used for early ideation and architecture brainstorming — exploring implementation approaches and engineering tradeoffs before committing to a direction.
        </p>
        <p>
          <strong>GitHub Copilot / Codex</strong> was used for inline code completion and targeted code generation during active development.
        </p>
        <p>
          <strong>Claude Code</strong> (Anthropic&apos;s CLI agent) became the primary AI engineering partner for the current development phase. The workflow included:
        </p>
        <ul>
          <li>Exploring large, unfamiliar parts of the codebase across multiple files with natural language queries.</li>
          <li>Iterating on implementation details across Java and TypeScript in a single session.</li>
          <li>Generating and refining unit tests to maintain consistent code coverage across the three-module build.</li>
          <li>Incremental UX polish passes — adjusting styles, layout, and copy in fast loops.</li>
          <li>Reviewing architectural tradeoffs before committing to a direction.</li>
          <li>Diagnosing errors in compiler output, test failures, and API responses.</li>
        </ul>
        <p>
          Architecture decisions, product direction, and final engineering judgment remained intentional and hands-on throughout. All AI-assisted code was reviewed and integrated against the same standards as human-written code.
        </p>
        <p>
          One of the goals of this project is to demonstrate how modern engineers can use AI tools responsibly — not as a substitute for judgment, but as a force multiplier for iteration, clarity, and delivery.
        </p>
      </section>

      <section className="settings-grid">
        <section className="card">
          <h3>What I learned</h3>
          <p className="meta section-description">The polish comes from small, repeatable decisions.</p>
          <ul>
            <li>Balancing immediate UI feedback with eventual consistency in background refresh.</li>
            <li>Designing authentication flows that remain clear even when backend systems respond slowly.</li>
            <li>Treating observability and diagnostics as core product features rather than afterthoughts.</li>
            <li>Working with modern Java concurrency primitives — virtual threads and structured concurrency — in a real system under real conditions.</li>
            <li>Building systems that evolve through small, iterative improvements rather than large rewrites.</li>
            <li>Integrating AI-assisted workflows across multiple layers of the stack — from brainstorming and architecture to code generation, test writing, and UX polish — without losing engineering ownership of decisions.</li>
          </ul>
        </section>

        <section className="card">
          <h3>Why this project matters</h3>
          <p className="meta section-description">A credible demo of engineering rigor and product thinking.</p>
          <ul>
            <li>Demonstrates end-to-end ownership across data collection, storage, UI, and operational readiness.</li>
            <li>Shows attention to failure modes, diagnostics, and safe defaults.</li>
            <li>Built to be demo-friendly without sacrificing realistic architecture.</li>
            <li>87% automated test coverage (97% core module), enforced via JaCoCo across a three-module Maven build.</li>
            <li>Demonstrates responsible, hands-on AI-assisted engineering — using multiple AI tools as force multipliers without outsourcing judgment.</li>
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

    {lightboxSrc && (
      <div
        className={`about-lightbox-overlay${zoomed ? ' zoomed' : ''}`}
        onClick={closeLightbox}
        role="dialog"
        aria-modal="true"
        aria-label={lightboxAlt}
      >
        <button className="about-lightbox-close" onClick={closeLightbox} aria-label="Close">
          ×
        </button>
        <img
          className={`about-lightbox-image${zoomed ? ' zoomed' : ''}`}
          src={lightboxSrc ?? undefined}
          alt={lightboxAlt}
          onClick={e => { e.stopPropagation(); setZoomed(z => !z); }}
        />
      </div>
    )}
    </>
  );
}
