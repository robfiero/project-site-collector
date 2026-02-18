import { formatDateTimeValue } from '../utils/date';
import { useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { CatalogDefaults, CollectorStatus, EventEnvelope, MetricsResponse, SignalsSnapshot } from '../models';
import type { AuthUserView, DevOutboxEmail } from '../api';
import KpiCard from './KpiCard';
import CollectorStatusCard from './CollectorStatusCard';
import ConfigCard from './ConfigCard';
import EventStreamCard from './EventStreamCard';

type AdminDashboardProps = {
  health: string;
  connectionState: string;
  metrics: MetricsResponse;
  metricsUpdatedAt: number;
  collectorStatus: Record<string, CollectorStatus>;
  siteEntries: SignalsSnapshot['sites'][string][];
  catalogDefaults: CatalogDefaults;
  configView: Record<string, unknown>;
  filteredEvents: EventEnvelope[];
  eventTypeFilter: string;
  setEventTypeFilter: Dispatch<SetStateAction<string>>;
  searchQuery: string;
  setSearchQuery: Dispatch<SetStateAction<string>>;
  expandedEvents: Set<string>;
  setExpandedEvents: Dispatch<SetStateAction<Set<string>>>;
  knownTypes: readonly string[];
  maxEvents: number;
  paused: boolean;
  onTogglePause: () => void;
  devOutbox: DevOutboxEmail[];
  currentUser: AuthUserView | null;
};

export default function AdminDashboard(props: AdminDashboardProps) {
  const degraded = props.health !== 'ok' || Object.values(props.collectorStatus).some((status) => status.lastSuccess === false);
  const [now, setNow] = useState<number>(Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <main className="admin-layout">
      <section className="admin-kpi-section">
        <div className="admin-heading-row">
          <div>
            <h2>At a glance</h2>
            <p className="meta">Current platform health and stream activity.</p>
          </div>
          <p className="meta kpi-updated">Updated {formatUpdatedAge(props.metricsUpdatedAt, now)}</p>
        </div>
        <div className="admin-kpi-grid">
          <KpiCard icon="●" iconTone="health" label="Health" value={degraded ? 'Degraded' : 'OK'} tone={degraded ? 'warn' : 'success'} />
          <KpiCard icon="⟳" iconTone="sse" label="SSE" value={props.connectionState === 'open' ? 'Connected' : 'Reconnecting'} tone={props.connectionState === 'open' ? 'success' : 'warn'} />
          <KpiCard icon="👥" iconTone="sse" label="SSE clients" value={String(props.metrics.sseClientsConnected)} />
          <KpiCard icon="⚡" iconTone="events" label="Events / min" value={String(props.metrics.recentEventsPerMinute)} />
          <KpiCard icon="📊" iconTone="neutral" label="Total events" value={String(props.metrics.eventsEmittedTotal)} />
        </div>
      </section>

      <section className="card admin-trends">
        <h2 className="section-title">Trends (coming soon)</h2>
        <p className="meta section-description">Chart panels for event throughput and collector trends will appear here.</p>
        <p className="empty">Event and collector trend charts will appear here in a future release.</p>
      </section>

      <section className="card collector-card">
        <h2 className="section-title">Current user</h2>
        <p className="meta section-description">Session visibility for demo and diagnostics.</p>
        {props.currentUser ? (
          <p className="meta"><strong>{props.currentUser.email}</strong> ({props.currentUser.id})</p>
        ) : (
          <p className="empty">Anonymous session</p>
        )}
      </section>

      <CollectorStatusCard collectorStatus={props.collectorStatus} />
      <ConfigCard catalogDefaults={props.catalogDefaults} configView={props.configView} />

      <section className="card sites">
        <h2 className="section-title">Sites (diagnostic)</h2>
        <p className="meta section-description">Latest site snapshots tracked by the collector runtime.</p>
        {props.siteEntries.length === 0 ? <p className="empty">No site signals yet.</p> : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Site</th>
                  <th>Title</th>
                  <th>Links</th>
                  <th>Last Checked</th>
                  <th>Last Changed</th>
                  <th>Hash</th>
                </tr>
              </thead>
              <tbody>
                {props.siteEntries.map((site) => (
                  <tr key={site.siteId}>
                    <td><a href={site.url} target="_blank" rel="noreferrer">{site.siteId}</a></td>
                    <td>{site.title ?? '-'}</td>
                    <td>{site.linkCount}</td>
                    <td className="site-date">{formatDateTimeValue(site.lastChecked)}</td>
                    <td className="site-date">{formatDateTimeValue(site.lastChanged)}</td>
                    <td className="hash">{formatHash(site.hash)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h2 className="section-title">Email Outbox (dev)</h2>
        <p className="meta section-description">Development-only email capture for reset/signup flows.</p>
        {props.devOutbox.length === 0 ? (
          <p className="empty">No dev emails yet.</p>
        ) : (
          <div className="card-body">
            {props.devOutbox.map((email, idx) => (
              <article key={`${email.createdAt}-${idx}`} className="item">
                <h3>{email.subject}</h3>
                <p className="meta">To: {email.to}</p>
                <p className="meta">{email.createdAt}</p>
                {email.links?.[0] && (
                  <div className="outbox-actions">
                    <a href={email.links[0]} target="_blank" rel="noreferrer">Open reset link</a>
                    <button
                      type="button"
                      onClick={async () => {
                        if (!navigator?.clipboard?.writeText) {
                          return;
                        }
                        await navigator.clipboard.writeText(email.links[0]);
                      }}
                    >
                      Copy link
                    </button>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </section>

      <EventStreamCard
        filteredEvents={props.filteredEvents}
        eventTypeFilter={props.eventTypeFilter}
        setEventTypeFilter={props.setEventTypeFilter}
        searchQuery={props.searchQuery}
        setSearchQuery={props.setSearchQuery}
        expandedEvents={props.expandedEvents}
        setExpandedEvents={props.setExpandedEvents}
        knownTypes={props.knownTypes}
        maxEvents={props.maxEvents}
        paused={props.paused}
        onTogglePause={props.onTogglePause}
      />
    </main>
  );
}

function formatUpdatedAge(updatedAt: number, now: number): string {
  const diffSeconds = Math.max(0, Math.floor((now - updatedAt) / 1000));
  if (diffSeconds < 5) {
    return 'just now';
  }
  return `${diffSeconds}s ago`;
}

function formatHash(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  if (value.length <= 14) {
    return value;
  }
  return `${value.slice(0, 6)}…${value.slice(-6)}`;
}
