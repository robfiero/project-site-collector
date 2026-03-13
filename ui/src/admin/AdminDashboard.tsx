import { formatDateTimeValue } from '../utils/date';
import { useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import StatusBadge from './StatusBadge';
import type {
  AdminEmailPreview,
  AdminTrendsSnapshot,
  CatalogDefaults,
  CollectorStatus,
  EventEnvelope,
  MetricsResponse,
  SignalsSnapshot,
  TrendPoint
} from '../models';
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
  trends: AdminTrendsSnapshot;
  trendsLoading: boolean;
  trendsError: string | null;
  emailPreview: AdminEmailPreview;
  emailPreviewLoading: boolean;
  emailPreviewError: string | null;
  versionInfo: { version?: string; buildTime?: string; gitSha?: string };
};

export default function AdminDashboard(props: AdminDashboardProps) {
  const degraded = props.health !== 'ok' || Object.values(props.collectorStatus).some((status) => status.lastSuccess === false);
  const [now, setNow] = useState<number>(Date.now());
  const [trendWindow, setTrendWindow] = useState<'60m' | '6h' | '24h'>('60m');
  const adminUnauthorized = (props.trendsError?.includes('(401)') ?? false) || (props.emailPreviewError?.includes('(401)') ?? false);
  const collectorSuccessTrend = aggregateTrend(props.trends, 'collector.runs.', '.success');
  const collectorFailureTrend = aggregateTrend(props.trends, 'collector.runs.', '.failure');
  const newsTrend = aggregateTrend(props.trends, 'ingested.news.');
  const localEventsTrend = aggregateTrend(props.trends, 'ingested.localEvents.');
  const latestTrendPointTime = getLatestTrendPointTime(collectorSuccessTrend, collectorFailureTrend, newsTrend, localEventsTrend);
  const trendWindowMinutes = trendWindow === '60m' ? 60 : trendWindow === '6h' ? 360 : 1440;
  const trendsWithWindow = {
    collectorSuccess: filterTrendPointsForWindow(collectorSuccessTrend, trendWindowMinutes, props.trends.bucketSeconds, latestTrendPointTime),
    collectorFailure: filterTrendPointsForWindow(collectorFailureTrend, trendWindowMinutes, props.trends.bucketSeconds, latestTrendPointTime),
    news: filterTrendPointsForWindow(newsTrend, trendWindowMinutes, props.trends.bucketSeconds, latestTrendPointTime),
    localEvents: filterTrendPointsForWindow(localEventsTrend, trendWindowMinutes, props.trends.bucketSeconds, latestTrendPointTime)
  };
  const trendWindowLabel = trendWindow === '60m' ? 'Showing last 60 minutes' : trendWindow === '6h' ? 'Showing last 6 hours' : 'Showing last 24 hours';

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
          <KpiCard
            icon="●"
            iconTone="health"
            label="Health"
            tone={degraded ? 'warn' : 'success'}
            value={<StatusBadge status={degraded ? 'Warning' : 'Healthy'}>{degraded ? 'Warning' : 'Healthy'}</StatusBadge>}
          />
          <KpiCard
            icon="⟳"
            iconTone="sse"
            label="SSE"
            tone={props.connectionState === 'open' ? 'success' : 'warn'}
            value={props.connectionState === 'open' ? (
              <StatusBadge status="Connected">Connected</StatusBadge>
            ) : (
              <StatusBadge status="Reconnecting">Reconnecting</StatusBadge>
            )}
          />
          <KpiCard icon="👥" iconTone="sse" label="SSE clients" value={String(props.metrics.sseClientsConnected)} />
          <KpiCard icon="⚡" iconTone="events" label="Events / min" value={String(props.metrics.recentEventsPerMinute)} />
          <KpiCard icon="📊" iconTone="neutral" label="Total events" value={String(props.metrics.eventsEmittedTotal)} />
        </div>
      </section>

      <section className="admin-context-strip">
        <span className="admin-context-label">Current user</span>
        {props.currentUser ? (
          <span className="admin-context-value">{props.currentUser.email} ({props.currentUser.id})</span>
        ) : (
          <span className="admin-context-value">Anonymous session</span>
        )}
      </section>
      <section className="admin-context-strip">
        <span className="admin-context-label">Version</span>
        <span className="admin-context-value">
          {props.versionInfo.version ?? 'Unknown'}
          {props.versionInfo.buildTime ? ` · ${props.versionInfo.buildTime}` : ''}
          {props.versionInfo.gitSha ? ` · ${props.versionInfo.gitSha}` : ''}
        </span>
      </section>

      <section className="card admin-trends">
        <h2 className="section-title">Trends</h2>
        <div className="trends-header-row">
          <p className="meta section-description">Collector outcomes and ingested volume across your configured window.</p>
          <label className="trend-window-control">
            <span>Window</span>
            <select
              aria-label="Trend window"
              value={trendWindow}
              onChange={(event) => {
                setTrendWindow(event.target.value as '60m' | '6h' | '24h');
              }}
            >
              <option value="60m">60m</option>
              <option value="6h">6h</option>
              <option value="24h">24h</option>
            </select>
          </label>
        </div>
        <p className="meta section-subtitle trend-window-label">{trendWindowLabel}</p>
        {adminUnauthorized ? <p className="empty">Unauthorized. Please log in to view admin diagnostics.</p> : null}
        {props.trendsLoading ? (
          <div className="skeleton-block">
            <div className="skeleton-line" />
            <div className="skeleton-line short" />
            <div className="skeleton-line" />
            <div className="skeleton-line short" />
          </div>
        ) : null}
        {!props.trendsLoading && props.trendsError && !adminUnauthorized ? <p className="empty">Trend data unavailable: {props.trendsError}</p> : null}
        {!props.trendsLoading && !props.trendsError && !adminUnauthorized && props.trends.series.length === 0 ? (
          <p className="empty">No trend data yet.</p>
        ) : null}
        {!props.trendsLoading && !props.trendsError && !adminUnauthorized && props.trends.series.length > 0 ? (
          <div className="trend-grid">
            <TrendChart title="Collector runs: success" tone="success" points={trendsWithWindow.collectorSuccess} />
            <TrendChart title="Collector runs: failure" tone="warn" points={trendsWithWindow.collectorFailure} />
            <TrendChart title="News ingested items" tone="info" points={trendsWithWindow.news} />
            <TrendChart title="Local happenings ingested" tone="neutral" points={trendsWithWindow.localEvents} />
          </div>
        ) : null}
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

      <section className="card admin-email">
        <h2 className="section-title">Email Diagnostics</h2>
        <p className="meta section-description">Digest preview and delivery diagnostics.</p>
        {props.emailPreviewLoading ? (
          <div className="skeleton-block">
            <div className="skeleton-line" />
            <div className="skeleton-line short" />
            <div className="skeleton-line" />
          </div>
        ) : null}
        {!props.emailPreviewLoading && props.emailPreviewError && !adminUnauthorized ? (
          <p className="empty">Email preview unavailable: {props.emailPreviewError}</p>
        ) : null}
        {!props.emailPreviewLoading && !props.emailPreviewError && !adminUnauthorized ? (
          <div className="email-preview">
            <section className="email-section">
              <h3 className="section-subtitle">Delivery</h3>
              <dl className="admin-email-grid">
                <div>
                  <dt>Delivery enabled</dt>
                  <dd><StatusBadge status={props.emailPreview.enabled ? 'Enabled' : 'Disabled'}>{props.emailPreview.enabled ? 'Enabled' : 'Disabled'}</StatusBadge></dd>
                </div>
                <div>
                  <dt>Active delivery mode</dt>
                  <dd>
                    <span>{formatDeliveryModeLabel(props.emailPreview.mode)}</span>
                    <p className="meta email-mode-helper">{deliveryModeHelperText(props.emailPreview.mode)}</p>
                  </dd>
                </div>
                <div>
                  <dt>Last sent</dt>
                  <dd>{props.emailPreview.lastSentAt || 'Not yet sent'}</dd>
                </div>
                <div>
                  <dt>Last error</dt>
                  <dd className="status-value">
                    {props.emailPreview.lastError ? (
                      <>
                        <StatusBadge status="error">Error</StatusBadge>
                        <span className="status-text">{props.emailPreview.lastError}</span>
                      </>
                    ) : (
                      <StatusBadge status="none">None</StatusBadge>
                    )}
                  </dd>
                </div>
              </dl>
            </section>
            <section className="email-section">
              <h3 className="section-subtitle">Included Counts</h3>
              <dl className="admin-email-grid">
                <div>
                  <dt>News</dt>
                  <dd>{props.emailPreview.includedCounts.newsStories}</dd>
                </div>
                <div>
                  <dt>Local events</dt>
                  <dd>{props.emailPreview.includedCounts.localEvents}</dd>
                </div>
                <div>
                  <dt>Weather</dt>
                  <dd>{props.emailPreview.includedCounts.weather}</dd>
                </div>
                <div>
                  <dt>Markets</dt>
                  <dd>{props.emailPreview.includedCounts.markets}</dd>
                </div>
              </dl>
            </section>
            <details>
              <summary>Email Digest Preview</summary>
              <p className="meta"><strong>{props.emailPreview.subject || 'No subject'}</strong></p>
              <pre className="email-preview-body">{props.emailPreview.body || 'No preview content available.'}</pre>
            </details>
          </div>
        ) : null}
        <h3 className="section-subtitle">Dev Outbox</h3>
        {props.devOutbox.length === 0 ? <p className="empty">No dev emails yet.</p> : (
          <div className="card-body">
            {props.devOutbox.map((email, idx) => (
              <article key={`${email.createdAt}-${idx}`} className="item">
                <h3>{email.subject}</h3>
                <p className="meta">To: {maskEmail(email.to)}</p>
                <p className="meta">{email.createdAt}</p>
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

function maskEmail(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const [local, domain] = value.split('@');
  if (!domain) {
    return value;
  }
  const prefix = local.slice(0, Math.min(2, local.length));
  return `${prefix}***@${domain}`;
}

function formatDeliveryModeLabel(mode: string): string {
  const normalized = mode.trim().toLowerCase();
  if (normalized === 'dev_outbox') {
    return 'Dev outbox';
  }
  if (normalized === 'smtp' || normalized === 'smtp_or_configured') {
    return 'SMTP configured';
  }
  const words = normalized
    .split(/[_\s]+/)
    .filter(Boolean);
  if (words.length === 0) {
    return 'Unknown';
  }
  return words
    .map((segment) => `${segment[0].toUpperCase()}${segment.slice(1)}`)
    .join(' ');
}

function deliveryModeHelperText(mode: string): string {
  const normalized = mode.trim().toLowerCase();
  if (normalized === 'dev_outbox') {
    return 'Emails are currently captured locally for diagnostics. Configure SMTP mode for real outbound delivery.';
  }
  if (normalized === 'smtp' || normalized === 'smtp_or_configured') {
    return 'Emails are currently sent using SMTP when configured.';
  }
  return 'Unknown delivery mode.';
}

function getLatestTrendPointTime(...trends: TrendPoint[][]): string | null {
  const allPoints = trends.flatMap((trend) => trend).filter((point) => Number.isFinite(Date.parse(point.timestamp)));
  if (allPoints.length === 0) {
    return null;
  }
  return allPoints.reduce((latest, point) => (point.timestamp > latest ? point.timestamp : latest), allPoints[0]!.timestamp);
}

function filterTrendPointsForWindow(
  points: TrendPoint[],
  minutes: number,
  bucketSeconds: number,
  latestPointTime: string | null
): TrendPoint[] {
  if (!latestPointTime) {
    return points;
  }
  const latestEpochMs = Date.parse(latestPointTime);
  const windowMs = minutes * 60 * 1000;
  const startWindowMs = latestEpochMs - windowMs + (bucketSeconds * 1000);
  return points.filter((point) => {
    const pointEpochMs = Date.parse(point.timestamp);
    return Number.isFinite(pointEpochMs) && pointEpochMs >= startWindowMs && pointEpochMs <= latestEpochMs;
  });
}

function aggregateTrend(trends: AdminTrendsSnapshot, prefix: string, suffix?: string): TrendPoint[] {
  const matches = trends.series.filter((series) => (
    series.key.startsWith(prefix) && (!suffix || series.key.endsWith(suffix))
  ));
  if (matches.length === 0) {
    return [];
  }
  const totals = new Map<string, number>();
  matches.forEach((series) => {
    series.points.forEach((point) => {
      const existing = totals.get(point.timestamp) ?? 0;
      totals.set(point.timestamp, existing + point.value);
    });
  });
  return Array.from(totals.entries())
    .map(([timestamp, value]) => ({ timestamp, value }))
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp));
}

function TrendChart(props: { title: string; points: TrendPoint[]; tone: 'success' | 'warn' | 'info' | 'neutral' }) {
  const width = 280;
  const height = 120;
  const padding = 12;
  if (props.points.length === 0) {
    return (
      <article className="trend-card">
        <h3>{props.title}</h3>
        <p className="empty">No data</p>
      </article>
    );
  }
  const max = Math.max(1, ...props.points.map((point) => point.value));
  const polyline = props.points.map((point, index) => {
    const x = padding + ((width - (padding * 2)) * (index / Math.max(1, props.points.length - 1)));
    const y = height - padding - ((height - (padding * 2)) * (point.value / max));
    return `${x},${y}`;
  }).join(' ');
  const lineBottom = height - padding;
  const lineTop = padding;
  const lineRange = height - (padding * 2);
  const gridYs = [lineBottom - (lineRange * 0.66), lineBottom - (lineRange * 0.33)];
  return (
    <article className={`trend-card tone-${props.tone}`}>
      <h3>{props.title}</h3>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={props.title}>
        <line x1={padding} x2={width - padding} y1={lineBottom} y2={lineBottom} className="trend-grid-line" />
        <line x1={padding} x2={width - padding} y1={lineTop} y2={lineTop} className="trend-grid-line" />
        {gridYs.map((gridY) => (
          <line key={gridY} x1={padding} x2={width - padding} y1={gridY} y2={gridY} className="trend-grid-line" />
        ))}
        <polyline points={polyline} fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <p className="meta">Latest: {props.points[props.points.length - 1]?.value ?? 0}</p>
    </article>
  );
}
