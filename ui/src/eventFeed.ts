import type {
  AlertRaisedEvent,
  CollectorTickCompletedEvent,
  CollectorTickStartedEvent,
  ContentChangedEvent,
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
    default:
      return envelope.type;
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
