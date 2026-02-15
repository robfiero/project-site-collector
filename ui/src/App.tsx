import { useEffect, useMemo, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { fetchEvents, fetchHealth, fetchSignals } from './api';
import { filterEvents, normalizeEventEnvelope, summarizeEvent } from './eventFeed';
import { epochSecondsToDate, formatEpochSecondsDateTime, formatEpochSecondsTime } from './utils/date';
import type {
  AlertRaisedEvent,
  CollectorTickCompletedEvent,
  CollectorTickStartedEvent,
  ContentChangedEvent,
  EventEnvelope,
  NewsUpdatedEvent,
  SignalsSnapshot,
  SiteFetchedEvent,
  WeatherUpdatedEvent
} from './models';

const MAX_EVENTS = 200;

const emptySnapshot: SignalsSnapshot = {
  sites: {},
  news: {},
  weather: {}
};

type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed';
const KNOWN_SSE_TYPES = [
  'CollectorTickStarted',
  'CollectorTickCompleted',
  'AlertRaised',
  'SiteFetched',
  'ContentChanged',
  'WeatherUpdated',
  'NewsUpdated'
] as const;

export default function App() {
  const [health, setHealth] = useState<string>('unknown');
  const [snapshot, setSnapshot] = useState<SignalsSnapshot>(emptySnapshot);
  const [events, setEvents] = useState<EventEnvelope[]>([]);
  const [eventTypeFilter, setEventTypeFilter] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');
  const [error, setError] = useState<string | null>(null);
  const [expandedEvents, setExpandedEvents] = useState<Set<string>>(new Set());

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const retryDelayRef = useRef<number>(1000);

  useEffect(() => {
    let mounted = true;

    async function bootstrap(): Promise<void> {
      try {
        const [healthResponse, signalsResponse, eventsResponse] = await Promise.all([
          fetchHealth(),
          fetchSignals(),
          fetchEvents(100)
        ]);

        if (!mounted) {
          return;
        }

        setHealth(healthResponse.status);
        setSnapshot(signalsResponse);
        setEvents(eventsResponse.map(normalizeEventEnvelope).filter((e): e is EventEnvelope => e !== null).slice(-MAX_EVENTS));
        setError(null);
      } catch (err) {
        if (!mounted) {
          return;
        }
        setError(err instanceof Error ? err.message : 'Failed to load dashboard');
      }
    }

    bootstrap();

    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    let disposed = false;

    const connect = (): void => {
      if (disposed) {
        return;
      }

      setConnectionState((state) => (state === 'open' ? state : 'connecting'));
      const source = new EventSource('/api/stream');
      eventSourceRef.current = source;

      source.onopen = () => {
        retryDelayRef.current = 1000;
        setConnectionState('open');
      };

      const onSseMessage = (message: MessageEvent<string>) => {
        try {
          const normalized = normalizeEventEnvelope(JSON.parse(message.data));
          if (normalized) {
            ingestEvent(normalized);
          }
        } catch {
          // Keep stream alive even if one message is malformed.
        }
      };

      KNOWN_SSE_TYPES.forEach((type) => source.addEventListener(type, onSseMessage as EventListener));
      source.onmessage = onSseMessage;

      source.onerror = () => {
        source.close();
        if (disposed) {
          return;
        }
        setConnectionState('reconnecting');
        const wait = retryDelayRef.current;
        retryDelayRef.current = Math.min(retryDelayRef.current * 2, 10_000);
        reconnectTimerRef.current = window.setTimeout(connect, wait);
      };
    };

    connect();

    return () => {
      disposed = true;
      setConnectionState('closed');
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current);
      }
      eventSourceRef.current?.close();
    };
  }, []);

  const ingestEvent = (envelope: EventEnvelope): void => {
    setEvents((previous) => [...previous, envelope].slice(-MAX_EVENTS));
    setSnapshot((previous) => applyOptimisticUpdate(previous, envelope));
  };

  const filteredEvents = useMemo(() => {
    return filterEvents(events, eventTypeFilter, searchQuery);
  }, [events, eventTypeFilter, searchQuery]);

  const weatherEntries = Object.values(snapshot.weather).sort((a, b) => a.location.localeCompare(b.location));
  const newsEntries = Object.values(snapshot.news).sort((a, b) => a.source.localeCompare(b.source));
  const siteEntries = Object.values(snapshot.sites).sort((a, b) => a.siteId.localeCompare(b.siteId));

  return (
    <div className="app">
      <header className="header card">
        <h1>Signal Sentinel</h1>
        <div className="status-row">
          <span>Health: <strong>{health}</strong></span>
          <span>Connection: <strong>{connectionState}</strong></span>
          {connectionState === 'reconnecting' && <span className="reconnecting">Reconnecting...</span>}
        </div>
        {error && <p className="error">{error}</p>}
      </header>

      <main className="grid">
        <section className="card weather">
          <h2>Weather</h2>
          {weatherEntries.length === 0 ? <p className="empty">No weather signals yet.</p> : weatherEntries.map((item) => (
            <article key={item.location} className="item">
              <h3>{item.location}</h3>
              <p>{item.tempF.toFixed(1)} F, {item.conditions}</p>
              <p className="meta">Updated: {epochSecondsToDate(item.updatedAt).toLocaleString()}</p>
              {item.alerts.length > 0 && (
                <ul>
                  {item.alerts.map((alert, index) => <li key={`${item.location}-${index}`}>{alert}</li>)}
                </ul>
              )}
            </article>
          ))}
        </section>

        <section className="card news">
          <h2>Top News</h2>
          {newsEntries.length === 0 ? <p className="empty">No news signals yet.</p> : newsEntries.map((source) => (
            <article key={source.source} className="item">
              <h3>{source.source}</h3>
              <p className="meta">Updated: {formatInstant(source.updatedAt)}</p>
              {source.stories.length === 0 ? (
                <p className="empty">No stories in current snapshot.</p>
              ) : (
                <ul>
                  {source.stories.map((story, idx) => (
                    <li key={`${source.source}-${idx}`}>
                      <a href={story.link} target="_blank" rel="noreferrer">{story.title}</a>
                      <span className="meta"> ({formatInstant(story.publishedAt)})</span>
                    </li>
                  ))}
                </ul>
              )}
            </article>
          ))}
        </section>

        <section className="card sites">
          <h2>Sites</h2>
          {siteEntries.length === 0 ? (
            <p className="empty">No site signals yet.</p>
          ) : (
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
                  {siteEntries.map((site) => (
                    <tr key={site.siteId}>
                      <td><a href={site.url} target="_blank" rel="noreferrer">{site.siteId}</a></td>
                      <td>{site.title ?? '-'}</td>
                      <td>{site.linkCount}</td>
                      <td>{formatInstant(site.lastChecked)}</td>
                      <td>{formatInstant(site.lastChanged)}</td>
                      <td className="hash">{site.hash || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="card events">
          <div className="event-header">
            <h2>Live Event Feed</h2>
            <div className="filters">
              <select value={eventTypeFilter} onChange={(e) => setEventTypeFilter(e.target.value)}>
                <option value="ALL">All types</option>
                <option value="CollectorTickStarted">CollectorTickStarted</option>
                <option value="CollectorTickCompleted">CollectorTickCompleted</option>
                <option value="SiteFetched">SiteFetched</option>
                <option value="ContentChanged">ContentChanged</option>
                <option value="NewsUpdated">NewsUpdated</option>
                <option value="WeatherUpdated">WeatherUpdated</option>
                <option value="AlertRaised">AlertRaised</option>
              </select>
              <input
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search events"
              />
            </div>
          </div>
          <p className="meta">Showing {filteredEvents.length} events (max stored: {MAX_EVENTS})</p>
          <div className="event-list">
            {filteredEvents.length === 0 ? (
              <p className="empty">No events match current filters.</p>
            ) : (
              filteredEvents.map((entry, index) => (
                <article key={`${entry.timestamp}-${index}`} className="event-item">
                  <div className="event-title">
                    <strong>{formatEpochSecondsTime(entry.timestamp)} | {entry.type}</strong>
                    <span>{summarizeEvent(entry)}</span>
                  </div>
                  <p className="meta">At {formatEpochSecondsDateTime(entry.timestamp)}</p>
                  <button
                    type="button"
                    className="toggle"
                    onClick={() => toggleExpanded(eventRowId(entry, index), setExpandedEvents)}
                  >
                    {expandedEvents.has(eventRowId(entry, index)) ? 'Hide details' : 'Show details'}
                  </button>
                  {expandedEvents.has(eventRowId(entry, index)) && (
                    <pre>{JSON.stringify(entry, null, 2)}</pre>
                  )}
                </article>
              ))
            )}
          </div>
        </section>
      </main>
    </div>
  );
}

function applyOptimisticUpdate(snapshot: SignalsSnapshot, envelope: EventEnvelope): SignalsSnapshot {
  switch (envelope.type) {
    case 'WeatherUpdated': {
      const weatherEvent = envelope.event as unknown as WeatherUpdatedEvent;
      return {
        ...snapshot,
        weather: {
          ...snapshot.weather,
          [weatherEvent.location]: {
            location: weatherEvent.location,
            tempF: weatherEvent.tempF,
            conditions: weatherEvent.conditions,
            alerts: snapshot.weather[weatherEvent.location]?.alerts ?? [],
            updatedAt: weatherEvent.timestamp
          }
        }
      };
    }
    case 'NewsUpdated': {
      const newsEvent = envelope.event as unknown as NewsUpdatedEvent;
      const current = snapshot.news[newsEvent.source];
      return {
        ...snapshot,
        news: {
          ...snapshot.news,
          [newsEvent.source]: {
            source: newsEvent.source,
            stories: current?.stories?.slice(0, newsEvent.storyCount) ?? [],
            updatedAt: epochSecondsToDate(newsEvent.timestamp).toISOString()
          }
        }
      };
    }
    case 'SiteFetched': {
      const siteEvent = envelope.event as unknown as SiteFetchedEvent;
      const current = snapshot.sites[siteEvent.siteId];
      return {
        ...snapshot,
        sites: {
          ...snapshot.sites,
          [siteEvent.siteId]: {
            siteId: siteEvent.siteId,
            url: current?.url ?? siteEvent.url,
            hash: current?.hash ?? '',
            title: current?.title ?? null,
            linkCount: current?.linkCount ?? 0,
            lastChecked: epochSecondsToDate(siteEvent.timestamp).toISOString(),
            lastChanged: current?.lastChanged ?? epochSecondsToDate(siteEvent.timestamp).toISOString()
          }
        }
      };
    }
    case 'ContentChanged': {
      const changeEvent = envelope.event as unknown as ContentChangedEvent;
      const current = snapshot.sites[changeEvent.siteId];
      return {
        ...snapshot,
        sites: {
          ...snapshot.sites,
          [changeEvent.siteId]: {
            siteId: changeEvent.siteId,
            url: current?.url ?? changeEvent.url,
            hash: changeEvent.newHash,
            title: current?.title ?? null,
            linkCount: current?.linkCount ?? 0,
            lastChecked: current?.lastChecked ?? epochSecondsToDate(changeEvent.timestamp).toISOString(),
            lastChanged: epochSecondsToDate(changeEvent.timestamp).toISOString()
          }
        }
      };
    }
    default:
      return snapshot;
  }
}

function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function eventRowId(entry: EventEnvelope, index: number): string {
  return `${entry.type}-${entry.timestamp}-${index}`;
}

function toggleExpanded(rowId: string, setExpandedEvents: Dispatch<SetStateAction<Set<string>>>): void {
  setExpandedEvents((prev) => {
    const next = new Set(prev);
    if (next.has(rowId)) {
      next.delete(rowId);
    } else {
      next.add(rowId);
    }
    return next;
  });
}
