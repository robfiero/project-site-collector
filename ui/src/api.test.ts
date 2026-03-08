import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAdminEmailPreview,
  fetchAdminTrends,
  fetchDevOutbox,
  fetchEnvironment,
  fetchEvents,
  fetchHealth,
  fetchMarkets,
  fetchMe,
  fetchMetrics,
  fetchMyPreferences,
  fetchNewsSourceSettings,
  fetchSignals,
  login,
  logout,
  resetSettings,
  resetPassword,
  signup,
  triggerCollectorRefresh
} from './api';

describe('basic fetch wrappers', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetchHealth success returns parsed JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 'ok' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchHealth()).resolves.toEqual({ status: 'ok' });
  });

  it('fetchHealth non-ok throws status error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('nope', { status: 503 }));
    await expect(fetchHealth()).rejects.toThrow('Health request failed (503)');
  });

  it('fetchSignals non-ok throws status error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 500 }));
    await expect(fetchSignals()).rejects.toThrow('Signals request failed (500)');
  });

  it('fetchMetrics success returns parsed JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ sseClientsConnected: 0, eventsEmittedTotal: 1, recentEventsPerMinute: 1, collectors: {} }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    const metrics = await fetchMetrics();
    expect(metrics.eventsEmittedTotal).toBe(1);
  });
});

describe('fetchEvents', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults to limit=100', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await fetchEvents();
    expect(fetchMock).toHaveBeenCalledWith('/api/events?limit=100');
  });

  it('uses explicit limit parameter', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await fetchEvents(5);
    expect(fetchMock).toHaveBeenCalledWith('/api/events?limit=5');
  });

  it('throws on non-ok response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 502 }));
    await expect(fetchEvents(5)).rejects.toThrow('Events request failed (502)');
  });
});

describe('fetchMarkets', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('requests symbols query and returns parsed payload', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 'ok', asOf: '2026-02-25T18:00:00Z', items: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchMarkets(['AAPL', 'MSFT'])).resolves.toMatchObject({ status: 'ok', items: [] });
    expect(fetchMock).toHaveBeenCalledWith('/api/markets?symbols=AAPL%2CMSFT');
  });

  it('throws on non-ok response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 502 }));
    await expect(fetchMarkets(['AAPL'])).rejects.toThrow('Markets request failed (502)');
  });
});

describe('fetchDevOutbox', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns empty list on 404', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 404 }));
    await expect(fetchDevOutbox()).resolves.toEqual([]);
  });

  it('throws on non-404 failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 500 }));
    await expect(fetchDevOutbox()).rejects.toThrow('Dev outbox request failed (500)');
  });
});

describe('admin endpoints', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetchAdminTrends returns parsed payload', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({
        windowStart: '2026-02-25T20:00:00Z',
        bucketSeconds: 300,
        series: []
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );
    await expect(fetchAdminTrends()).resolves.toMatchObject({ bucketSeconds: 300, series: [] });
  });

  it('fetchAdminEmailPreview throws on non-ok', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 500 }));
    await expect(fetchAdminEmailPreview()).rejects.toThrow('Admin email preview request failed (500)');
  });
});

describe('triggerCollectorRefresh', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts default collectors payload', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 200 }));

    await triggerCollectorRefresh();

    expect(fetchMock).toHaveBeenCalledWith('/api/collectors/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ collectors: ['envCollector', 'rssCollector', 'localEventsCollector'] })
    });
  });

  it('does not throw on 501', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 501 }));
    await expect(triggerCollectorRefresh()).resolves.toBeUndefined();
  });

  it('throws ApiError with status on non-501 error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'bad refresh' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    try {
      await triggerCollectorRefresh();
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toBe('bad refresh');
      expect(err.status).toBe(400);
    }
  });
});

describe('resetSettings', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts scope and returns parsed payload', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({
        scopeApplied: 'collectors',
        preferences: { zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn'], themeMode: 'dark', accent: 'default' }
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(resetSettings('collectors')).resolves.toMatchObject({
      scopeApplied: 'collectors',
      preferences: { zipCodes: ['02108'] }
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/settings/reset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scope: 'collectors' })
    });
  });

  it('throws ApiError with status on failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'scope must be one of: ui, collectors, all' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    try {
      await resetSettings('ui');
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toContain('scope must be one of');
      expect(err.status).toBe(400);
    }
  });
});

describe('throwApiError/extractErrorMessage via public API', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('uses json.error and sets error.status', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'bad' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    try {
      await login('user@example.com', 'secret');
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toBe('bad');
      expect(err.status).toBe(401);
    }
  });

  it('uses json.message when json.error absent', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ message: 'nope' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    try {
      await signup('user@example.com', 'secret');
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toBe('nope');
      expect(err.status).toBe(400);
    }
  });

  it('falls back to response text when json parse fails', async () => {
    const response = {
      ok: false,
      status: 418,
      clone: () => ({
        json: async () => {
          throw new Error('bad json');
        }
      }),
      text: async () => 'teapot text'
    } as unknown as Response;

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response);

    try {
      await logout();
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toBe('teapot text');
      expect(err.status).toBe(418);
    }
  });

  it('falls back to Request failed when json and text fail', async () => {
    const response = {
      ok: false,
      status: 503,
      clone: () => ({
        json: async () => {
          throw new Error('bad json');
        }
      }),
      text: async () => {
        throw new Error('bad text');
      }
    } as unknown as Response;

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response);

    try {
      await fetchMyPreferences();
      throw new Error('expected throw');
    } catch (error) {
      const err = error as Error & { status?: number };
      expect(err.message).toBe('Request failed (503)');
      expect(err.status).toBe(503);
    }
  });

  it('saveNewsSourceSettings uses extract message path', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'settings failed' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchNewsSourceSettings()).rejects.toMatchObject({ message: 'settings failed', status: 400 });
  });

  it('resetPassword uses fallback error extraction', async () => {
    const response = {
      ok: false,
      status: 422,
      clone: () => ({
        json: async () => ({})
      }),
      text: async () => ''
    } as unknown as Response;
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response);

    await expect(resetPassword('token', 'pw')).rejects.toMatchObject({ message: 'Request failed (422)', status: 422 });
  });
});

describe('fetchMe', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns null on 401', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 401 }));
    await expect(fetchMe()).resolves.toBeNull();
  });

  it('treats 404 auth_disabled as anonymous mode', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'auth_disabled' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchMe()).resolves.toBeNull();
  });

  it('throws when 404 body is invalid json', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response('not json', {
        status: 404,
        headers: { 'Content-Type': 'text/plain' }
      })
    );

    await expect(fetchMe()).rejects.toThrow('Current user request failed (404)');
  });

  it('throws for server errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 500 }));
    await expect(fetchMe()).rejects.toThrow('Current user request failed (500)');
  });

  it('returns parsed user when ok', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ id: 'u1', email: 'u@example.com' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchMe()).resolves.toEqual({ id: 'u1', email: 'u@example.com' });
  });
});

describe('fetchEnvironment', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('calls /api/env with no query when zips is empty', async () => {
    const payload = [{
      zip: '02108',
      locationLabel: 'Boston, MA (02108)',
      lat: 42.35,
      lon: -71.06,
      weather: { temperatureF: 70, forecast: 'Clear', windSpeed: '5 mph', observedAt: '2026-02-19T10:00:00Z' },
      aqi: { aqi: 42, category: 'Good', observedAt: '2026-02-19T10:00:00Z', message: null },
      updatedAt: '2026-02-19T10:00:00Z'
    }];
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify(payload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchEnvironment([])).resolves.toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/env');
  });

  it('throws clear error when single-zip request fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('bad', { status: 502 }));
    await expect(fetchEnvironment(['02108'])).rejects.toThrow('Environment request failed (502)');
  });

  it('returns fulfilled per-zip payloads when batch request fails', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/env?zips=02108%2C98101') {
        return new Response('bad', { status: 500 });
      }
      if (url === '/api/env?zips=02108') {
        return new Response(JSON.stringify([{
          zip: '02108',
          locationLabel: 'Boston, MA (02108)',
          lat: 42.35,
          lon: -71.06,
          weather: { temperatureF: 70, forecast: 'Clear', windSpeed: '5 mph', observedAt: '2026-02-19T10:00:00Z' },
          aqi: { aqi: 42, category: 'Good', observedAt: '2026-02-19T10:00:00Z', message: null },
          updatedAt: '2026-02-19T10:00:00Z'
        }]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      if (url === '/api/env?zips=98101') {
        return new Response('bad', { status: 502 });
      }
      throw new Error(`unexpected url ${url}`);
    });

    const statuses = await fetchEnvironment(['02108', '98101']);
    expect(statuses).toHaveLength(1);
    expect(statuses[0].zip).toBe('02108');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('throws if all per-zip fallback requests fail', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/env?zips=02108%2C98101') {
        return new Response('bad', { status: 500 });
      }
      if (url === '/api/env?zips=02108' || url === '/api/env?zips=98101') {
        return new Response('bad', { status: 503 });
      }
      throw new Error(`unexpected url ${url}`);
    });

    await expect(fetchEnvironment(['02108', '98101'])).rejects.toThrow('Environment request failed (500)');
  });

  it('uses encoded query for batch and per-zip fallback', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/env?zips=02108%2C98%2F101') {
        return new Response('bad', { status: 500 });
      }
      if (url === '/api/env?zips=02108') {
        return new Response(JSON.stringify([{
          zip: '02108',
          locationLabel: 'Boston, MA (02108)',
          lat: 42.35,
          lon: -71.06,
          weather: { temperatureF: 70, forecast: 'Clear', windSpeed: '5 mph', observedAt: '2026-02-19T10:00:00Z' },
          aqi: { aqi: 42, category: 'Good', observedAt: '2026-02-19T10:00:00Z', message: null },
          updatedAt: '2026-02-19T10:00:00Z'
        }]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      if (url === '/api/env?zips=98%2F101') {
        return new Response('bad', { status: 503 });
      }
      throw new Error(`unexpected url ${url}`);
    });

    const results = await fetchEnvironment(['02108', '98/101']);
    expect(results).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/env?zips=02108%2C98%2F101');
    expect(fetchMock).toHaveBeenCalledWith('/api/env?zips=98%2F101');
  });
});
