import { cleanup, render, screen, within, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import AdminDashboard from './AdminDashboard';
import type { AdminEmailPreview, AdminTrendsSnapshot, CatalogDefaults, CollectorStatus, MetricsResponse, SignalsSnapshot } from '../models';

const emptyMetrics: MetricsResponse = {
  sseClientsConnected: 0,
  eventsEmittedTotal: 0,
  recentEventsPerMinute: 0,
  collectors: {}
};

const emptyDefaults: CatalogDefaults = {
  defaultZipCodes: ['02108', '98101'],
  defaultWatchlist: ['AAPL'],
  defaultNewsSources: []
};

const emptyTrends: AdminTrendsSnapshot = {
  windowStart: '2026-02-25T20:00:00Z',
  bucketSeconds: 300,
  series: []
};

const defaultEmailPreview: AdminEmailPreview = {
  enabled: true,
  mode: 'dev_outbox',
  lastSentAt: null,
  lastError: null,
  generatedAt: '2026-02-25T20:00:00Z',
  subject: "Today's Overview Digest Preview - 2026-02-25",
  body: 'Digest preview generated',
  includedCounts: {
    sites: 1,
    newsStories: 2,
    localEvents: 3,
    weather: 2,
    markets: 4
  }
};

const emptySignals: SignalsSnapshot['sites'][string][] = [];
const emptyNewsSnapshot: SignalsSnapshot['news'] = {};
const emptyCollectorStatus: Record<string, CollectorStatus> = {};

afterEach(() => {
  cleanup();
});

function renderDashboard(overrides?: Partial<ComponentProps<typeof AdminDashboard>>) {
  return render(
    <AdminDashboard
      health="ok"
      connectionState="open"
      metrics={emptyMetrics}
      metricsUpdatedAt={Date.now()}
      collectorStatus={emptyCollectorStatus}
      siteEntries={emptySignals}
      catalogDefaults={emptyDefaults}
      configView={{}}
      selectedNewsSourceIds={[]}
      newsSnapshot={emptyNewsSnapshot}
      filteredEvents={[]}
      eventTypeFilter="ALL"
      setEventTypeFilter={vi.fn()}
      searchQuery=""
      setSearchQuery={vi.fn()}
      expandedEvents={new Set()}
      setExpandedEvents={vi.fn()}
      knownTypes={['CollectorTickCompleted']}
      maxEvents={200}
      paused={false}
      onTogglePause={vi.fn()}
      devOutbox={[]}
      currentUser={null}
      trends={emptyTrends}
      trendsLoading={false}
      trendsError={null}
      emailPreview={defaultEmailPreview}
      emailPreviewLoading={false}
      emailPreviewError={null}
      versionInfo={{ version: '0.1.0', buildTime: '2026-03-08', gitSha: 'abc123' }}
      {...overrides}
    />
  );
}

describe('AdminDashboard', () => {
  it('renders trend charts when trend series data is present', () => {
    const trends: AdminTrendsSnapshot = {
      ...emptyTrends,
      series: [
        {
          key: 'collector.runs.rssCollector.success',
          points: [
            { timestamp: '2026-02-25T20:00:00Z', value: 0 },
            { timestamp: '2026-02-25T20:05:00Z', value: 1 }
          ]
        },
        {
          key: 'ingested.news.cnn',
          points: [
            { timestamp: '2026-02-25T20:00:00Z', value: 2 },
            { timestamp: '2026-02-25T20:05:00Z', value: 1 }
          ]
        }
      ]
    };
    renderDashboard({ trends });
    expect(screen.getByText('Collector runs: success')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Collector runs: success' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'News ingested items' })).toBeTruthy();
  });

  it('shows trend empty and error states', () => {
    renderDashboard({ trendsError: 'Admin trends request failed (500)' });
    expect(screen.getByText(/Trend data unavailable:/)).toBeTruthy();
  });

  it('shows no-data state when trends series is empty', () => {
    renderDashboard({ trends: { ...emptyTrends, series: [] }, trendsError: null });
    expect(screen.getByText(/No trend data yet/)).toBeTruthy();
  });

  it('renders email preview details and counts', () => {
    renderDashboard();
    expect(screen.getByText(/Email Diagnostics/)).toBeTruthy();
    expect(screen.getByText('Delivery')).toBeTruthy();
    expect(screen.getByText('Delivery enabled')).toBeTruthy();
    expect(screen.getByText('Active delivery mode')).toBeTruthy();
    expect(screen.getByText('Enabled')).toBeTruthy();
    expect(screen.getByText('Dev outbox')).toBeTruthy();
    expect(screen.getByText('Emails are currently captured locally for diagnostics. Configure SMTP mode for real outbound delivery.')).toBeTruthy();
    expect(screen.getByText('Last sent')).toBeTruthy();
    expect(screen.getByText('Not yet sent')).toBeTruthy();
    expect(screen.getByText('Included Counts')).toBeTruthy();
    expect(screen.getByText('Email Digest Preview')).toBeTruthy();
  });

  it('masks dev outbox recipients and hides reset link actions', () => {
    renderDashboard({
      devOutbox: [
        {
          to: 'robert@example.com',
          subject: 'Password reset request',
          body: 'Reset request body',
          createdAt: '2026-03-08T10:00:00Z',
          links: ['https://reset.example.com/token/abc123']
        }
      ]
    });
    expect(screen.getByText('Password reset request')).toBeTruthy();
    expect(screen.getByText('To: ro***@example.com')).toBeTruthy();
    expect(screen.queryByText('robert@example.com')).toBeNull();
    expect(screen.queryByText('Open reset link')).toBeNull();
    expect(screen.queryByText('Copy link')).toBeNull();
    expect(screen.queryByText('https://reset.example.com/token/abc123')).toBeNull();
  });

  it('renders smtp active mode label and helper text for configured smtp mode', () => {
    renderDashboard({
      emailPreview: {
        ...defaultEmailPreview,
        mode: 'smtp_or_configured'
      }
    });
    expect(screen.getByText('SMTP configured')).toBeTruthy();
    expect(screen.getByText('Emails are currently sent using SMTP when configured.')).toBeTruthy();
  });

  it('renders status badges in at-a-glance health and SSE fields', () => {
    renderDashboard();
    const healthyBadge = screen.getByText('Healthy');
    expect(healthyBadge).toBeTruthy();
    expect(healthyBadge.className).toContain('status-badge');
    expect(healthyBadge.className).toContain('success');
    const connectedBadge = screen.getByText('Connected');
    expect(connectedBadge).toBeTruthy();
    expect(connectedBadge.className).toContain('success');
  });

  it('shows warning badges when health is degraded', () => {
    renderDashboard({ health: 'degraded', connectionState: 'closed' });
    expect(screen.getByText('Warning')).toBeTruthy();
    const warningBadge = screen.getByText('Warning');
    expect(warningBadge.className).toContain('status-badge');
    expect(warningBadge.className).toContain('warning');
    expect(screen.getByText('Reconnecting')).toBeTruthy();
  });

  it('uses status badges in collector status table and supports unknown fallback', () => {
    renderDashboard({
      collectorStatus: {
        envCollector: {
          lastRunAt: '2026-02-25T20:00:00Z',
          lastDurationMillis: 12,
          lastSuccess: true,
          lastErrorMessage: null
        },
        rssCollector: {
          lastRunAt: null,
          lastDurationMillis: null,
          lastSuccess: null,
          lastErrorMessage: null
        },
        localEventsCollector: {
          lastRunAt: null,
          lastDurationMillis: 7,
          lastSuccess: false,
          lastErrorMessage: 'temporary network error'
        }
      }
    });
    const healthy = screen.getByText('Healthy');
    expect(healthy.className).toContain('success');
    const failed = screen.getByText('Failed');
    expect(failed.className).toContain('error');
    const unknown = screen.getByText('Unknown');
    expect(unknown.className).toContain('neutral');
    expect(screen.getByText('temporary network error')).toBeTruthy();
  });

  it('shows email preview error state', () => {
    renderDashboard({ emailPreviewError: 'Admin email preview request failed (500)' });
    expect(screen.getByText(/Email preview unavailable:/)).toBeTruthy();
  });

  it('defaults trends to 60m and supports switching to 6h and 24h', () => {
    renderDashboard({
      trends: {
        ...emptyTrends,
        series: [
          {
            key: 'ingested.localEvents.feed',
            points: [
              { timestamp: '2026-02-25T07:00:00Z', value: 2 }
            ]
          },
          {
            key: 'ingested.news.cnn',
            points: [
              { timestamp: '2026-02-25T08:10:00Z', value: 3 }
            ]
          },
          {
            key: 'collector.runs.rssCollector.success',
            points: [
              { timestamp: '2026-02-25T08:00:00Z', value: 1 },
              { timestamp: '2026-02-25T08:30:00Z', value: 1 }
            ]
          },
          {
            key: 'collector.runs.rssCollector.failure',
            points: [
              { timestamp: '2026-02-25T08:30:00Z', value: 1 }
            ]
          }
        ]
      }
    });
    expect(screen.getByText('Showing last 60 minutes')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Collector runs: success' })).toBeTruthy();
    const trendSelect = screen.getByLabelText('Trend window');
    expect(trendSelect).toBeTruthy();
    expect((trendSelect as HTMLSelectElement).value).toBe('60m');

    const localEventsCard = screen.getByText('Local happenings ingested').closest('article');
    expect(localEventsCard).toBeTruthy();
    expect(within(localEventsCard!).getByText('No data')).toBeTruthy();

    fireEvent.change(trendSelect, { target: { value: '6h' } });
    expect(screen.getByText('Showing last 6 hours')).toBeTruthy();
    expect(within(localEventsCard!).queryByText('No data')).toBeNull();

    fireEvent.change(trendSelect, { target: { value: '24h' } });
    expect(screen.getByText('Showing last 24 hours')).toBeTruthy();
  });

  it('shows friendly unauthorized state for admin endpoint 401 responses', () => {
    renderDashboard({
      trendsError: 'Admin trends request failed (401)',
      emailPreviewError: 'Admin email preview request failed (401)'
    });
    expect(screen.getByText(/Session expired\. Please log in again\./)).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'Collector runs: success' })).toBeNull();
  });

  it('shows trend loading skeletons while admin trend data is loading', () => {
    renderDashboard({ trendsLoading: true });
    expect(document.querySelectorAll('.skeleton-block').length).toBeGreaterThan(0);
    expect(screen.queryByRole('img', { name: 'Collector runs: success' })).toBeNull();
  });

  it('shows email preview loading skeletons while preview is loading', () => {
    renderDashboard({ emailPreviewLoading: true });
    expect(document.querySelectorAll('.skeleton-block').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Email digest preview/)).toBeNull();
  });
});
