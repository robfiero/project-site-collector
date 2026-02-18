import { useEffect, useMemo, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import {
  fetchDevOutbox,
  fetchCatalogDefaults,
  fetchCollectorStatus,
  fetchConfigView,
  fetchEnvironment,
  fetchEvents,
  fetchHealth,
  fetchMe,
  fetchMetrics,
  fetchMyPreferences,
  fetchSignals,
  forgotPassword,
  login,
  logout,
  resetPassword,
  saveMyPreferences,
  signup,
  type AuthUserView,
  type DevOutboxEmail
} from './api';
import { demoLocalHappenings, demoQuote } from './demoData';
import { filterEvents, normalizeEventEnvelope } from './eventFeed';
import { formatPlaceLabel } from './places';
import AdminDashboard from './admin/AdminDashboard';
import type {
  CatalogDefaults,
  CollectorStatus,
  EnvStatus,
  EventEnvelope,
  LocalHappeningsSignal,
  MarketQuoteSignal,
  MetricsResponse,
  SignalsSnapshot
} from './models';
import { loadWatchlist, loadZipCodes, saveWatchlist, saveZipCodes } from './preferences';

const MAX_EVENTS = 200;
const MAX_WATCHLIST = 25;
const FALLBACK_DEFAULT_ZIPS = ['02108', '98101'];
const FALLBACK_DEFAULT_WATCHLIST = ['AAPL', 'MSFT', 'SPY', 'BTC-USD', 'ETH-USD'];
const INTENDED_ROUTE_KEY = 'todays-overview:intended-route';
const KNOWN_SSE_TYPES = [
  'CollectorTickStarted',
  'CollectorTickCompleted',
  'AlertRaised',
  'SiteFetched',
  'ContentChanged',
  'EnvWeatherUpdated',
  'EnvAqiUpdated',
  'NewsUpdated',
  'UserRegistered',
  'LoginSucceeded',
  'LoginFailed',
  'PasswordResetRequested',
  'PasswordResetSucceeded',
  'PasswordResetFailed'
] as const;

type RouteName = 'home' | 'settings' | 'admin' | 'login' | 'signup' | 'forgot' | 'reset';
type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed';

const emptySnapshot: SignalsSnapshot = { sites: {}, news: {}, weather: {} };
const emptyDefaults: CatalogDefaults = { defaultZipCodes: [], defaultNewsSources: [], defaultWatchlist: [] };
const emptyMetrics: MetricsResponse = { sseClientsConnected: 0, eventsEmittedTotal: 0, recentEventsPerMinute: 0, collectors: {} };

export default function App() {
  const [route, setRoute] = useState<RouteName>(readRouteFromHash());
  const [health, setHealth] = useState<string>('unknown');
  const [snapshot, setSnapshot] = useState<SignalsSnapshot>(emptySnapshot);
  const [events, setEvents] = useState<EventEnvelope[]>([]);
  const [eventTypeFilter, setEventTypeFilter] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');
  const [eventFeedPaused, setEventFeedPaused] = useState<boolean>(false);
  const [pausedEventBuffer, setPausedEventBuffer] = useState<EventEnvelope[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [expandedEvents, setExpandedEvents] = useState<Set<string>>(new Set());
  const [metrics, setMetrics] = useState<MetricsResponse>(emptyMetrics);
  const [metricsUpdatedAt, setMetricsUpdatedAt] = useState<number>(Date.now());
  const [collectorStatus, setCollectorStatus] = useState<Record<string, CollectorStatus>>({});
  const [catalogDefaults, setCatalogDefaults] = useState<CatalogDefaults>(emptyDefaults);
  const [configView, setConfigView] = useState<Record<string, unknown>>({});
  const [envByZip, setEnvByZip] = useState<Record<string, EnvStatus>>({});
  const [zipCodes, setZipCodes] = useState<string[]>(loadZipCodes());
  const [watchlist, setWatchlist] = useState<string[]>(loadWatchlist());
  const [authUser, setAuthUser] = useState<AuthUserView | null>(null);
  const [authResolved, setAuthResolved] = useState<boolean>(false);
  const [sessionExpired, setSessionExpired] = useState<boolean>(false);
  const [devOutbox, setDevOutbox] = useState<DevOutboxEmail[]>([]);
  const [authMessage, setAuthMessage] = useState<string | null>(null);
  const [settingsSaveState, setSettingsSaveState] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [zipInput, setZipInput] = useState<string>('');
  const [symbolInput, setSymbolInput] = useState<string>('');

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const retryDelayRef = useRef<number>(1000);
  const pausedRef = useRef<boolean>(false);
  const settingsSavedTimerRef = useRef<number | null>(null);

  useEffect(() => {
    const onHashChange = () => setRoute(readRouteFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    if (authUser) {
      return;
    }
    saveZipCodes(zipCodes);
  }, [zipCodes, authUser]);

  useEffect(() => {
    if (authUser) {
      return;
    }
    saveWatchlist(watchlist);
  }, [watchlist, authUser]);

  useEffect(() => {
    let mounted = true;
    async function bootstrap(): Promise<void> {
      try {
        const [healthResponse, signalsResponse, eventsResponse, metricsResponse, statusResponse, defaultsResponse, configResponse, meResponse, outboxResponse] = await Promise.all([
          fetchHealth(),
          fetchSignals(),
          fetchEvents(100),
          fetchMetrics(),
          fetchCollectorStatus(),
          fetchCatalogDefaults(),
          fetchConfigView(),
          fetchMe(),
          fetchDevOutbox()
        ]);
        if (!mounted) {
          return;
        }
        setHealth(healthResponse.status);
        setSnapshot(signalsResponse);
        setEvents(eventsResponse.map(normalizeEventEnvelope).filter((e): e is EventEnvelope => e !== null).slice(-MAX_EVENTS));
        setMetrics(metricsResponse);
        setMetricsUpdatedAt(Date.now());
        setCollectorStatus(statusResponse);
        setCatalogDefaults(defaultsResponse);
        setConfigView(configResponse);
        setAuthUser(meResponse);
        setSessionExpired(false);
        setDevOutbox(outboxResponse);
        if (meResponse) {
          try {
            const prefs = await fetchMyPreferences();
            setZipCodes(prefs.zipCodes);
            setWatchlist(prefs.watchlist);
          } catch (prefError) {
            if (isUnauthorizedError(prefError)) {
              handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
            } else {
              throw prefError;
            }
          }
        }
        setAuthResolved(true);
        setError(null);
      } catch (err) {
        if (!mounted) {
          return;
        }
        setError(err instanceof Error ? err.message : 'Failed to load dashboard');
        setAuthResolved(true);
      }
    }
    bootstrap();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    const timer = window.setInterval(async () => {
      try {
        const [metricsResponse, statusResponse] = await Promise.all([fetchMetrics(), fetchCollectorStatus()]);
        setMetrics(metricsResponse);
        setMetricsUpdatedAt(Date.now());
        setCollectorStatus(statusResponse);
      } catch {
        // keep existing values if diagnostics polling fails temporarily
      }
    }, 15_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (authResolved && route === 'settings' && !authUser) {
      storeIntendedRoute('#/settings');
      window.location.hash = '#/login';
    }
  }, [authResolved, route, authUser]);

  useEffect(() => {
    pausedRef.current = eventFeedPaused;
  }, [eventFeedPaused]);

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
          if (!normalized) {
            return;
          }
          if (pausedRef.current) {
            setPausedEventBuffer((previous) => [...previous, normalized].slice(-MAX_EVENTS));
          } else {
            setEvents((previous) => [...previous, normalized].slice(-MAX_EVENTS));
          }
          setSnapshot((previous) => applyOptimisticUpdate(previous, normalized));
          setEnvByZip((previous) => applyEnvironmentUpdate(previous, normalized));
        } catch {
          // ignore malformed payload and keep stream alive
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

  useEffect(() => {
    if (eventFeedPaused || pausedEventBuffer.length === 0) {
      return;
    }
    setEvents((previous) => [...previous, ...pausedEventBuffer].slice(-MAX_EVENTS));
    setPausedEventBuffer([]);
  }, [eventFeedPaused, pausedEventBuffer]);

  const filteredEvents = useMemo(() => filterEvents(events, eventTypeFilter, searchQuery), [events, eventTypeFilter, searchQuery]);
  const siteEntries = useMemo(() => Object.values(snapshot.sites).sort((a, b) => a.siteId.localeCompare(b.siteId)), [snapshot.sites]);
  const newsEntries = useMemo(() => Object.values(snapshot.news).sort((a, b) => a.source.localeCompare(b.source)), [snapshot.news]);
  const marketEntries = useMemo(() => watchlist.map((symbol) => snapshot.markets?.[symbol] ?? demoQuote(symbol)), [watchlist, snapshot.markets]);
  const effectiveZipCodes = useMemo(
    () => (zipCodes.length > 0
      ? zipCodes
      : (catalogDefaults.defaultZipCodes.length > 0 ? catalogDefaults.defaultZipCodes : FALLBACK_DEFAULT_ZIPS)),
    [zipCodes, catalogDefaults.defaultZipCodes]
  );
  const happeningsEntries = useMemo(
    () => effectiveZipCodes.map((zip) => snapshot.localHappenings?.[zip] ?? demoLocalHappenings(zip)),
    [effectiveZipCodes, snapshot.localHappenings]
  );
  const showHeaderStatus = health !== 'ok' || connectionState !== 'open';

  useEffect(() => {
    let cancelled = false;
    async function loadEnvironment(): Promise<void> {
      try {
        const statuses = await fetchEnvironment(effectiveZipCodes);
        if (cancelled) {
          return;
        }
        setEnvByZip(Object.fromEntries(statuses.map((status) => [status.zip, status])) as Record<string, EnvStatus>);
      } catch {
        if (!cancelled) {
          setEnvByZip({});
        }
      }
    }
    loadEnvironment();
    return () => {
      cancelled = true;
    };
  }, [effectiveZipCodes]);

  const addZip = (): 'added' | 'invalid' | 'duplicate' | 'limit' => {
    const value = zipInput.trim();
    if (!/^\d{5}$/.test(value)) {
      return 'invalid';
    }
    let outcome: 'added' | 'duplicate' | 'limit' = 'added';
    setZipCodes((previous) => {
      if (previous.includes(value)) {
        outcome = 'duplicate';
        return previous;
      }
      if (previous.length >= 10) {
        outcome = 'limit';
        return previous;
      }
      return [...previous, value];
    });
    setZipInput('');
    return outcome;
  };

  const addSymbol = (): 'added' | 'invalid' | 'duplicate' | 'limit' => {
    const value = symbolInput.trim().toUpperCase();
    if (!value) {
      return 'invalid';
    }
    let outcome: 'added' | 'duplicate' | 'limit' = 'added';
    setWatchlist((previous) => {
      if (previous.includes(value)) {
        outcome = 'duplicate';
        return previous;
      }
      if (previous.length >= MAX_WATCHLIST) {
        outcome = 'limit';
        return previous;
      }
      return [...previous, value];
    });
    setSymbolInput('');
    return outcome;
  };

  const resetSettingsToDefaults = () => {
    const nextZips = catalogDefaults.defaultZipCodes.length > 0 ? catalogDefaults.defaultZipCodes.slice(0, 10) : FALLBACK_DEFAULT_ZIPS;
    const nextWatchlist = catalogDefaults.defaultWatchlist.length > 0 ? catalogDefaults.defaultWatchlist : FALLBACK_DEFAULT_WATCHLIST;
    setZipCodes(nextZips);
    setWatchlist(nextWatchlist);
    setZipInput('');
    setSymbolInput('');
  };

  const restorePlacesDefaults = () => {
    const nextZips = catalogDefaults.defaultZipCodes.length > 0 ? catalogDefaults.defaultZipCodes.slice(0, 10) : FALLBACK_DEFAULT_ZIPS;
    setZipCodes(nextZips);
    setZipInput('');
  };

  const restoreWatchlistDefaults = () => {
    const nextWatchlist = catalogDefaults.defaultWatchlist.length > 0 ? catalogDefaults.defaultWatchlist : FALLBACK_DEFAULT_WATCHLIST;
    setWatchlist(nextWatchlist);
    setSymbolInput('');
  };

  useEffect(() => {
    if (!authUser) {
      return;
    }
    setSettingsSaveState('saving');
    saveMyPreferences({
      zipCodes,
      watchlist,
      newsSourceIds: []
    }).then(() => {
      setSettingsSaveState('saved');
      if (settingsSavedTimerRef.current !== null) {
        window.clearTimeout(settingsSavedTimerRef.current);
      }
      settingsSavedTimerRef.current = window.setTimeout(() => setSettingsSaveState('idle'), 2000);
    }).catch((error) => {
      if (isUnauthorizedError(error)) {
        handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
        setSettingsSaveState('idle');
        return;
      }
      setSettingsSaveState('idle');
    });
  }, [authUser, zipCodes, watchlist]);

  useEffect(() => {
    return () => {
      if (settingsSavedTimerRef.current !== null) {
        window.clearTimeout(settingsSavedTimerRef.current);
      }
    };
  }, []);

  const doSignOut = async () => {
    try {
      await logout();
      setAuthUser(null);
      setSessionExpired(false);
      setZipCodes(loadZipCodes());
      setWatchlist(loadWatchlist());
      window.location.hash = '#/';
    } catch (err) {
      setAuthMessage(err instanceof Error ? err.message : 'Sign out failed');
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-container">
          <div className="card">
            <div className="header-top">
              <h1>Today&apos;s Overview</h1>
              <nav className="nav">
                <a href="#/" className={route === 'home' ? 'active' : ''}>Home</a>
                {authUser && <a href="#/settings" className={route === 'settings' ? 'active' : ''}>⚙ Settings</a>}
                <a href="#/admin" className={route === 'admin' ? 'active' : ''}>Admin / Diagnostics</a>
                {!authUser && <a href="#/login" className={route === 'login' ? 'active' : ''}>Login</a>}
                {!authUser && <a href="#/signup" className={route === 'signup' ? 'active' : ''}>Sign up</a>}
                {authUser && <button type="button" onClick={doSignOut}>Sign out</button>}
              </nav>
            </div>
            {authUser && <p className="meta">Signed in as {authUser.email}</p>}
            {showHeaderStatus && (
              <div className="header-status">
                {health !== 'ok' && <span className="warning">Backend health: degraded</span>}
                {connectionState !== 'open' && <span className="warning">Live updates: disconnected</span>}
                {connectionState === 'reconnecting' && <span className="reconnecting">Reconnecting...</span>}
              </div>
            )}
            {error && <p className="error">{error}</p>}
            {sessionExpired && (
              <p className="error session-expired-banner">
                Session expired — please sign in again.
                <button type="button" onClick={() => setSessionExpired(false)} aria-label="Dismiss session expired message">Dismiss</button>
              </p>
            )}
            {authMessage && <p className="error">{authMessage}</p>}
          </div>
        </div>
      </header>

      <div className="app-container">
        {route === 'home' ? (
          <HomePage
            zipCodes={effectiveZipCodes}
            envByZip={envByZip}
            newsEntries={newsEntries}
            happeningsEntries={happeningsEntries}
            marketEntries={marketEntries}
          />
        ) : route === 'settings' ? (
          authUser ? (
          <SettingsPage
            zipCodes={zipCodes}
            zipInput={zipInput}
            setZipInput={setZipInput}
            addZip={addZip}
            removeZip={(zip) => setZipCodes((previous) => previous.filter((value) => value !== zip))}
            watchlist={watchlist}
            symbolInput={symbolInput}
            setSymbolInput={setSymbolInput}
            addSymbol={addSymbol}
            maxWatchlist={MAX_WATCHLIST}
            saveState={settingsSaveState}
            removeSymbol={(symbol) => setWatchlist((previous) => previous.filter((value) => value !== symbol))}
            onRestorePlaces={restorePlacesDefaults}
            onRestoreWatchlist={restoreWatchlistDefaults}
            onReset={resetSettingsToDefaults}
          />
          ) : authResolved ? (
            <AuthPage
              title="Login required"
              description="Sign in to manage server-side settings."
              submitLabel="Go to Login"
              fields={[]}
              onSubmit={async () => {
                window.location.hash = '#/login';
              }}
            />
          ) : (
            <main className="settings-page"><section className="card"><p className="meta">Loading account...</p></section></main>
          )
        ) : route === 'login' ? (
          <AuthPage
            title="Login"
            description="Sign in to save your settings server-side."
            submitLabel="Login"
            fields={[
              { key: 'email', label: 'Email', type: 'email' },
              { key: 'password', label: 'Password', type: 'password' }
            ]}
            onSubmit={async (values) => {
              const user = await login(values.email, values.password);
              setAuthUser(user);
              setSessionExpired(false);
              const prefs = await fetchMyPreferences();
              setZipCodes(prefs.zipCodes);
              setWatchlist(prefs.watchlist);
              window.location.hash = consumeIntendedRoute();
            }}
          />
        ) : route === 'signup' ? (
          <AuthPage
            title="Sign up"
            description="Create an account to personalize your dashboard."
            submitLabel="Create account"
            fields={[
              { key: 'email', label: 'Email', type: 'email' },
              { key: 'password', label: 'Password', type: 'password' }
            ]}
            onSubmit={async (values) => {
              const user = await signup(values.email, values.password);
              setAuthUser(user);
              setSessionExpired(false);
              window.location.hash = consumeIntendedRoute();
            }}
          />
        ) : route === 'forgot' ? (
          <AuthPage
            title="Forgot password"
            description="Enter your email and we will send reset instructions."
            submitLabel="Send reset link"
            fields={[{ key: 'email', label: 'Email', type: 'email' }]}
            onSubmit={async (values) => {
              await forgotPassword(values.email);
              setAuthMessage('If that account exists, a reset email has been sent.');
            }}
          />
        ) : route === 'reset' ? (
          <AuthPage
            title="Reset password"
            description="Set your new password."
            submitLabel="Reset password"
            fields={[{ key: 'password', label: 'New password', type: 'password' }]}
            onSubmit={async (values) => {
              const token = readResetTokenFromHash();
              if (!token) {
                throw new Error('Reset token missing');
              }
              await resetPassword(token, values.password);
              setAuthMessage('Password reset complete. You can now log in.');
              window.location.hash = '#/login';
            }}
          />
        ) : (
          <AdminDashboard
            health={health}
            connectionState={connectionState}
            metrics={metrics}
            metricsUpdatedAt={metricsUpdatedAt}
            collectorStatus={collectorStatus}
            siteEntries={siteEntries}
            catalogDefaults={catalogDefaults}
            configView={configView}
            filteredEvents={filteredEvents}
            eventTypeFilter={eventTypeFilter}
            setEventTypeFilter={setEventTypeFilter}
            searchQuery={searchQuery}
            setSearchQuery={setSearchQuery}
            expandedEvents={expandedEvents}
            setExpandedEvents={setExpandedEvents}
            knownTypes={KNOWN_SSE_TYPES}
            maxEvents={MAX_EVENTS}
            paused={eventFeedPaused}
            onTogglePause={() => setEventFeedPaused((prev) => !prev)}
            devOutbox={devOutbox}
            currentUser={authUser}
          />
        )}
      </div>
    </div>
  );
}

type HomePageProps = {
  zipCodes: string[];
  envByZip: Record<string, EnvStatus>;
  newsEntries: SignalsSnapshot['news'][string][];
  happeningsEntries: LocalHappeningsSignal[];
  marketEntries: MarketQuoteSignal[];
};

function HomePage(props: HomePageProps) {
  return (
    <main>
      <section className="primary-grid">
        <section className="card weather">
          <h2>Environment</h2>
          {props.zipCodes.length === 0 ? (
            <p className="empty">No ZIP codes selected. Add places in Settings.</p>
          ) : (
            <div className="card-body">
              <table className="weather-table">
                <thead>
                  <tr>
                    <th>Place</th>
                    <th>Weather</th>
                    <th>Air Quality</th>
                  </tr>
                </thead>
                <tbody>
                  {props.zipCodes.map((zip) => {
                    const env = props.envByZip[zip];
                    return (
                      <tr key={zip}>
                        <td>{formatPlaceLabel(zip)}</td>
                        <td>
                          {env?.weather?.temperatureF != null ? (
                            <>
                              {weatherIcon(env.weather.forecast)} {env.weather.temperatureF.toFixed(1)} F, {env.weather.forecast}
                            </>
                          ) : (
                            <span className="empty-inline">Waiting for first weather update for this ZIP.</span>
                          )}
                          {env?.updatedAt && <div className="meta">{formatInstant(env.updatedAt)}</div>}
                        </td>
                        <td>
                          {env?.aqi?.aqi != null ? (
                            <>AQI {env.aqi.aqi} <span className="aqi-meta">- {env.aqi.category ?? 'Unknown'}</span></>
                          ) : (
                            <span className="empty-inline">{env?.aqi?.message ?? 'AQI unavailable'}</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="card news">
          <h2>Top News</h2>
          <div className="card-body">
            {props.newsEntries.length === 0 ? <p className="empty">No news signals yet.</p> : props.newsEntries.map((source) => (
              <article key={source.source} className="item">
                <h3>{source.source}</h3>
                {source.stories.length === 0 ? <p className="empty">No stories in current snapshot.</p> : (
                  <ul className="news-list top-news">
                    {source.stories.slice(0, 5).map((story, idx) => (
                      <li key={`${source.source}-${idx}`}><a href={story.link} target="_blank" rel="noreferrer">{story.title}</a></li>
                    ))}
                  </ul>
                )}
              </article>
            ))}
          </div>
        </section>
      </section>

      <section className="secondary-grid">
        <section className="card">
          <h2>Local What&apos;s Happening</h2>
          <div className="card-body">
            {props.happeningsEntries.length === 0 ? <p className="empty">No local places selected.</p> : props.happeningsEntries.map((entry) => (
              <article key={entry.location} className="item">
                <h3>{formatPlaceLabel(entry.location)}</h3>
                <ul>{entry.headlines.map((headline, index) => <li key={`${entry.location}-${index}`}>{headline}</li>)}</ul>
              </article>
            ))}
          </div>
        </section>

        <section className="card">
          <h2>Markets</h2>
          <div className="card-body">
            {props.marketEntries.length === 0 ? <p className="empty">No symbols in watchlist.</p> : props.marketEntries.map((entry) => (
              <article key={entry.symbol} className="item market-row">
                <h3>{entry.symbol}</h3>
                <p><span className="market-value">{entry.price.toFixed(2)} ({entry.change >= 0 ? '+' : ''}{entry.change.toFixed(2)})</span></p>
              </article>
            ))}
          </div>
        </section>
      </section>
    </main>
  );
}

type SettingsPageProps = {
  zipCodes: string[];
  zipInput: string;
  setZipInput: Dispatch<SetStateAction<string>>;
  addZip: () => 'added' | 'invalid' | 'duplicate' | 'limit';
  removeZip: (zip: string) => void;
  watchlist: string[];
  symbolInput: string;
  setSymbolInput: Dispatch<SetStateAction<string>>;
  addSymbol: () => 'added' | 'invalid' | 'duplicate' | 'limit';
  maxWatchlist: number;
  saveState: 'idle' | 'saving' | 'saved';
  removeSymbol: (symbol: string) => void;
  onRestorePlaces: () => void;
  onRestoreWatchlist: () => void;
  onReset: () => void;
};

function SettingsPage(props: SettingsPageProps) {
  const zipCandidate = props.zipInput.trim();
  const symbolCandidate = props.symbolInput.trim().toUpperCase();
  const zipValid = /^\d{5}$/.test(zipCandidate);
  const symbolValid = symbolCandidate.length > 0;
  const [zipHint, setZipHint] = useState<string>('');
  const [symbolHint, setSymbolHint] = useState<string>('');
  const [resetHint, setResetHint] = useState<string>('');
  const defaultResetPrompt = 'Reset Places + Watchlist to defaults?';

  return (
    <main className="settings-page">
      <section className="settings-intro">
        <h2>Settings</h2>
        <p className="meta">Configure Places and Markets on this device.</p>
      </section>
      <section className="settings-grid">
        <section className="card controls">
          <div className="card-title-row">
            <h2>Places ({props.zipCodes.length}/10)</h2>
            <button
              type="button"
              className="link-button"
              onClick={() => {
                props.onRestorePlaces();
                setZipHint('');
                setResetHint('');
              }}
            >
              Restore defaults
            </button>
          </div>
          <p className="meta">Add up to 10 US ZIP codes. Saved in browser localStorage.</p>
          {props.saveState === 'saving' && <p className="meta inline-hint">Saving...</p>}
          {props.saveState === 'saved' && <p className="meta inline-hint">Saved ✅</p>}
          <form
            className="inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              const result = props.addZip();
              setResetHint('');
              if (result === 'invalid') {
                setZipHint('Enter a 5-digit ZIP');
              } else if (result === 'duplicate') {
                setZipHint('Already added');
              } else if (result === 'limit') {
                setZipHint('You can add up to 10 ZIP codes');
              } else {
                setZipHint('');
              }
            }}
          >
            <label htmlFor="settings-zip-input" className="sr-only">ZIP code</label>
            <input
              id="settings-zip-input"
              aria-label="ZIP code"
              value={props.zipInput}
              onChange={(e) => {
                props.setZipInput(e.target.value.replace(/\D/g, '').slice(0, 5));
                setZipHint('');
              }}
              placeholder="ZIP (e.g., 02108)"
            />
            <button type="submit" disabled={!zipValid}>Add ZIP</button>
          </form>
          {zipCandidate.length > 0 && !zipValid && <p className="meta inline-hint">Enter a 5-digit ZIP</p>}
          {zipHint && <p className="meta inline-hint">{zipHint}</p>}
          <div className="chips">
            {props.zipCodes.map((zip) => (
              <div key={zip} className="chip-item">
                <span className="chip-label">{formatPlaceLabel(zip)}</span>
                <button type="button" className="chip-remove" aria-label={`Remove ${formatPlaceLabel(zip)}`} onClick={() => props.removeZip(zip)}>x</button>
              </div>
            ))}
          </div>
        </section>

        <section className="card controls">
          <div className="card-title-row">
            <h2>Watchlist ({props.watchlist.length}/{props.maxWatchlist})</h2>
            <button
              type="button"
              className="link-button"
              onClick={() => {
                props.onRestoreWatchlist();
                setSymbolHint('');
                setResetHint('');
              }}
            >
              Restore defaults
            </button>
          </div>
          <p className="meta">Symbols are stored in browser localStorage for this device.</p>
          <form
            className="inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              const result = props.addSymbol();
              setResetHint('');
              if (result === 'invalid') {
                setSymbolHint('Enter a symbol (e.g., NVDA)');
              } else if (result === 'duplicate') {
                setSymbolHint('Already added');
              } else if (result === 'limit') {
                setSymbolHint(`You can add up to ${props.maxWatchlist} symbols`);
              } else {
                setSymbolHint('');
              }
            }}
          >
            <label htmlFor="settings-symbol-input" className="sr-only">Market symbol</label>
            <input
              id="settings-symbol-input"
              aria-label="Market symbol"
              value={props.symbolInput}
              onChange={(e) => {
                props.setSymbolInput(e.target.value.toUpperCase().replace(/\s+/g, ''));
                setSymbolHint('');
              }}
              placeholder="Symbol (e.g., NVDA)"
            />
            <button type="submit" disabled={!symbolValid}>Add Symbol</button>
          </form>
          {props.symbolInput.length > 0 && !symbolValid && <p className="meta inline-hint">Enter a symbol (e.g., NVDA)</p>}
          {symbolHint && <p className="meta inline-hint">{symbolHint}</p>}
          <div className="chips">
            {props.watchlist.map((symbol) => (
              <div key={symbol} className="chip-item">
                <span className="chip-label">{symbol}</span>
                <button type="button" className="chip-remove" aria-label={`Remove ${symbol}`} onClick={() => props.removeSymbol(symbol)}>x</button>
              </div>
            ))}
          </div>
        </section>
      </section>
      <section className="card danger-zone">
        <h2>Danger zone</h2>
        <p className="meta">Reset Places and Watchlist back to default values for this device.</p>
        <div className="settings-actions">
          <button
            type="button"
            onClick={() => {
              if (!window.confirm(defaultResetPrompt)) {
                return;
              }
              props.onReset();
              setZipHint('');
              setSymbolHint('');
              setResetHint('Reset complete');
            }}
          >
            Reset to defaults
          </button>
          {resetHint && <p className="meta inline-hint">{resetHint}</p>}
        </div>
      </section>
    </main>
  );
}

type AuthField = {
  key: 'email' | 'password';
  label: string;
  type: 'email' | 'password';
};

type AuthPageProps = {
  title: string;
  description: string;
  submitLabel: string;
  fields: AuthField[];
  onSubmit: (values: Record<'email' | 'password', string>) => Promise<void>;
};

function AuthPage(props: AuthPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  return (
    <main className="settings-page">
      <section className="card controls">
        <h2>{props.title}</h2>
        <p className="meta">{props.description}</p>
        <form
          className="auth-form"
          onSubmit={async (event) => {
            event.preventDefault();
            setPending(true);
            setMessage(null);
            try {
              await props.onSubmit({ email, password });
              setMessage('Success');
            } catch (error) {
              setMessage(formatAuthError(error));
            } finally {
              setPending(false);
            }
          }}
        >
          {props.fields.some((field) => field.key === 'email') && (
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                required
              />
            </label>
          )}
          {props.fields.some((field) => field.key === 'password') && (
            <label>
              Password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="********"
                required
              />
            </label>
          )}
          <button type="submit" disabled={pending}>{pending ? 'Working...' : props.submitLabel}</button>
        </form>
        {message && <p className="meta">{message}</p>}
        <p className="meta auth-links">
          <a href="#/forgot">Forgot password?</a>
        </p>
      </section>
    </main>
  );
}

function formatAuthError(error: unknown): string {
  if (!(error instanceof Error)) {
    return 'Request failed. Please try again.';
  }
  if (error.message.includes('(401)')) {
    return 'Invalid credentials. Please check your email and password.';
  }
  if (error.message.includes('(400)')) {
    return 'Please review your input and try again.';
  }
  return 'Request failed. Please try again.';
}

function isUnauthorizedError(error: unknown): boolean {
  return error instanceof Error && error.message.includes('(401)');
}

function handleSessionExpired(
  setAuthUser: Dispatch<SetStateAction<AuthUserView | null>>,
  setSessionExpired: Dispatch<SetStateAction<boolean>>,
  setAuthMessage: Dispatch<SetStateAction<string | null>>
) {
  setAuthUser(null);
  setSessionExpired(true);
  setAuthMessage(null);
}

function storeIntendedRoute(routeHash: string) {
  if (!routeHash.startsWith('#/')) {
    return;
  }
  sessionStorage.setItem(INTENDED_ROUTE_KEY, routeHash);
}

function consumeIntendedRoute(): string {
  const saved = sessionStorage.getItem(INTENDED_ROUTE_KEY);
  sessionStorage.removeItem(INTENDED_ROUTE_KEY);
  if (saved && saved.startsWith('#/')) {
    return saved;
  }
  return '#/';
}

function applyOptimisticUpdate(snapshot: SignalsSnapshot, envelope: EventEnvelope): SignalsSnapshot {
  switch (envelope.type) {
    case 'WeatherUpdated': {
      const event = envelope.event as { location: string; tempF: number; conditions: string; timestamp: number };
      return {
        ...snapshot,
        weather: {
          ...snapshot.weather,
          [event.location]: {
            location: event.location,
            tempF: event.tempF,
            conditions: event.conditions,
            alerts: snapshot.weather[event.location]?.alerts ?? [],
            updatedAt: event.timestamp
          }
        }
      };
    }
    default:
      return snapshot;
  }
}

function applyEnvironmentUpdate(
  envByZip: Record<string, EnvStatus>,
  envelope: EventEnvelope
): Record<string, EnvStatus> {
  if (envelope.type === 'EnvWeatherUpdated') {
    const event = envelope.event as Record<string, unknown>;
    const zip = asString(event.zip) ?? asString(event.location);
    if (!zip) {
      return envByZip;
    }

    const previous = envByZip[zip];
    const observedAt = toIsoTimestamp(event.observedAt ?? envelope.timestamp);
    const nextWeather = {
      temperatureF: asNumber(event.tempF),
      forecast: asString(event.conditions) ?? previous?.weather.forecast ?? 'Unknown',
      windSpeed: asString(event.windSpeed) ?? previous?.weather.windSpeed ?? '',
      observedAt
    };
    return {
      ...envByZip,
      [zip]: {
        zip,
        lat: previous?.lat ?? 0,
        lon: previous?.lon ?? 0,
        weather: nextWeather,
        aqi: previous?.aqi ?? { aqi: null, category: null, observedAt, message: 'AQI unavailable' },
        updatedAt: observedAt
      }
    };
  }

  if (envelope.type === 'EnvAqiUpdated') {
    const event = envelope.event as Record<string, unknown>;
    const zip = asString(event.zip);
    if (!zip) {
      return envByZip;
    }
    const previous = envByZip[zip];
    const observedAt = toIsoTimestamp(event.observedAt ?? envelope.timestamp);
    const eventAqi = asNumber(event.aqi);
    const nextAqi = eventAqi == null
      ? {
        aqi: null,
        category: asString(event.category),
        observedAt,
        message: asString(event.message) ?? 'AQI unavailable'
      }
      : {
        aqi: eventAqi,
        category: asString(event.category) ?? previous?.aqi.category ?? 'Unknown',
        observedAt,
        message: null
      };
    return {
      ...envByZip,
      [zip]: {
        zip,
        lat: previous?.lat ?? 0,
        lon: previous?.lon ?? 0,
        weather: previous?.weather ?? {
          temperatureF: null,
          forecast: 'Unknown',
          windSpeed: '',
          observedAt
        },
        aqi: nextAqi,
        updatedAt: observedAt
      }
    };
  }

  return envByZip;
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value : null;
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function toIsoTimestamp(value: unknown): string {
  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    if (!Number.isNaN(parsed)) {
      return new Date(parsed).toISOString();
    }
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return new Date(value * 1000).toISOString();
  }
  return new Date().toISOString();
}

function readRouteFromHash(): RouteName {
  if (window.location.hash === '#/login') {
    return 'login';
  }
  if (window.location.hash === '#/signup') {
    return 'signup';
  }
  if (window.location.hash.startsWith('#/reset')) {
    return 'reset';
  }
  if (window.location.hash === '#/forgot') {
    return 'forgot';
  }
  if (window.location.hash === '#/admin') {
    return 'admin';
  }
  if (window.location.hash === '#/settings') {
    return 'settings';
  }
  return 'home';
}

function readResetTokenFromHash(): string | null {
  const hash = window.location.hash;
  const marker = 'token=';
  const index = hash.indexOf(marker);
  if (index < 0) {
    return null;
  }
  return decodeURIComponent(hash.substring(index + marker.length));
}

function weatherIcon(conditions: string): string {
  const value = conditions.toLowerCase();
  if (value.includes('rain') || value.includes('storm')) {
    return '🌧';
  }
  if (value.includes('cloud')) {
    return '☁️';
  }
  return '☀️';
}

function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
