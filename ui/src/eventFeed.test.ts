import { describe, expect, it } from 'vitest';
import { filterEvents, normalizeEventEnvelope, summarizeEvent } from './eventFeed';
import type { EventEnvelope } from './models';

describe('normalizeEventEnvelope', () => {
  it('parses wrapped SSE payload format', () => {
    const wrapped = {
      type: 'WeatherUpdated',
      timestamp: 1700000000.25,
      event: {
        type: 'WeatherUpdated',
        timestamp: 1700000000.25,
        location: 'Boston',
        tempF: 37.5,
        conditions: 'Cloudy'
      }
    };
    const result = normalizeEventEnvelope(wrapped);
    expect(result).toEqual({
      type: 'WeatherUpdated',
      timestamp: 1700000000.25,
      event: wrapped.event
    });
  });

  it('parses raw payload and string timestamp', () => {
    const raw = {
      type: 'AlertRaised',
      timestamp: '1700000001.5',
      category: 'collector',
      message: 'timeout'
    };
    const result = normalizeEventEnvelope(raw);
    expect(result?.type).toBe('AlertRaised');
    expect(result?.timestamp).toBe(1700000001.5);
    expect(result?.event.message).toBe('timeout');
  });
});

describe('summarizeEvent', () => {
  it('summarizes collector ticks', () => {
    const started: EventEnvelope = {
      type: 'CollectorTickStarted',
      timestamp: 1700000000,
      event: { collectorName: 'siteCollector' }
    };
    const completed: EventEnvelope = {
      type: 'CollectorTickCompleted',
      timestamp: 1700000001,
      event: { collectorName: 'siteCollector', success: true, durationMillis: 1234 }
    };
    expect(summarizeEvent(started)).toBe('collector siteCollector started');
    expect(summarizeEvent(completed)).toBe('collector siteCollector ok in 1234ms');
  });

  it('summarizes alerts, site fetch, weather, and env updates', () => {
    const alert: EventEnvelope = {
      type: 'AlertRaised',
      timestamp: 1700000000,
      event: { category: 'collector', message: 'dns failure' }
    };
    const siteFetched: EventEnvelope = {
      type: 'SiteFetched',
      timestamp: 1700000000,
      event: { siteId: 'docs', status: 200, durationMillis: 55 }
    };
    const weather: EventEnvelope = {
      type: 'WeatherUpdated',
      timestamp: 1700000000,
      event: { location: 'Boston', tempF: 37.4, conditions: 'Cloudy' }
    };
    const envWeather: EventEnvelope = {
      type: 'EnvWeatherUpdated',
      timestamp: 1700000000,
      event: {
        zip: '02108',
        lat: 42.35,
        lon: -71.06,
        tempF: 37.4,
        conditions: 'Cloudy',
        source: 'NOAA',
        fetchedAtEpochMillis: 1700000000000,
        status: 'OK',
        error: null,
        requestUrl: 'https://api.weather.gov/gridpoints/BOX/70,76/forecast?units=us',
        observationTime: '2026-02-18T10:00:00Z'
      }
    };
    const envAqi: EventEnvelope = {
      type: 'EnvAqiUpdated',
      timestamp: 1700000000,
      event: {
        zip: '02108',
        lat: 42.35,
        lon: -71.06,
        aqi: 42,
        category: 'Good',
        message: null,
        source: 'airnow',
        fetchedAtEpochMillis: 1700000000000,
        status: 'OK',
        error: null,
        requestUrl: 'https://www.airnowapi.org/aq/observation/zipCode/current/?zipCode=02108&distance=25',
        validDateTime: '2026-02-18 10:00'
      }
    };
    expect(summarizeEvent(alert)).toBe('collector: dns failure');
    expect(summarizeEvent(siteFetched)).toBe('docs: status 200, 55ms');
    expect(summarizeEvent(weather)).toBe('Boston: 37.4F, Cloudy');
    expect(summarizeEvent(envWeather)).toContain('02108: 37.4F, Cloudy');
    expect(summarizeEvent(envWeather)).toContain('(NOAA)');
    expect(summarizeEvent(envWeather)).toContain('[api.weather.gov/gridpoints/BOX/70,76/forecast]');
    expect(summarizeEvent(envAqi)).toContain('02108: AQI 42 (Good)');
    expect(summarizeEvent(envAqi)).toContain('via AIRNOW');
    expect(summarizeEvent(envAqi)).toContain('[www.airnowapi.org/aq/observation/zipCode/current/]');
  });
});

describe('filterEvents', () => {
  const events: EventEnvelope[] = [
    { type: 'AlertRaised', timestamp: 1, event: { category: 'collector', message: 'dns failure' } },
    { type: 'SiteFetched', timestamp: 2, event: { siteId: 'docs', status: 200 } },
    { type: 'WeatherUpdated', timestamp: 3, event: { location: 'Boston', conditions: 'Cloudy' } }
  ];

  it('filters by type and keeps newest-first order', () => {
    const filtered = filterEvents(events, 'SiteFetched', '');
    expect(filtered).toHaveLength(1);
    expect(filtered[0].type).toBe('SiteFetched');
  });

  it('searches across type and payload JSON', () => {
    expect(filterEvents(events, 'ALL', 'weatherupdated')).toHaveLength(1);
    const byPayload = filterEvents(events, 'ALL', 'dns failure');
    expect(byPayload).toHaveLength(1);
    expect(byPayload[0].type).toBe('AlertRaised');
  });
});
