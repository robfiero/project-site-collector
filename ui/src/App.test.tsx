import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { formatPlaceLabel } from './places';

vi.mock('./api', () => ({
  fetchHealth: vi.fn(async () => ({ status: 'ok', version: '0.1.0', buildTime: '2026-03-08', gitSha: 'abc123' })),
  fetchSignals: vi.fn(async () => ({ sites: {}, news: {}, weather: {} })),
  fetchEvents: vi.fn(async () => ([])),
  fetchMetrics: vi.fn(async () => ({ sseClientsConnected: 0, eventsEmittedTotal: 0, recentEventsPerMinute: 0, collectors: {} })),
  fetchCollectorStatus: vi.fn(async () => ({})),
  fetchCatalogDefaults: vi.fn(async () => ({ defaultZipCodes: ['02108'], defaultNewsSources: [], defaultWatchlist: ['AAPL'] })),
  fetchConfigView: vi.fn(async () => ({ collectors: [] })),
  fetchAdminTrends: vi.fn(async () => ({
    asOf: '2026-02-25T20:00:00Z',
    windowStart: '2026-02-25T19:55:00Z',
    bucketSeconds: 300,
    series: []
  })),
  fetchAdminEmailPreview: vi.fn(async () => ({
    enabled: true,
    mode: 'dev_outbox',
    lastSentAt: '',
    lastError: '',
    generatedAt: '2026-02-25T20:00:00Z',
    subject: "Today's Overview Digest Preview - 2026-02-25",
    body: '',
    includedCounts: { sites: 0, newsStories: 0, localEvents: 0, weather: 0, markets: 0 }
  })),
  fetchNewsSourceSettings: vi.fn(async () => ({
    availableSources: [
      { id: 'cnn', name: 'CNN', type: 'rss', url: 'https://www.cnn.com/rss' },
      { id: 'wsj', name: 'WSJ', type: 'rss', url: 'https://www.wsj.com/news' }
    ],
    effectiveSelectedSources: ['cnn', 'wsj']
  })),
  fetchEnvironment: vi.fn(async () => ([])),
  fetchMarkets: vi.fn(async () => ({ status: 'ok', asOf: '2026-02-25T18:00:00Z', items: [] })),
  fetchMe: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  fetchMyPreferences: vi.fn(async () => ({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: [], themeMode: 'light', accent: 'blue' })),
  saveMyPreferences: vi.fn(async (payload) => payload),
  fetchDevOutbox: vi.fn(async () => []),
  login: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  signup: vi.fn(async () => ({ id: 'u-1', email: 'user@example.com' })),
  logout: vi.fn(async () => {}),
  deleteMyAccount: vi.fn(async () => {}),
  resetSettings: vi.fn(async (scope: 'ui' | 'collectors' | 'all') => ({
    scopeApplied: scope,
    preferences: { zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn', 'wsj'], themeMode: 'light', accent: 'blue' }
  })),
  triggerCollectorRefresh: vi.fn(async () => {}),
  forgotPassword: vi.fn(async () => {}),
  resetPassword: vi.fn(async () => {}),
  setUnauthorizedHandler: vi.fn()
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
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
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
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

    const zipInput = await screen.findByPlaceholderText('ZIPs (e.g., 02108, 98101)');
    fireEvent.change(zipInput, { target: { value: '60601, 98101' } });
    fireEvent.keyDown(zipInput, { key: 'Enter', code: 'Enter', charCode: 13 });
    fireEvent.submit(zipInput.closest('form') as HTMLFormElement);

    const symbolInput = screen.getByPlaceholderText('Symbols (e.g., AAPL, MSFT, BTC-USD)');
    fireEvent.change(symbolInput, { target: { value: 'tsla, msft' } });
    fireEvent.keyDown(symbolInput, { key: 'Enter', code: 'Enter', charCode: 13 });
    fireEvent.submit(symbolInput.closest('form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByText(formatPlaceLabel('60601'))).toBeTruthy());
    await waitFor(() => expect(screen.getByText(formatPlaceLabel('98101'))).toBeTruthy());
    await waitFor(() => expect(screen.getByText(/TSLA/)).toBeTruthy());
    await waitFor(() => expect(screen.getByText(/MSFT/)).toBeTruthy());
  });

  it('bulk adds ZIPs and symbols with invalid entries filtered', async () => {
    window.location.hash = '#/settings';
    render(<App />);

    const zipInput = await screen.findByPlaceholderText('ZIPs (e.g., 02108, 98101)');
    fireEvent.change(zipInput, { target: { value: '02108, 1234, 98101, 02108' } });
    fireEvent.submit(zipInput.closest('form') as HTMLFormElement);

    const symbolInput = screen.getByPlaceholderText('Symbols (e.g., AAPL, MSFT, BTC-USD)');
    fireEvent.change(symbolInput, { target: { value: 'aapl, msft, $bad, btc-usd, aapl' } });
    fireEvent.submit(symbolInput.closest('form') as HTMLFormElement);

    expect(screen.getByText(formatPlaceLabel('02108'))).toBeTruthy();
    expect(screen.getByText(formatPlaceLabel('98101'))).toBeTruthy();
    expect(screen.queryByText('ZIP 1234')).toBeNull();
    expect(screen.getByText(/AAPL/)).toBeTruthy();
    expect(screen.getByText(/MSFT/)).toBeTruthy();
    expect(screen.getByText(/BTC-USD/)).toBeTruthy();
  });

  it('shows inline validation and disables Add buttons for invalid settings input', async () => {
    window.location.hash = '#/settings';
    render(<App />);

    const addZipButton = await screen.findByRole('button', { name: 'Add ZIPs' });
    const zipInput = screen.getByPlaceholderText('ZIPs (e.g., 02108, 98101)');
    fireEvent.change(zipInput, { target: { value: '12ab' } });
    expect(addZipButton.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('Enter 5-digit ZIPs separated by commas.')).toBeTruthy();

    const addSymbolButton = screen.getByRole('button', { name: 'Add Symbols' });
    const symbolInput = screen.getByPlaceholderText('Symbols (e.g., AAPL, MSFT, BTC-USD)');
    fireEvent.change(symbolInput, { target: { value: '' } });
    expect(addSymbolButton.hasAttribute('disabled')).toBe(true);
    fireEvent.submit(symbolInput.closest('form') as HTMLFormElement);
    expect(screen.getByText('Enter comma-separated symbols.')).toBeTruthy();
  });

  it('deletes account from user menu and returns to signed-out state', async () => {
    const api = await import('./api');
    window.location.hash = '#/settings';
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Open user menu' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete account' }));

    await waitFor(() => expect(api.deleteMyAccount).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('link', { name: 'Sign In' })).toBeTruthy());
  });

  it('restores defaults per card without resetting the other section', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['60601'],
      watchlist: ['TSLA'],
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
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

  it('applies theme controls immediately and ui reset restores default theme preferences', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: ['AAPL'],
      newsSourceIds: ['cnn', 'wsj'],
      themeMode: 'light',
      accent: 'green'
    });
    vi.mocked(api.resetSettings).mockImplementation(async (scope) => ({
      scopeApplied: scope,
      preferences: {
        zipCodes: ['02108'],
        watchlist: ['AAPL'],
        newsSourceIds: ['cnn', 'wsj'],
        themeMode: 'light',
        accent: 'blue'
      }
    }));
    window.location.hash = '#/settings';
    render(<App />);

    await waitFor(() => expect(screen.getByLabelText('Theme mode')).toBeTruthy());
    const themeSelect = screen.getByLabelText('Theme mode') as HTMLSelectElement;
    const accentSelect = screen.getByLabelText('Accent') as HTMLSelectElement;
    expect(themeSelect.value).toBe('light');
    expect(accentSelect.value).toBe('green');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.dataset.accent).toBe('green');

    fireEvent.change(themeSelect, { target: { value: 'dark' } });
    fireEvent.change(accentSelect, { target: { value: 'blue' } });
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'));
    await waitFor(() => expect(document.documentElement.dataset.accent).toBe('blue'));

    fireEvent.click(screen.getAllByRole('button', { name: 'Restore defaults' })[2]);
    await waitFor(() => expect(api.resetSettings).toHaveBeenCalledWith('ui'));
    await waitFor(() => expect((screen.getByLabelText('Theme mode') as HTMLSelectElement).value).toBe('light'));
    await waitFor(() => expect((screen.getByLabelText('Accent') as HTMLSelectElement).value).toBe('blue'));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('light'));
    await waitFor(() => expect(document.documentElement.dataset.accent).toBe('blue'));
  });

  it('allows drag/drop reorder for ZIP codes and watchlist symbols in settings', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108', '98101'],
      watchlist: ['AAPL', 'MSFT'],
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
    });
    window.location.hash = '#/settings';
    render(<App />);

    await waitFor(() => expect(screen.getByText('Boston, MA (02108)')).toBeTruthy());
    const cards = Array.from(document.querySelectorAll('.card.controls'));
    const placesCard = cards[0];
    const watchlistCard = cards[1];

    const placeLabelsBefore = Array.from(placesCard.querySelectorAll('.chip-label')).map((node) => node.textContent?.trim());
    expect(placeLabelsBefore).toEqual(['Boston, MA (02108)', 'Seattle, WA (98101)']);
    const symbolLabelsBefore = Array.from(watchlistCard.querySelectorAll('.chip-label')).map((node) => node.textContent?.trim());
    expect(symbolLabelsBefore).toEqual(['AAPL - Apple Inc.', 'MSFT - Microsoft Corporation']);

    const zipChips = Array.from(placesCard.querySelectorAll('.chip-item'));
    const symbolChips = Array.from(watchlistCard.querySelectorAll('.chip-item'));
    const zipTransfer = { setData: vi.fn(), getData: vi.fn(), effectAllowed: 'move' };
    const symbolTransfer = { setData: vi.fn(), getData: vi.fn(), effectAllowed: 'move' };

    fireEvent.dragStart(zipChips[1], { dataTransfer: zipTransfer });
    fireEvent.dragOver(zipChips[0], { dataTransfer: zipTransfer });
    fireEvent.drop(zipChips[0], { dataTransfer: zipTransfer });
    fireEvent.dragEnd(zipChips[1], { dataTransfer: zipTransfer });

    fireEvent.dragStart(symbolChips[1], { dataTransfer: symbolTransfer });
    fireEvent.dragOver(symbolChips[0], { dataTransfer: symbolTransfer });
    fireEvent.drop(symbolChips[0], { dataTransfer: symbolTransfer });
    fireEvent.dragEnd(symbolChips[1], { dataTransfer: symbolTransfer });

    const placeLabelsAfter = Array.from(placesCard.querySelectorAll('.chip-label')).map((node) => node.textContent?.trim());
    expect(placeLabelsAfter).toEqual(['Seattle, WA (98101)', 'Boston, MA (02108)']);
    const symbolLabelsAfter = Array.from(watchlistCard.querySelectorAll('.chip-label')).map((node) => node.textContent?.trim());
    expect(symbolLabelsAfter).toEqual(['MSFT - Microsoft Corporation', 'AAPL - Apple Inc.']);
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
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
    });
    vi.mocked(api.fetchEnvironment).mockResolvedValueOnce([]);
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['02108']));
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify([]));

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Weather & Air Quality' })).toBeTruthy());
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
    expect(screen.getByText('No market data available right now.')).toBeTruthy();
  });

  it('shows markets loading then success with as-of timestamp', async () => {
    const api = await import('./api');
    const pending = deferred<{ status: string; asOf: string; items: Array<{ symbol: string; price: number; change: number; updatedAt: string }> }>();
    vi.mocked(api.fetchMarkets)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue({
        status: 'ok',
        asOf: '2026-02-25T18:30:00Z',
        items: [{ symbol: 'AAPL', price: 212.34, change: 1.22, updatedAt: '2026-02-25T18:29:00Z' }]
      });

    render(<App />);
    await waitFor(() => expect(document.querySelector('.skeleton-block')).toBeTruthy());

    pending.resolve({
      status: 'ok',
      asOf: '2026-02-25T18:30:00Z',
      items: [{ symbol: 'AAPL', price: 212.34, change: 1.22, updatedAt: '2026-02-25T18:29:00Z' }]
    });

    await waitFor(() => expect(screen.getByText(/AAPL/)).toBeTruthy());
    expect(screen.getByText(/\+1\.22/)).toBeTruthy();
    expect(screen.getByText(/As of/)).toBeTruthy();
  });

  it('shows skeletons during home loading and replaces them with content', async () => {
    const api = await import('./api');
    const pendingSignals = deferred<{
      sites: {};
      news: {};
      weather: {};
      localHappenings: Record<string, never>;
    }>();
    const pendingMarkets = deferred<{ status: string; asOf: string; items: Array<{ symbol: string; price: number; change: number; updatedAt: string }> }>();

    vi.mocked(api.fetchSignals).mockReturnValueOnce(pendingSignals.promise);
    vi.mocked(api.fetchMarkets)
      .mockReturnValueOnce(pendingMarkets.promise)
      .mockResolvedValue({
        status: 'ok',
        asOf: '2026-02-25T18:30:00Z',
        items: [{ symbol: 'AAPL', price: 212.34, change: 1.22, updatedAt: '2026-02-25T18:29:00Z' }]
      });

    render(<App />);

    expect(document.querySelector('.skeleton-block')).toBeTruthy();

    pendingSignals.resolve({
      sites: {},
      news: {
        cnn: {
          source: 'cnn',
          updatedAt: '2026-02-25T18:00:00Z',
          stories: [{ title: 'Market-ready story', link: 'https://example.com/story', publishedAt: '2026-02-25T17:00:00Z', source: 'cnn' }]
        }
      },
      weather: {},
      localHappenings: {}
    });
    pendingMarkets.resolve({
      status: 'ok',
      asOf: '2026-02-25T18:30:00Z',
      items: [{ symbol: 'AAPL', price: 212.34, change: 1.22, updatedAt: '2026-02-25T18:29:00Z' }]
    });

    await waitFor(() => expect(document.querySelector('.skeleton-block')).toBeNull());
    expect(screen.getByText('Market-ready story')).toBeTruthy();
    const marketLink = await screen.findByRole('link', { name: /AAPL details/i });
    expect((marketLink.getAttribute('href') ?? '').includes('AAPL')).toBe(true);
  });

  it('shows markets error state when request fails', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMarkets).mockRejectedValueOnce(new Error('Markets request failed (502)'));

    render(<App />);

    await waitFor(() => expect(screen.getByText(/Market data unavailable: Markets request failed \(502\)/)).toBeTruthy());
  });

  it('deduplicates local happenings by title and renders missing-link items as plain text', async () => {
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
    expect(screen.getByText('No Link Event')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Comedy Show' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'No Link Event' })).toBeNull();
  });

  it('formats numeric env updatedAt as epoch-seconds, not 1970', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: [],
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
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
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Weather & Air Quality' })).toBeTruthy());
    expect(screen.queryByText(/1970/)).toBeNull();
  });

  it('renders top news stories as links and handles missing URLs as text', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchSignals).mockResolvedValueOnce({
      sites: {},
      news: {
        cnn: {
          source: 'cnn',
          updatedAt: '2026-02-25T18:00:00Z',
          stories: [
            { title: 'Story with Link', link: 'https://example.com/has-link', publishedAt: '2026-02-25T17:00:00Z', source: 'cnn' },
            { title: 'Story without Link', link: '', publishedAt: '2026-02-25T17:05:00Z', source: 'cnn' }
          ]
        }
      },
      weather: {},
      localHappenings: {
        '02108': {
          location: '02108',
          sourceAttribution: 'Powered by Ticketmaster',
          updatedAt: '2026-02-25T12:00:00Z',
          items: []
        }
      }
    });

    render(<App />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'Story with Link' })).toBeTruthy());
    expect(screen.getByRole('link', { name: 'Story with Link' }).getAttribute('href')).toBe('https://example.com/has-link');
    expect(screen.getByText('Story without Link')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Story without Link' })).toBeNull();
  });

  it('uses mapped publisher domains for known news sources', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchNewsSourceSettings).mockResolvedValueOnce({
      availableSources: [{ id: 'fox', name: 'Fox News', type: 'rss', url: 'https://example.org/redirect?url=https://moxie.foxnews.com/google-publisher/latest.xml' }],
      effectiveSelectedSources: ['fox']
    });
    vi.mocked(api.fetchSignals).mockResolvedValueOnce({
      sites: {},
      weather: {},
      localHappenings: {
        '02108': {
          location: '02108',
          sourceAttribution: 'Powered by Ticketmaster',
          updatedAt: '2026-02-25T12:00:00Z',
          items: []
      }
      },
      news: {
        fox: {
          source: 'fox',
          updatedAt: '2026-02-25T18:00:00Z',
          stories: [{ title: 'Top Story', link: 'https://www.foxnews.com/article', publishedAt: '2026-02-25T17:00:00Z', source: 'fox' }]
        }
      }
    });

    render(<App />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'Fox News source icon' })).toBeTruthy());
    expect(screen.getByRole('img', { name: 'Fox News source icon' }).getAttribute('src')).toContain(
      'https://www.google.com/s2/favicons?domain=foxnews.com&sz=32'
    );
  });

  it('falls back to the generic source icon when favicon fails to load', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchNewsSourceSettings).mockResolvedValueOnce({
      availableSources: [{ id: 'cnn', name: 'CNN', type: 'rss', url: 'https://www.cnn.com/rss' }],
      effectiveSelectedSources: ['cnn']
    });
    vi.mocked(api.fetchSignals).mockResolvedValueOnce({
      sites: {},
      weather: {},
      localHappenings: {
        '02108': {
          location: '02108',
          sourceAttribution: 'Powered by Ticketmaster',
          updatedAt: '2026-02-25T12:00:00Z',
          items: []
        }
      },
      news: {
        cnn: {
          source: 'cnn',
          updatedAt: '2026-02-25T18:00:00Z',
          stories: [{ title: 'Story 1', link: 'https://www.cnn.com/story', publishedAt: '2026-02-25T17:00:00Z', source: 'cnn' }]
        }
      }
    });

    render(<App />);

    const headingText = await screen.findByText('CNN');
    const heading = headingText.closest('h3');
    expect(heading).toBeTruthy();
    if (!heading) {
      throw new Error('News source heading not found');
    }
    const favicon = heading.querySelector('img[alt="CNN source icon"]');
    expect(favicon).toBeTruthy();
    fireEvent.error(favicon as Element);
    await waitFor(() => {
      const fallback = heading.querySelector('span.news-source-icon.news-logo-fallback');
      expect(fallback).toBeTruthy();
      expect(fallback?.textContent).toBe('CNN');
    });
    expect(heading.querySelector('img[alt="CNN source icon"]')).toBeNull();
  });

  it('uses fallback icon for unmapped source with no resolvable URL', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchNewsSourceSettings).mockResolvedValueOnce({
      availableSources: [{ id: 'indie', name: 'Indie Feed', type: 'rss', url: 'not-a-url' }],
      effectiveSelectedSources: ['indie']
    });
    vi.mocked(api.fetchSignals).mockResolvedValueOnce({
      sites: {},
      weather: {},
      localHappenings: {
        '02108': {
          location: '02108',
          sourceAttribution: 'Powered by Ticketmaster',
          updatedAt: '2026-02-25T12:00:00Z',
          items: []
        }
      },
      news: {
        indie: {
          source: 'indie',
          updatedAt: '2026-02-25T18:00:00Z',
          stories: [{ title: 'Story 1', link: 'https://example.com/story', publishedAt: '2026-02-25T17:00:00Z', source: 'indie' }]
        }
      }
    });

    render(<App />);
    const headingText = await screen.findByText('Indie Feed');
    const heading = headingText.closest('h3');
    expect(heading).toBeTruthy();
    expect(heading?.querySelector('span.news-source-icon.news-logo-fallback')).toBeTruthy();
    expect(heading?.querySelector('img.news-source-icon')).toBeNull();
  });

  it('shows clear empty state for news when no stories are available', async () => {
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
          items: []
        }
      }
    });

    render(<App />);

    await waitFor(() => expect(screen.getByText('No news items available from your selected sources.')).toBeTruthy());
  });

  it('renders market row links to Yahoo Finance', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMarkets).mockResolvedValue({
      status: 'ok',
      asOf: '2026-02-25T18:30:00Z',
      items: [{ symbol: 'BTC-USD', price: 42000.33, change: -2.75, updatedAt: '2026-02-25T18:29:00Z' }]
    });

    render(<App />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'BTC-USD details' })).toBeTruthy());
    expect(screen.getByRole('link', { name: 'BTC-USD details' }).getAttribute('href')).toBe('https://finance.yahoo.com/quote/BTC-USD');
  });

  it('shows clear empty state when market symbols list is empty', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMarkets).mockResolvedValue({
      status: 'ok',
      asOf: '2026-02-25T18:30:00Z',
      items: []
    });
    localStorage.setItem('signal-sentinel:watchlist', JSON.stringify([]));

    render(<App />);
    await waitFor(() => expect(screen.getByText('No market data available right now.')).toBeTruthy());
  });

  it('shows backend ZIP resolution guidance when weather cannot resolve location', async () => {
    const api = await import('./api');
    localStorage.setItem('signal-sentinel:zip-codes', JSON.stringify(['53201']));
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['53201'],
      watchlist: [],
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
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
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Weather & Air Quality' })).toBeTruthy());
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

  it('shows friendly unauthorized admin state when admin endpoints return 401', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchAdminTrends).mockRejectedValueOnce(new Error('Admin trends request failed (401)'));
    vi.mocked(api.fetchAdminEmailPreview).mockRejectedValueOnce(new Error('Admin email preview request failed (401)'));
    window.location.hash = '#/admin';

    render(<App />);

    await waitFor(() => expect(screen.getByText(/Session expired\. Please log in again\./)).toBeTruthy());
    expect(screen.queryByRole('img', { name: 'Collector runs: success' })).toBeNull();
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

  it('hides settings nav for anonymous users and shows auth entry link', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    expect(screen.queryByRole('link', { name: /settings/i })).toBeNull();
    expect(screen.getByRole('link', { name: 'Sign In' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'About' })).toBeTruthy();
  });

  it('keeps healthy anonymous UI when auth is disabled server-side', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    vi.mocked(api.fetchHealth).mockResolvedValueOnce({ status: 'ok' });
    window.location.hash = '#/';

    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: "Today's Overview" })).toBeTruthy());
    expect(screen.queryByText('Backend health: degraded')).toBeNull();
    expect(screen.getByRole('link', { name: 'Sign In' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: /Weather & Air Quality/i })).toBeTruthy();
  });

  it('renders about page content', async () => {
    window.location.hash = '#/about';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('heading', { name: /About Today's Overview/i })).toBeTruthy());
    expect(screen.getByText(/Why I built this/i)).toBeTruthy();
    expect(screen.getByText(/Key goals/i)).toBeTruthy();
    expect(screen.getByText(/AI-Assisted Engineering Workflow/i)).toBeTruthy();
    expect(screen.getByText(/Tech stack/i)).toBeTruthy();
    expect(screen.getAllByText(/Java features used/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Libraries and tooling/i).length).toBeGreaterThan(0);
  });

  it('login flow shows settings nav after successful sign-in', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/auth?mode=login';

    render(<App />);

    const emailInput = await screen.findByLabelText('Email');
    fireEvent.change(emailInput, { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'hunter2-password' } });
    const signInButtons = screen.getAllByRole('button', { name: 'Sign In' });
    fireEvent.click(signInButtons[1]);

    await waitFor(() => expect(api.login).toHaveBeenCalledWith('user@example.com', 'hunter2-password'));
    await waitFor(() => expect(screen.getByRole('link', { name: /settings/i })).toBeTruthy());
  });

  it('signup enforces min password length and surfaces backend 400 error message', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    const badRequest = Object.assign(new Error('Password must be at least 8 characters'), { status: 400 });
    vi.mocked(api.signup).mockRejectedValueOnce(badRequest);
    window.location.hash = '#/auth?mode=signup';

    render(<App />);

    const passwordInput = await screen.findByPlaceholderText('********');
    const submitButton = screen.getAllByRole('button', { name: 'Create Account' })[1];
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

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Sign In' })).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'hunter2-password' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Sign In' })[1]);

    await waitFor(() => expect(api.login).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('heading', { name: /Places/i })).toBeTruthy());
  });

  it('shows forgot password notice in the auth card and not in the header', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/forgot';

    render(<App />);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }));

    const notice = 'Password reset is still under construction for this demo. Please contact the project author if you need help accessing your account.';
    await waitFor(() => expect(screen.getByText(notice)).toBeTruthy());
    const header = screen.getByRole('banner');
    expect(within(header).queryByText(notice)).toBeNull();
  });

  it('clears forgot password notice on cancel', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/forgot';

    render(<App />);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }));

    const notice = 'Password reset is still under construction for this demo. Please contact the project author if you need help accessing your account.';
    await waitFor(() => expect(screen.getByText(notice)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(window.location.hash).toBe('#/'));
    expect(screen.queryByText(notice)).toBeNull();
  });

  it('clears auth notice on mode switch', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMe).mockResolvedValueOnce(null);
    window.location.hash = '#/reset?token=test-reset-token';

    render(<App />);

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'new-password-123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reset password' }));

    const notice = 'Password reset complete. You can now log in.';
    await waitFor(() => expect(screen.getByText(notice)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Create Account' }));
    expect(screen.queryByText(notice)).toBeNull();
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
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
    });
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByText('Boston, MA (02108)')).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText('ZIPs (e.g., 02108, 98101)'), { target: { value: '98101' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add ZIPs' }));

    await waitFor(() =>
      expect(api.saveMyPreferences).toHaveBeenLastCalledWith({
        zipCodes: ['02108', '98101'],
        watchlist: ['AAPL'],
        newsSourceIds: ['cnn', 'wsj'], themeMode: 'light', accent: 'blue'
      })
    );
    await waitFor(() => expect(screen.getByText('Saved ✅')).toBeTruthy());
  });

  it('handles 401 from authenticated preference save by expiring session', async () => {
    const api = await import('./api');
    vi.mocked(api.fetchMyPreferences).mockResolvedValueOnce({
      zipCodes: ['02108'],
      watchlist: ['AAPL'],
      newsSourceIds: [], themeMode: 'light', accent: 'blue'
    });
    vi.mocked(api.saveMyPreferences)
      .mockResolvedValueOnce({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn', 'wsj'], themeMode: 'light', accent: 'blue' })
      .mockResolvedValueOnce({ zipCodes: ['02108'], watchlist: ['AAPL'], newsSourceIds: ['cnn', 'wsj'], themeMode: 'light', accent: 'blue' })
      .mockRejectedValueOnce(new Error('Preferences update failed (401)'));
    window.location.hash = '#/settings';

    render(<App />);

    await waitFor(() => expect(screen.getByText('Boston, MA (02108)')).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText('ZIPs (e.g., 02108, 98101)'), { target: { value: '98101' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add ZIPs' }));

    await waitFor(() => expect(screen.getByText('Session expired — please sign in again.')).toBeTruthy());
    await waitFor(() => expect(screen.queryByRole('link', { name: /settings/i })).toBeNull());
    expect(screen.queryByText('Backend health: degraded')).toBeNull();
  });
});
