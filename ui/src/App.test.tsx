import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

vi.mock('./api', () => ({
  fetchHealth: vi.fn(async () => ({ status: 'ok' })),
  fetchSignals: vi.fn(async () => ({ sites: {}, news: {}, weather: {} })),
  fetchEvents: vi.fn(async () => ([])),
  fetchMetrics: vi.fn(async () => ({ sseClientsConnected: 0, eventsEmittedTotal: 0, recentEventsPerMinute: 0, collectors: {} })),
  fetchCollectorStatus: vi.fn(async () => ({})),
  fetchCatalogDefaults: vi.fn(async () => ({ defaultZipCodes: ['02108'], defaultNewsSources: [], defaultWatchlist: ['AAPL'] })),
  fetchConfigView: vi.fn(async () => ({ collectors: [] })),
  fetchNewsSourceSettings: vi.fn(async () => ({
    availableSources: [
      { id: 'cnn', name: 'CNN', type: 'rss', url: 'https://example.com/cnn' },
      { id: 'wsj', name: 'WSJ', type: 'rss', url: 'https://example.com/wsj' }
    ],
    effectiveSelectedSources: ['cnn', 'wsj']
  })),
  fetchEnvironment: vi.fn(async () => ([])),
  fetchMe: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  fetchMyPreferences: vi.fn(async () => ({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: [] })),
  saveMyPreferences: vi.fn(async (payload) => payload),
  fetchDevOutbox: vi.fn(async () => []),
  login: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  signup: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  logout: vi.fn(async () => {}),
  triggerCollectorRefresh: vi.fn(async () => {}),
  forgotPassword: vi.fn(async () => {}),
  resetPassword: vi.fn(async () => {})
}));

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: (() => void) | null = null;
  private readonly listeners = new Map<string, EventListener[]>();

  constructor(_url: string) {
    FakeEventSource.instances.push(this);
    setTimeout(() => this.onopen?.(), 0);
  }

  addEventListener(type: string, listener: EventListener): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    // noop
  }

  emit(type: string, payload: unknown): void {
    const data = JSON.stringify(payload);
    const event = new MessageEvent<string>('message', { data });
    (this.listeners.get(type) ?? []).forEach((listener) => listener(event));
    this.onmessage?.(event);
  }

  static emitAll(type: string, payload: unknown): void {
    FakeEventSource.instances.forEach((instance) => instance.emit(type, payload));
  }
}

describe('App', () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    const mockLocalStorage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
      clear: () => store.clear()
    };
    Object.defineProperty(globalThis, 'localStorage', { value: mockLocalStorage, configurable: true });
    localStorage.clear();
    sessionStorage.clear();
    window.location.hash = '#/';
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    cleanup();
    FakeEventSource.instances = [];
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders settings route with places/watchlist controls and formatted place labels', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108', '98101'],
      watchlist: ['AAPL'],
      newsSourceIds: []
    });
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['02108', '98101']));
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify(['AAPL']));
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: /places/i })).toBeTruthy());
    expect(screen.getByRole('heading', { name: /watchlist/i })).toBeTruthy();
    expect(screen.getByRole('heading', { name: /news sources/i })).toBeTruthy();
    expect((screen.getByLabelText(/CNN/i) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByText('Boston, MA (02108)')).toBeTruthy();
    expect(screen.getByText('Seattle, WA (98101)')).toBeTruthy();
    expect(screen.getByText(/AAPL/)).toBeTruthy();
  });

  it('adds ZIP and symbol using Enter key in settings', async () => {
    window.location.hash = '#/settings';
    render(<App />);

    const zipInput = await screen.findByPlaceholderText('ZIP (e.g., 02108)');
    fireEvent.change(zipInput, { target: { value: '60601' } });
    fireEvent.keyDown(zipInput, { key: 'Enter', code: 'Enter', charCode: 13 });
    fireEvent.submit(zipInput.closest('form') as HTMLFormElement);

    const symbolInput = screen.getByPlaceholderText('Symbol (e.g., NVDA)');
    fireEvent.change(symbolInput, { target: { value: 'tsla' } });
    fireEvent.keyDown(symbolInput, { key: 'Enter', code: 'Enter', charCode: 13 });
    fireEvent.submit(symbolInput.closest('form') as HTMLFormElement);

    expect(screen.getByText('ZIP 60601')).toBeTruthy();
    expect(screen.getByText(/TSLA/)).toBeTruthy();
  });

  it('shows inline validation and disables Add buttons for invalid settings input', async () => {
    window.location.hash = '#/settings';
    render(<App />);

    const addZipButton = await screen.findByRole('button', { name: 'Add ZIP' });
    const zipInput = screen.getByPlaceholderText('ZIP (e.g., 02108)');
    fireEvent.change(zipInput, { target: { value: '12ab' } });
    expect(addZipButton.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('Enter a 5-digit ZIP')).toBeTruthy();

    const addSymbolButton = screen.getByRole('button', { name: 'Add Symbol' });
    const symbolInput = screen.getByPlaceholderText('Symbol (e.g., NVDA)');
    fireEvent.change(symbolInput, { target: { value: '' } });
    expect(addSymbolButton.hasAttribute('disabled')).toBe(true);
    fireEvent.submit(symbolInput.closest('form') as HTMLFormElement);
    expect(screen.getByText('Enter a symbol (e.g., NVDA)')).toBeTruthy();
  });

  it('prevents duplicates and can reset settings to defaults', async () => {
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['02108']));
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify(['AAPL']));
    window.location.hash = '#/settings';
    render(<App />);

    const zipInput = await screen.findByPlaceholderText('ZIP (e.g., 02108)');
    fireEvent.change(zipInput, { target: { value: '02108' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add ZIP' }));
    expect(screen.getAllByText('Boston, MA (02108)').length).toBe(1);

    const symbolInput = screen.getByPlaceholderText('Symbol (e.g., NVDA)');
    fireEvent.change(symbolInput, { target: { value: 'aapl' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add Symbol' }));
    expect(screen.getAllByText(/AAPL/).length).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Reset to defaults' }));
    expect(window.confirm).toHaveBeenCalled();
    expect(screen.getByText('Reset complete')).toBeTruthy();
    expect(screen.getByText('Boston, MA (02108)')).toBeTruthy();
    expect(screen.getByText(/AAPL/)).toBeTruthy();
    await waitFor(() => expect(localStorage.getItem('signal-sentinel:zip-codes')).toContain('02108'));
    await waitFor(() => expect(localStorage.getItem('signal-sentinel:watchlist')).toContain('AAPL'));
  });

  it('restores defaults per card without resetting the other section', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['60601'],
      watchlist: ['TSLA'],
      newsSourceIds: []
    });
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['60601']));
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify(['TSLA']));
    window.location.hash = '#/settings';
    render(<App />);

    await waitFor(() => expect(screen.getByText('ZIP 60601')).toBeTruthy());
    fireEvent.click(screen.getAllByRole('button', { name: 'Restore defaults' })[0]);
    expect(screen.getByText('Boston, MA (02108)')).toBeTruthy();
    expect(screen.getByText(/TSLA/)).toBeTruthy();

    fireEvent.click(screen.getAllByRole('button', { name: 'Restore defaults' })[1]);
    expect(screen.getByText(/AAPL/)).toBeTruthy();
  });

  it('forces refresh when navigating from settings back to home', async () => {
    const api = await import('./api');
    window.location.hash = '#/settings';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: /places/i })).toBeTruthy());
    fireEvent.click(screen.getByRole('link', { name: 'Home' }));

    await waitFor(() => expect(api.triggerCollectorRefresh).toHaveBeenCalled());
  });

  it('renders home useful panels without settings controls and shows environment rows', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: [],
      newsSourceIds: []
    });
    vi.mocked(api.fetchEnvironment).mockResolvedValueOnce([]);
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['02108']));
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify([]));

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Environment' })).toBeTruthy());
    expect(screen.queryByRole('heading', { name: 'Places' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Markets Watchlist' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Air Quality (AQI)' })).toBeNull();
    expect(screen.getByText('Powered by Ticketmaster')).toBeTruthy();
    expect(screen.getAllByText('Boston, MA (02108)').length).toBeGreaterThan(0);
    expect(screen.getByText('AQI unavailable')).toBeTruthy();
    expect(screen.getByText('Waiting for first weather update for this ZIP.')).toBeTruthy();

    FakeEventSource.emitAll('EnvWeatherUpdated', {
      type: 'EnvWeatherUpdated',
      timestamp: 1771105748.313279,
      event: {
        type: 'EnvWeatherUpdated',
        timestamp: 1771105748.313279,
        zip: '02108',
        tempF: 72.5,
        conditions: 'Clear'
      }
    });
    FakeEventSource.emitAll('EnvAqiUpdated', {
      type: 'EnvAqiUpdated',
      timestamp: 1771105748.313279,
      event: {
        type: 'EnvAqiUpdated',
        timestamp: 1771105748.313279,
        zip: '02108',
        aqi: 42,
        category: 'Good',
        message: null
      }
    });

    await waitFor(() => expect(screen.getByText(/72\.5 F, Clear/)).toBeTruthy());
    await waitFor(() => expect(screen.getByText((_, node) => node?.textContent === 'AQI 42 - Good')).toBeTruthy());
    expect(screen.queryByText(/1970/)).toBeNull();
    expect(screen.getByText('No symbols in watchlist.')).toBeTruthy();
  });

  it('deduplicates local happenings by title and hides items without links', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchSignals).mockResolvedValueOnce({
      sites: {},
      news: {},
      weather: {},
      localHappenings: {
        '02108': {
          location: '02108',
          sourceAttribution: 'Powered by Ticketmaster',
          updatedAt: '2026-02-25T12:00:00Z',
          items: [
            {
              id: 'evt-1',
              name: 'Jazz Night',
              startDateTime: '2026-03-10T20:00:00Z',
              venueName: 'Venue A',
              city: 'Boston',
              state: 'MA',
              url: 'https://example.com/jazz-night',
              category: 'music',
              source: 'ticketmaster'
            },
            {
              id: 'evt-2',
              name: 'Jazz Night',
              startDateTime: '2026-03-11T20:00:00Z',
              venueName: 'Venue B',
              city: 'Boston',
              state: 'MA',
              url: 'https://example.com/jazz-night-duplicate',
              category: 'music',
              source: 'ticketmaster'
            },
            {
              id: 'evt-3',
              name: 'No Link Event',
              startDateTime: '2026-03-09T20:00:00Z',
              venueName: 'Venue C',
              city: 'Boston',
              state: 'MA',
              url: '',
              category: 'arts',
              source: 'ticketmaster'
            },
            {
              id: 'evt-4',
              name: 'Comedy Show',
              startDateTime: '2026-03-12T20:00:00Z',
              venueName: 'Venue D',
              city: 'Boston',
              state: 'MA',
              url: 'https://example.com/comedy-show',
              category: 'arts',
              source: 'ticketmaster'
            }
          ]
        }
      }
    });

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    const jazzLink = screen.getByRole('link', { name: 'Jazz Night' });
    expect(jazzLink.getAttribute('href')).toBe('https://example.com/jazz-night');
    expect(screen.getAllByRole('link', { name: 'Jazz Night' })).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Comedy Show' })).toBeTruthy();
    expect(screen.queryByText('No Link Event')).toBeNull();
  });

  it('formats numeric env updatedAt as epoch-seconds, not 1970', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: [],
      newsSourceIds: []
    });
    vi.mocked(api.fetchEnvironment).mockResolvedValueOnce([
      {
        zip: '02108',
        lat: 42.35,
        lon: -71.06,
        weather: {
          temperatureF: 70,
          forecast: 'Clear',
          windSpeed: '5 mph',
          observedAt: '2026-02-18T12:00:00Z'
        },
        aqi: {
          aqi: 42,
          category: 'Good',
          observedAt: '2026-02-18T12:00:00Z',
          message: null
        },
        updatedAt: 1771105748.313279 as unknown as string
      }
    ]);

    render(<App />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Environment' })).toBeTruthy());
    expect(screen.queryByText(/1970/)).toBeNull();
  });

  it('shows backend ZIP resolution guidance when weather cannot resolve location', async () => {
    const api = await import('./api');
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['53201']));
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['53201'],
      watchlist: [],
      newsSourceIds: []
    });
    vi.mocked(api.fetchEnvironment).mockResolvedValue([
      {
        zip: '53201',
        locationLabel: 'ZIP 53201',
        lat: Number.NaN,
        lon: Number.NaN,
        weather: {
          temperatureF: null,
          forecast: 'Unable to resolve ZIP to location. Try a nearby ZIP code.',
          windSpeed: '',
          observedAt: '2026-02-19T10:00:00Z'
        },
        aqi: {
          aqi: null,
          category: null,
          observedAt: '2026-02-19T10:00:00Z',
          message: 'Unable to resolve ZIP to location. Try a nearby ZIP code.'
        },
        updatedAt: '2026-02-19T10:00:00Z'
      }
    ]);

    render(<App />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Environment' })).toBeTruthy());
    expect(screen.getAllByText('Unable to resolve ZIP to location. Try a nearby ZIP code.').length).toBeGreaterThan(0);
  });

  it('renders envelope-format events in admin feed', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchEvents).mockResolvedValueOnce([
      {
        type: 'CollectorTickStarted',
        timestamp: 1700000000,
        event: {
          type: 'CollectorTickStarted',
          timestamp: 1700000000,
          collectorName: 'siteCollector'
        }
      }
    ]);
    window.location.hash = '#/admin';

    render(<App />);

    await waitFor(() => expect(screen.getByText(/collector siteCollector started/i)).toBeTruthy());
  });

  it('renders admin key ops panels', async () => {
    window.location.hash = '#/admin';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Collectors' })).toBeTruthy());
    expect(screen.getByRole('heading', { name: 'Runtime / Config' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Live Activity' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Sites (diagnostic)' })).toBeTruthy();
    const sitesHeading = screen.getByRole('heading', { name: 'Sites (diagnostic)' });
    const eventsHeading = screen.getByRole('heading', { name: 'Live Activity' });
    expect(sitesHeading.compareDocumentPosition(eventsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('hides normal header status and shows warning status when degraded', async () => {
    window.location.hash = '#/';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    expect(screen.queryByText('Backend health: degraded')).toBeNull();
    expect(screen.queryByText('Live updates: disconnected')).toBeNull();

    cleanup();
    const api = await import('./api');
    vi.mocked(api.fetchHealth).mockResolvedValueOnce({ status: 'degraded' });
    render(<App />);

    await waitFor(() => expect(screen.getByText('Backend health: degraded')).toBeTruthy());
  });

  it('shows collector failure indicator and can expand event payload details', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchCollectorStatus).mockResolvedValueOnce({
      siteCollector: {
        lastRunAt: '2026-02-16T00:00:00Z',
        lastDurationMillis: 150,
        lastSuccess: false,
        lastErrorMessage: 'HTTP 500'
      }
    });
    vi.mocked(api.fetchEvents).mockResolvedValueOnce([
      { type: 'AlertRaised', timestamp: 1700000000, event: { category: 'collector', message: 'dns issue' } }
    ]);
    window.location.hash = '#/admin';

    render(<App />);
    await waitFor(() => expect(screen.getByText('Failed')).toBeTruthy());
    expect(screen.getByText('collector: dns issue')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/"message": "dns issue"/)).toBeTruthy();
  });

  it('pause toggle stops appending live events until resumed', async () => {
    window.location.hash = '#/admin';
    render(<App />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pause' })).toBeTruthy());

    FakeEventSource.emitAll('AlertRaised', {
      type: 'AlertRaised',
      timestamp: 1700000100,
      event: { category: 'collector', message: 'first-event' }
    });
    await waitFor(() => expect(screen.getAllByText('collector: first-event').length).toBeGreaterThan(0));

    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
    FakeEventSource.emitAll('AlertRaised', {
      type: 'AlertRaised',
      timestamp: 1700000200,
      event: { category: 'collector', message: 'second-event' }
    });
    expect(screen.queryByText('collector: second-event')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(screen.getAllByText('collector: second-event').length).toBeGreaterThan(0));
  });

  it('filters event feed by type and search text', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchEvents).mockResolvedValueOnce([
      { type: 'AlertRaised', timestamp: 1700000000, event: { category: 'collector', message: 'dns issue' } },
      { type: 'NewsUpdated', timestamp: 1700000001, event: { source: 'feed', storyCount: 2 } }
    ]);
    window.location.hash = '#/admin';

    render(<App />);
    await waitFor(() => expect(screen.getByText(/dns issue/i)).toBeTruthy());

    fireEvent.change(screen.getAllByDisplayValue('All types')[0], { target: { value: 'AlertRaised' } });
    expect(screen.getByText(/dns issue/i)).toBeTruthy();
    expect(screen.queryByText(/feed: 2 stories/i)).toBeNull();

    fireEvent.change(screen.getByPlaceholderText('Search events'), { target: { value: 'dns' } });
    expect(screen.getByText(/dns issue/i)).toBeTruthy();
  });

  it('hides settings nav for anonymous users and shows login/signup links', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    expect(screen.queryByRole('link', { name: /settings/i })).toBeNull();
    expect(screen.getByRole('link', { name: 'Login' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Sign up' })).toBeTruthy();
  });

  it('keeps healthy anonymous UI when auth is disabled server-side', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    vi.mocked(api.fetchHealth).mockResolvedValueOnce({ status: 'ok' });
    window.location.hash = '#/';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    expect(screen.queryByText('Backend health: degraded')).toBeNull();
    expect(screen.getByRole('link', { name: 'Login' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: /Environment/i })).toBeTruthy();
  });

  it('login flow shows settings nav after successful sign-in', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/login';

    render(<App />);

    const emailInput = await screen.findByLabelText('Email');
    fireEvent.change(emailInput, { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'hunter2-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));

    await waitFor(() => expect(api.login).toHaveBeenCalledWith('user@example.com', 'hunter2-password'));
    await waitFor(() => expect(screen.getByRole('link', { name: /settings/i })).toBeTruthy());
  });

  it('signup enforces min password length and surfaces backend 400 error message', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    const badRequest = Object.assign(new Error('Password must be at least 8 characters'), { status: 400 });
    vi.mocked(api.signup).mockRejectedValueOnce(badRequest);
    window.location.hash = '#/signup';

    render(<App />);

    const passwordInput = await screen.findByPlaceholderText('********');
    const submitButton = screen.getByRole('button', { name: 'Create account' });
    expect(screen.getByText('Password must be at least 8 characters.')).toBeTruthy();

    fireEvent.change(passwordInput, { target: { value: 'short' } });
    expect(submitButton.hasAttribute('disabled')).toBe(true);
    expect(screen.getAllByText('Password must be at least 8 characters.')).toHaveLength(1);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } });
    fireEvent.change(passwordInput, { target: { value: 'long-enough-password' } });
    expect(submitButton.hasAttribute('disabled')).toBe(false);
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.signup).toHaveBeenCalledWith('user@example.com', 'long-enough-password'));
    await waitFor(() => expect(screen.getByText('Password must be at least 8 characters')).toBeTruthy());
  });

  it('redirects back to intended protected route after login', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Login' })).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'hunter2-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));

    await waitFor(() => expect(api.login).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('heading', { name: /Places/i })).toBeTruthy());
  });

  it('reset flow submits token and password from reset route', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/reset?token=test-reset-token';

    render(<App />);

    const passwordInput = await screen.findByLabelText('Password');
    fireEvent.change(passwordInput, { target: { value: 'new-password-123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reset password' }));

    await waitFor(() => expect(api.resetPassword).toHaveBeenCalledWith('test-reset-token', 'new-password-123'));
  });

  it('loads and saves settings preferences for logged-in users', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: ['AAPL'],
      newsSourceIds: []
    });
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByText('Boston, MA (02108)')).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText('ZIP (e.g., 02108)'), { target: { value: '98101' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add ZIP' }));

    await waitFor(() =>
      expect(api.saveMyPreferences).toHaveBeenLastCalledWith({
        zipCodes: ['02108', '98101'],
        watchlist: ['AAPL'],
        newsSourceIds: ['cnn', 'wsj']
      })
    );
    await waitFor(() => expect(screen.getByText('Saved ✅')).toBeTruthy());
  });

  it('handles 401 from authenticated preference save by expiring session', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: ['AAPL'],
      newsSourceIds: []
    });
    vi.mocked(api.saveMyPreferences)
      .mockResolvedValueOnce({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn', 'wsj'] })
      .mockResolvedValueOnce({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn', 'wsj'] })
      .mockRejectedValueOnce(new Error('Preferences update failed (401)'));
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByText('Boston, MA (02108)')).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText('ZIP (e.g., 02108)'), { target: { value: '98101' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add ZIP' }));

    await waitFor(() => expect(screen.getByText('Session expired — please sign in again.')).toBeTruthy());
    await waitFor(() => expect(screen.queryByRole('link', { name: /settings/i })).toBeNull());
    expect(screen.queryByText('Backend health: degraded')).toBeNull();
  });
});
