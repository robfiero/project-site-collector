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
  fetchNewsSourceSettings,
  fetchMyPreferences,
  fetchSignals,
  forgotPassword,
  login,
  logout,
  resetPassword,
  saveMyPreferences,
  signup,
  triggerCollectorRefresh,
  type AuthUserView,
  type DevOutboxEmail
} from './api';
import { demoLocalHappenings, demoQuote } from './demoData';
import { filterEvents, normalizeEventEnvelope } from './eventFeed';
import { formatMarketSymbolLabel } from './markets';
import { formatPlaceLabel } from './places';
import cnnLogo from './assets/news-logos/cnn.svg';
import foxLogo from './assets/news-logos/fox.svg';
import nprLogo from './assets/news-logos/npr.svg';
import nytLogo from './assets/news-logos/nyt.svg';
import vergeLogo from './assets/news-logos/verge.svg';
import wsjLogo from './assets/news-logos/wsj.svg';
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
const FALLBACK_DEFAULT_NEWS_SOURCES = ['cnn', 'wsj', 'verge'];
const AUTH_TRANSITION_COLLECTORS = ['envCollector', 'rssCollector', 'localEventsCollector'];
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
const NEWS_LOGO_BY_SOURCE_ID: Record<string, { src: string; alt: string }> = {
  nyt: { src: nytLogo, alt: 'The New York Times logo' },
  nyt_most_popular: { src: nytLogo, alt: 'The New York Times logo' },
  cnn: { src: cnnLogo, alt: 'CNN logo' },
  fox: { src: foxLogo, alt: 'Fox News logo' },
  wsj: { src: wsjLogo, alt: 'Wall Street Journal logo' },
  verge: { src: vergeLogo, alt: 'The Verge logo' },
  npr_news_now: { src: nprLogo, alt: 'NPR logo' },
  npr_politics: { src: nprLogo, alt: 'NPR logo' },
  npr_morning_edition: { src: nprLogo, alt: 'NPR logo' }
};

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
  const [selectedNewsSourceIds, setSelectedNewsSourceIds] = useState<string[]>(FALLBACK_DEFAULT_NEWS_SOURCES);
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
  const newsRefreshTimerRef = useRef<number | null>(null);
  const retryDelayRef = useRef<number>(1000);
  const pausedRef = useRef<boolean>(false);
  const settingsSavedTimerRef = useRef<number | null>(null);
  const previousRouteRef = useRef<RouteName>(route);

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
        const [healthResponse, signalsResponse, eventsResponse, metricsResponse, statusResponse, defaultsResponse, configResponse, meResponse, outboxResponse, newsSourceSettings] = await Promise.all([
          fetchHealth(),
          fetchSignals(),
          fetchEvents(100),
          fetchMetrics(),
          fetchCollectorStatus(),
          fetchCatalogDefaults(),
          fetchConfigView(),
          fetchMe(),
          fetchDevOutbox(),
          fetchNewsSourceSettings()
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
        setCatalogDefaults({
          ...defaultsResponse,
          defaultNewsSources: defaultsResponse.defaultNewsSources.length > 0
            ? defaultsResponse.defaultNewsSources
            : newsSourceSettings.availableSources,
          defaultSelectedNewsSources: defaultsResponse.defaultSelectedNewsSources && defaultsResponse.defaultSelectedNewsSources.length > 0
            ? defaultsResponse.defaultSelectedNewsSources
            : newsSourceSettings.effectiveSelectedSources
        });
        setConfigView(configResponse);
        setAuthUser(meResponse);
        setSessionExpired(false);
        setDevOutbox(outboxResponse);
        setSelectedNewsSourceIds(newsSourceSettings.effectiveSelectedSources.length > 0
          ? newsSourceSettings.effectiveSelectedSources
          : (defaultsResponse.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES));
        if (meResponse) {
          try {
            const prefs = await fetchMyPreferences();
            setZipCodes(prefs.zipCodes);
            setWatchlist(prefs.watchlist);
            setSelectedNewsSourceIds(
              prefs.newsSourceIds.length > 0
                ? prefs.newsSourceIds
                : (newsSourceSettings.effectiveSelectedSources.length > 0
                  ? newsSourceSettings.effectiveSelectedSources
                  : (defaultsResponse.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES))
            );
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
          if (normalized.type === 'NewsUpdated' && newsRefreshTimerRef.current === null) {
            newsRefreshTimerRef.current = window.setTimeout(async () => {
              newsRefreshTimerRef.current = null;
              try {
                const refreshedSignals = await fetchSignals();
                setSnapshot(refreshedSignals);
              } catch {
                // keep existing snapshot if refresh fails
              }
            }, 350);
          }
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
      if (newsRefreshTimerRef.current !== null) {
        window.clearTimeout(newsRefreshTimerRef.current);
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
  const newsEntries = useMemo(() => {
    const selected = new Set(selectedNewsSourceIds);
    return Object.values(snapshot.news)
      .filter((source) => selected.size === 0 || selected.has(source.source))
      .sort((a, b) => a.source.localeCompare(b.source));
  }, [snapshot.news, selectedNewsSourceIds]);
  const newsSourceLabels = useMemo(() => {
    return new Map(
      catalogDefaults.defaultNewsSources.map((source) => [source.id, source.name])
    );
  }, [catalogDefaults.defaultNewsSources]);
  const marketEntries = useMemo(() => watchlist.map((symbol) => snapshot.markets?.[symbol] ?? demoQuote(symbol)), [watchlist, snapshot.markets]);
  const effectiveZipCodes = useMemo(
    () => normalizeZipCodes(
      zipCodes.length > 0
        ? zipCodes
        : (catalogDefaults.defaultZipCodes.length > 0 ? catalogDefaults.defaultZipCodes : FALLBACK_DEFAULT_ZIPS)
    ),
    [zipCodes, catalogDefaults.defaultZipCodes]
  );
  const happeningsEntries = useMemo(
    () => {
      const backendEntries = Object.values(snapshot.localHappenings ?? {});
      if (backendEntries.length > 0) {
        return backendEntries;
      }
      return effectiveZipCodes.map((zip) => demoLocalHappenings(zip));
    },
    [effectiveZipCodes, snapshot.localHappenings]
  );
  const happeningsAttribution = useMemo(
    () => happeningsEntries.find((entry) => entry.sourceAttribution)?.sourceAttribution ?? 'Powered by Ticketmaster',
    [happeningsEntries]
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
        setEnvByZip(
          Object.fromEntries(
            statuses
              .map((status) => ({ ...status, zip: normalizeZip(status.zip) ?? status.zip }))
              .map((status) => [status.zip, status])
          ) as Record<string, EnvStatus>
        );
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
    const nextNewsSources = catalogDefaults.defaultSelectedNewsSources && catalogDefaults.defaultSelectedNewsSources.length > 0
      ? catalogDefaults.defaultSelectedNewsSources
      : FALLBACK_DEFAULT_NEWS_SOURCES;
    setZipCodes(nextZips);
    setWatchlist(nextWatchlist);
    setSelectedNewsSourceIds(nextNewsSources);
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

  const restoreNewsSourceDefaults = () => {
    const nextNewsSources = catalogDefaults.defaultSelectedNewsSources && catalogDefaults.defaultSelectedNewsSources.length > 0
      ? catalogDefaults.defaultSelectedNewsSources
      : FALLBACK_DEFAULT_NEWS_SOURCES;
    setSelectedNewsSourceIds(nextNewsSources);
  };

  useEffect(() => {
    if (!authUser) {
      return;
    }
      setSettingsSaveState('saving');
      saveMyPreferences({
        zipCodes,
        watchlist,
        newsSourceIds: selectedNewsSourceIds
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
  }, [authUser, zipCodes, watchlist, selectedNewsSourceIds]);

  useEffect(() => {
    return () => {
      if (settingsSavedTimerRef.current !== null) {
        window.clearTimeout(settingsSavedTimerRef.current);
      }
    };
  }, []);

  const refreshAfterAuthTransition = async (targetZips: string[]) => {
    try {
      await triggerCollectorRefresh(AUTH_TRANSITION_COLLECTORS);
    } catch {
      // Continue with snapshot refresh even when manual collector refresh is unavailable.
    }

    try {
      const nextSnapshot = await fetchSignals();
      setSnapshot(nextSnapshot);
    } catch {
      // Keep existing snapshot if fetch fails.
    }

    try {
      const statuses = await fetchEnvironment(normalizeZipCodes(targetZips));
      setEnvByZip(
        Object.fromEntries(
          statuses
            .map((status) => ({ ...status, zip: normalizeZip(status.zip) ?? status.zip }))
            .map((status) => [status.zip, status])
        ) as Record<string, EnvStatus>
      );
    } catch {
      // Keep existing env status if refresh fails.
    }
  };

  useEffect(() => {
    const previousRoute = previousRouteRef.current;
    previousRouteRef.current = route;
    if (previousRoute === 'settings' && route === 'home') {
      void refreshAfterAuthTransition(effectiveZipCodes);
    }
  }, [route, effectiveZipCodes]);

  const doSignOut = async () => {
    try {
      await logout();
      const nextZips = loadZipCodes();
      setAuthUser(null);
      setSessionExpired(false);
      setAuthMessage(null);
      setZipCodes(nextZips);
      setWatchlist(loadWatchlist());
      setSelectedNewsSourceIds(catalogDefaults.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES);
      await refreshAfterAuthTransition(nextZips);
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
            {authUser && <p className="meta signed-in-label-row">Signed in as {authUser.email}</p>}
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
            resolveNewsSourceLabel={(sourceId) => newsSourceLabels.get(sourceId) ?? sourceId}
            happeningsEntries={happeningsEntries}
            happeningsAttribution={happeningsAttribution}
            marketEntries={marketEntries}
          />
        ) : route === 'settings' ? (
          authUser ? (
          <SettingsPage
            zipCodes={zipCodes}
            resolveZipLabel={(zip) => envByZip[zip]?.locationLabel ?? formatPlaceLabel(zip)}
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
            availableNewsSources={catalogDefaults.defaultNewsSources}
            selectedNewsSourceIds={selectedNewsSourceIds}
            onToggleNewsSource={(sourceId, checked) => {
              setSelectedNewsSourceIds((previous) => {
                if (checked) {
                  if (previous.includes(sourceId)) {
                    return previous;
                  }
                  return [...previous, sourceId];
                }
                return previous.filter((id) => id !== sourceId);
              });
            }}
            onRestoreNewsSources={restoreNewsSourceDefaults}
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
              setAuthMessage(null);
              const prefs = await fetchMyPreferences();
              setZipCodes(prefs.zipCodes);
              setWatchlist(prefs.watchlist);
              setSelectedNewsSourceIds(
                prefs.newsSourceIds.length > 0
                  ? prefs.newsSourceIds
                  : (catalogDefaults.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES)
              );
              await refreshAfterAuthTransition(prefs.zipCodes);
              window.location.hash = consumeIntendedRoute();
            }}
          />
        ) : route === 'signup' ? (
          <AuthPage
            title="Sign up"
            description="Create an account to personalize your dashboard."
            submitLabel="Create account"
            passwordRequirement="Password must be at least 8 characters."
            fields={[
              { key: 'email', label: 'Email', type: 'email' },
              { key: 'password', label: 'Password', type: 'password' }
            ]}
            onSubmit={async (values) => {
              const user = await signup(values.email, values.password);
              setAuthUser(user);
              setSessionExpired(false);
              setAuthMessage(null);
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
  resolveNewsSourceLabel: (sourceId: string) => string;
  happeningsEntries: LocalHappeningsSignal[];
  happeningsAttribution: string;
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
                    <th>Current Weather</th>
                    <th>Air Quality</th>
                  </tr>
                </thead>
                <tbody>
                  {props.zipCodes.map((zip) => {
                    const normalizedZip = normalizeZip(zip) ?? zip;
                    const env = props.envByZip[normalizedZip] ?? props.envByZip[zip];
                    return (
                      <tr key={normalizedZip}>
                        <td>{env?.locationLabel ?? formatPlaceLabel(normalizedZip)}</td>
                        <td>
                          {env?.weather?.temperatureF != null ? (
                            <>
                              {weatherIcon(env.weather.forecast)} {env.weather.temperatureF.toFixed(1)} F, {env.weather.forecast}
                            </>
                          ) : (
                            <span className="empty-inline">
                              {env?.weather?.forecast && env.weather.forecast !== 'Unknown'
                                ? env.weather.forecast
                                : 'Waiting for first weather update for this ZIP.'}
                            </span>
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
                <h3 className="news-source-heading">
                  <NewsSourceLogo sourceId={source.source} />
                  <span>{props.resolveNewsSourceLabel(source.source)}</span>
                </h3>
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
          <p className="meta section-description">{props.happeningsAttribution}</p>
          <div className="card-body">
            {props.happeningsEntries.length === 0 ? <p className="empty">No local places selected.</p> : props.happeningsEntries.map((entry) => (
              <article key={entry.location} className="item">
                <h3>{entry.location.startsWith('lat:') ? 'Local area' : formatPlaceLabel(entry.location)}</h3>
                <ul>
                  {filterDisplayableHappeningItems(entry.items).map((item) => (
                    <li key={item.id}>
                      <a href={item.url} target="_blank" rel="noreferrer">{item.name}</a>
                    </li>
                  ))}
                </ul>
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
  resolveZipLabel: (zip: string) => string;
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
  availableNewsSources: CatalogDefaults['defaultNewsSources'];
  selectedNewsSourceIds: string[];
  onToggleNewsSource: (sourceId: string, checked: boolean) => void;
  onRestoreNewsSources: () => void;
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
                <span className="chip-label">{props.resolveZipLabel(zip)}</span>
                <button
                  type="button"
                  className="chip-remove"
                  aria-label={`Remove ${props.resolveZipLabel(zip)}`}
                  onClick={() => props.removeZip(zip)}
                >
                  x
                </button>
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
                <span className="chip-label">{formatMarketSymbolLabel(symbol)}</span>
                <button
                  type="button"
                  className="chip-remove"
                  aria-label={`Remove ${formatMarketSymbolLabel(symbol)}`}
                  onClick={() => props.removeSymbol(symbol)}
                >
                  x
                </button>
              </div>
            ))}
          </div>
        </section>

        <section className="card controls">
          <div className="card-title-row">
            <h2>News Sources ({props.selectedNewsSourceIds.length})</h2>
            <button
              type="button"
              className="link-button"
              onClick={() => {
                props.onRestoreNewsSources();
                setResetHint('');
              }}
            >
              Restore defaults
            </button>
          </div>
          <p className="meta">Choose which sources appear in your news feed.</p>
          <div className="news-source-grid">
            {props.availableNewsSources.map((source) => {
              const checked = props.selectedNewsSourceIds.includes(source.id);
              const disabled = source.requiresConfig === true;
              return (
                <label key={source.id} className={`news-source-option ${disabled ? 'disabled' : ''}`}>
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={disabled}
                    onChange={(event) => props.onToggleNewsSource(source.id, event.target.checked)}
                  />
                  <NewsSourceLogo sourceId={source.id} />
                  <span>{source.name}</span>
                  <span className="meta small">({source.type.toUpperCase()})</span>
                  {disabled && <span className="meta small">{source.note ?? 'Requires additional configuration'}</span>}
                </label>
              );
            })}
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
  passwordRequirement?: string;
  onSubmit: (values: Record<'email' | 'password', string>) => Promise<void>;
};

function AuthPage(props: AuthPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const requiresMinPassword = props.passwordRequirement != null;
  const hasPasswordField = props.fields.some((field) => field.key === 'password');
  const passwordTooShort = requiresMinPassword && hasPasswordField && password.length > 0 && password.length < 8;
  const passwordError = passwordTooShort ? props.passwordRequirement : null;
  const passwordHelper = passwordError ?? props.passwordRequirement ?? null;
  const disableSubmit = pending || (requiresMinPassword && hasPasswordField && password.length < 8);

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
              {passwordHelper && <p className={passwordError ? 'meta inline-hint' : 'meta'}>{passwordHelper}</p>}
            </label>
          )}
          <button type="submit" disabled={disableSubmit}>{pending ? 'Working...' : props.submitLabel}</button>
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
  const status = getErrorStatus(error);
  if (status === 400) {
    return error.message;
  }
  if (status === 401 || error.message.includes('(401)')) {
    return 'Invalid credentials. Please check your email and password.';
  }
  return 'Request failed. Please try again.';
}

function isUnauthorizedError(error: unknown): boolean {
  return getErrorStatus(error) === 401 || (error instanceof Error && error.message.includes('(401)'));
}

function getErrorStatus(error: unknown): number | null {
  if (!(error instanceof Error)) {
    return null;
  }
  const maybeStatus = (error as Error & { status?: unknown }).status;
  return typeof maybeStatus === 'number' ? maybeStatus : null;
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
    const observedAt = toIsoTimestamp(resolveEventTimestampMillis(event, envelope));
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
        locationLabel: asString(event.locationLabel) ?? previous?.locationLabel ?? formatPlaceLabel(zip),
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
    const observedAt = toIsoTimestamp(resolveEventTimestampMillis(event, envelope));
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
        locationLabel: asString(event.locationLabel) ?? previous?.locationLabel ?? formatPlaceLabel(zip),
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
    const millis = value > 10_000_000_000 ? value : value * 1000;
    return new Date(millis).toISOString();
  }
  return new Date().toISOString();
}

function resolveEventTimestampMillis(payload: Record<string, unknown>, envelope: EventEnvelope): number {
  const payloadMillis = asNumber(payload.fetchedAtEpochMillis);
  if (payloadMillis != null && Number.isFinite(payloadMillis)) {
    return payloadMillis;
  }
  return envelope.timestampEpochMillis;
}

function normalizeZip(value: string): string | null {
  const trimmed = value.trim();
  return /^\d{5}$/.test(trimmed) ? trimmed : null;
}

function normalizeZipCodes(values: string[]): string[] {
  const normalized = new Set<string>();
  values.forEach((value) => {
    const zip = normalizeZip(value);
    if (zip) {
      normalized.add(zip);
    }
  });
  return [...normalized];
}

function filterDisplayableHappeningItems(items: LocalHappeningsSignal['items']): LocalHappeningsSignal['items'] {
  const seenTitles = new Set<string>();
  return items.filter((item) => {
    const title = item.name.trim();
    if (title.length === 0 || item.url.trim().length === 0) {
      return false;
    }
    if (seenTitles.has(title)) {
      return false;
    }
    seenTitles.add(title);
    return true;
  });
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

function NewsSourceLogo({ sourceId }: { sourceId: string }) {
  const logo = NEWS_LOGO_BY_SOURCE_ID[sourceId];
  if (!logo) {
    return <span className="news-logo-fallback">{sourceId.toUpperCase()}</span>;
  }
  return <img className="news-logo-img" src={logo.src} alt={logo.alt} loading="lazy" />;
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

function formatInstant(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const millis = value > 10_000_000_000 ? value : value * 1000;
    const parsed = new Date(millis);
    return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString();
  }
  if (typeof value === 'string') {
    const numeric = Number.parseFloat(value);
    if (Number.isFinite(numeric) && /^\d+(\.\d+)?$/.test(value.trim())) {
      const millis = numeric > 10_000_000_000 ? numeric : numeric * 1000;
      const parsed = new Date(millis);
      return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
    }
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
  }
  return String(value);
}
