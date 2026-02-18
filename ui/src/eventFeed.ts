import type {
  AlertRaisedEvent,
  CollectorTickCompletedEvent,
  CollectorTickStartedEvent,
  ContentChangedEvent,
  EnvAqiUpdatedEvent,
  EnvWeatherUpdatedEvent,
  EventEnvelope,
  NewsUpdatedEvent,
  SiteFetchedEvent,
  WeatherUpdatedEvent
} from './models';

export function summarizeEvent(envelope: EventEnvelope): string {
  switch (envelope.type) {
    case 'WeatherUpdated': {
      const e = envelope.event as unknown as WeatherUpdatedEvent;
      return `${e.location}: ${e.tempF.toFixed(1)}F, ${e.conditions}`;
    }
    case 'EnvWeatherUpdated': {
      const e = envelope.event as unknown as EnvWeatherUpdatedEvent;
      const when = formatFetchedAtMillis(e.fetchedAtEpochMillis);
      const endpoint = shortenRequestUrl(e.requestUrl);
      if (e.status !== 'OK') {
        return `${e.zip}: weather ${e.status.toLowerCase()} (${normalizeSource(e.source)}) @ ${when} - ${e.error ?? 'unavailable'} [${endpoint}]`;
      }
      return `${e.zip}: ${e.tempF.toFixed(1)}F, ${e.conditions} (${normalizeSource(e.source)}) @ ${when} [${endpoint}]`;
    }
    case 'EnvAqiUpdated': {
      const e = envelope.event as unknown as EnvAqiUpdatedEvent;
      const when = formatFetchedAtMillis(e.fetchedAtEpochMillis);
      const endpoint = shortenRequestUrl(e.requestUrl);
      if (e.status !== 'OK') {
        return `${e.zip}: AQI ${e.status.toLowerCase()} (${normalizeSource(e.source)}) @ ${when} - ${e.error ?? e.message ?? 'unavailable'} [${endpoint}]`;
      }
      if (e.aqi == null) {
        return `${e.zip}: ${e.message ?? 'AQI unavailable'} (${normalizeSource(e.source)}) @ ${when} [${endpoint}]`;
      }
      return `${e.zip}: AQI ${e.aqi} (${e.category ?? 'Unknown'}) via ${normalizeSource(e.source)} @ ${when} [${endpoint}]`;
    }
    case 'NewsUpdated': {
      const e = envelope.event as unknown as NewsUpdatedEvent;
      return `${e.source}: ${e.storyCount} stories`;
    }
    case 'SiteFetched': {
      const e = envelope.event as unknown as SiteFetchedEvent;
      return `${e.siteId}: status ${e.status}, ${e.durationMillis}ms`;
    }
    case 'ContentChanged': {
      const e = envelope.event as unknown as ContentChangedEvent;
      return `${e.siteId}: content hash changed`;
    }
    case 'AlertRaised': {
      const e = envelope.event as unknown as AlertRaisedEvent;
      return `${e.category}: ${e.message}`;
    }
    case 'CollectorTickStarted': {
      const e = envelope.event as unknown as CollectorTickStartedEvent;
      return `collector ${e.collectorName} started`;
    }
    case 'CollectorTickCompleted': {
      const e = envelope.event as unknown as CollectorTickCompletedEvent;
      return `collector ${e.collectorName} ${e.success ? 'ok' : 'failed'} in ${e.durationMillis}ms`;
    }
    case 'UserRegistered':
      return 'user registered';
    case 'LoginSucceeded':
      return 'login succeeded';
    case 'LoginFailed':
      return 'login failed';
    case 'PasswordResetRequested':
      return 'password reset requested';
    case 'PasswordResetSucceeded':
      return 'password reset succeeded';
    case 'PasswordResetFailed':
      return 'password reset failed';
    default:
      return envelope.type;
  }
}

function formatFetchedAtMillis(value: unknown): string {
  const ms = toEpochMillis(value);
  if (!Number.isFinite(ms)) {
    return '-';
  }
  return new Date(ms).toLocaleTimeString();
}

function toEpochMillis(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string') {
    const numeric = Number.parseFloat(value);
    if (!Number.isNaN(numeric) && Number.isFinite(numeric)) {
      return numeric;
    }
  }
  return Number.NaN;
}

function normalizeSource(source: unknown): string {
  if (typeof source !== 'string' || source.trim().length === 0) {
    return 'UNKNOWN';
  }
  return source.toUpperCase();
}

function shortenRequestUrl(rawUrl: unknown): string {
  if (typeof rawUrl !== 'string' || rawUrl.trim().length === 0) {
    return 'request-url:n/a';
  }
  try {
    const parsed = new URL(rawUrl);
    return `${parsed.host}${parsed.pathname}`;
  } catch {
    const withoutQuery = rawUrl.split('?')[0];
    return withoutQuery;
  }
}

export function normalizeEventEnvelope(input: unknown): EventEnvelope | null {
  if (!input || typeof input !== 'object') {
    return null;
  }

  const raw = input as Record<string, unknown>;
  const wrapped = raw.event && typeof raw.event === 'object';
  const payload = (wrapped ? raw.event : raw) as Record<string, unknown>;
  const typeValue = raw.type ?? payload.type;
  if (typeof typeValue !== 'string') {
    return null;
  }

  const timestamp = toEpochSeconds(raw.timestamp ?? payload.timestamp);
  const eventPayload = { ...payload } as Record<string, unknown>;

  return {
    type: typeValue,
    timestamp,
    event: eventPayload
  };
}

export function filterEvents(
  events: EventEnvelope[],
  eventTypeFilter: string,
  searchQuery: string
): EventEnvelope[] {
  const query = searchQuery.trim().toLowerCase();
  return [...events]
    .reverse()
    .filter((item) => (eventTypeFilter === 'ALL' ? true : item.type === eventTypeFilter))
    .filter((item) => {
      if (!query) {
        return true;
      }
      return `${item.type} ${JSON.stringify(item.event)}`.toLowerCase().includes(query);
    });
}

export function toEpochSeconds(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string') {
    const numeric = Number.parseFloat(value);
    if (!Number.isNaN(numeric) && Number.isFinite(numeric)) {
      return numeric;
    }
    const ms = Date.parse(value);
    if (!Number.isNaN(ms)) {
      return ms / 1000;
    }
  }
  return Date.now() / 1000;
}
