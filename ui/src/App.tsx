import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import {
  fetchAdminEmailPreview,
  fetchAdminTrends,
  fetchDevOutbox,
  fetchCatalogDefaults,
  fetchCollectorStatus,
  fetchConfigView,
  fetchEnvironment,
  fetchEvents,
  fetchHealth,
  deleteMyAccount,
  fetchMarkets,
  fetchMe,
  fetchMetrics,
  fetchNewsSourceSettings,
  fetchMyPreferences,
  fetchSignals,
  forgotPassword,
  login,
  logout,
  resetSettings,
  resetPassword,
  saveMyPreferences,
  setUnauthorizedHandler,
  signup,
  triggerCollectorRefresh,
  type AuthUserView,
  type DevOutboxEmail
} from './api';
import { demoLocalHappenings } from './demoData';
import { filterEvents, normalizeEventEnvelope } from './eventFeed';
import { companyNameForSymbol, formatMarketSymbolLabel } from './markets';
import { formatPlaceLabel } from './places';
import { apiUrl } from './config/api';
import cnnLogo from './assets/news-logos/cnn.svg';
import foxLogo from './assets/news-logos/fox.svg';
import nprLogo from './assets/news-logos/npr.svg';
import nytLogo from './assets/news-logos/nyt.svg';
import vergeLogo from './assets/news-logos/verge.svg';
import wsjLogo from './assets/news-logos/wsj.svg';
import AdminDashboard from './admin/AdminDashboard';
import AboutPage from './about/AboutPage';
import type {
  CatalogDefaults,
  CollectorStatus,
  EnvStatus,
  EventEnvelope,
  LocalHappeningsSignal,
  AdminEmailPreview,
  AdminTrendsSnapshot,
  MarketQuoteSignal,
  MarketsSnapshot,
  MetricsResponse,
  SignalsSnapshot
} from './models';
import { loadWatchlist, loadZipCodes, saveWatchlist, saveZipCodes } from './preferences';

const MAX_EVENTS = 200;
const MAX_WATCHLIST = 25;
const FALLBACK_DEFAULT_ZIPS = ['02108', '98101'];
const FALLBACK_DEFAULT_WATCHLIST = ['DJIA', '^GSPC', 'ORCL', 'AAPL', 'SBUX', 'HD', 'DIS', 'MSFT', 'AMZN', 'BTC-USD', 'NFLX'];
const FALLBACK_DEFAULT_NEWS_SOURCES = ['cnn', 'wsj', 'verge'];
const DEFAULT_THEME_MODE = 'light';
const DEFAULT_ACCENT = 'blue';
const AUTH_TRANSITION_COLLECTORS = ['envCollector', 'rssCollector', 'localEventsCollector'];
const INTENDED_ROUTE_KEY = 'todays-overview:intended-route';
const NEWS_DEBUG_STORAGE_KEY = 'todays-overview:debug-news';
const NEWS_SOURCE_ICON_DOMAIN_OVERRIDES_BY_ID: Record<string, string> = {
  abc: 'abcnews.go.com',
  cbs: 'cbsnews.com',
  cnn: 'cnn.com',
  fox: 'foxnews.com',
  nbc: 'nbcnews.com',
  npr_morning_edition: 'npr.org',
  npr_news_now: 'npr.org',
  npr_politics: 'npr.org',
  nyt: 'nytimes.com',
  nyt_most_popular: 'nytimes.com',
  reuters: 'reuters.com',
  wsj: 'wsj.com'
};
const NEWS_SOURCE_ICON_DOMAIN_OVERRIDES_BY_LABEL: Record<string, string> = {
  'abc news': 'abcnews.go.com',
  'abcnews': 'abcnews.go.com',
  'cbs news': 'cbsnews.com',
  cnn: 'cnn.com',
  fox: 'foxnews.com',
  'fox news': 'foxnews.com',
  'new york times': 'nytimes.com',
  'nyt': 'nytimes.com',
  'nyt most popular': 'nytimes.com',
  'npr news now': 'npr.org',
  'npr news now (breaking)': 'npr.org',
  'npr politics': 'npr.org',
  'npr morning edition': 'npr.org',
  'reuters': 'reuters.com',
  'wall street journal': 'wsj.com'
};
const WRAPPER_QUERY_KEYS = ['url', 'u', 'uri', 'link', 'dest', 'destination', 'rurl', 'sourceUrl', 'next'] as const;
const GENERIC_ICON_HOSTS = new Set(['www.google.com', 'google.com', 'gstatic.com', 'img.youtube.com']);
const KNOWN_SSE_TYPES = [
  'CollectorTickStarted',
  'CollectorTickCompleted',
  'AlertRaised',
  'SiteFetched',
  'ContentChanged',
  'EnvWeatherUpdated',
  'EnvAqiUpdated',
  'NewsUpdated',
  'NewsItemsIngested',
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

type RouteName = 'home' | 'settings' | 'admin' | 'auth' | 'about' | 'forgot' | 'reset';
type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed';
type ThemeMode = 'light' | 'dark';
type Accent = 'default' | 'gold' | 'blue' | 'green' | 'red' | 'orange' | 'yellow' | 'purple' | 'pink' | 'white' | 'lightGray' | 'darkGray' | 'black';

const emptySnapshot: SignalsSnapshot = { sites: {}, news: {}, weather: {} };
const emptyMarketsSnapshot: MarketsSnapshot = { status: 'ok', asOf: '', items: [] };
const emptyAdminTrends: AdminTrendsSnapshot = { windowStart: '', bucketSeconds: 300, series: [] };
const emptyAdminEmailPreview: AdminEmailPreview = {
  enabled: false,
  mode: 'n/a',
  lastSentAt: null,
  lastError: null,
  generatedAt: '',
  subject: '',
  body: '',
  includedCounts: { sites: 0, newsStories: 0, localEvents: 0, weather: 0, markets: 0 }
};
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
  const [themeMode, setThemeMode] = useState<ThemeMode>(DEFAULT_THEME_MODE);
  const [accent, setAccent] = useState<Accent>(DEFAULT_ACCENT);
  const [authUser, setAuthUser] = useState<AuthUserView | null>(null);
  const [authResolved, setAuthResolved] = useState<boolean>(false);
  const [sessionExpired, setSessionExpired] = useState<boolean>(false);
  const [devOutbox, setDevOutbox] = useState<DevOutboxEmail[]>([]);
  const [authMessage, setAuthMessage] = useState<string | null>(null);
  const [forgotMessage, setForgotMessage] = useState<string | null>(null);
  const [authMode, setAuthMode] = useState<'login' | 'signup'>('login');
  const [versionInfo, setVersionInfo] = useState<{ version?: string; buildTime?: string; gitSha?: string }>({});
  const [settingsSaveState, setSettingsSaveState] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [marketsSnapshot, setMarketsSnapshot] = useState<MarketsSnapshot>(emptyMarketsSnapshot);
  const [marketsLoading, setMarketsLoading] = useState<boolean>(false);
  const [marketsError, setMarketsError] = useState<string | null>(null);
  const [signalsLoading, setSignalsLoading] = useState<boolean>(true);
  const [adminTrends, setAdminTrends] = useState<AdminTrendsSnapshot>(emptyAdminTrends);
  const [adminTrendsLoading, setAdminTrendsLoading] = useState<boolean>(false);
  const [adminTrendsError, setAdminTrendsError] = useState<string | null>(null);
  const [adminEmailPreview, setAdminEmailPreview] = useState<AdminEmailPreview>(emptyAdminEmailPreview);
  const [adminEmailLoading, setAdminEmailLoading] = useState<boolean>(false);
  const [adminEmailError, setAdminEmailError] = useState<string | null>(null);
  const [zipInput, setZipInput] = useState<string>('');
  const [symbolInput, setSymbolInput] = useState<string>('');
  const [userMenuOpen, setUserMenuOpen] = useState<boolean>(false);
  const [mobileNavOpen, setMobileNavOpen] = useState<boolean>(false);
  const newsDebugEnabled = useMemo(() => isNewsDebugEnabled(), []);
  const logNewsDebug = useCallback((message: string, data?: Record<string, unknown>) => {
    if (!newsDebugEnabled) {
      return;
    }
    if (data) {
      console.info('[news-debug]', message, data);
    } else {
      console.info('[news-debug]', message);
    }
  }, [newsDebugEnabled]);

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const newsRefreshTimerRef = useRef<number | null>(null);
  const retryDelayRef = useRef<number>(1000);
  const pausedRef = useRef<boolean>(false);
  const settingsSavedTimerRef = useRef<number | null>(null);
  const previousRouteRef = useRef<RouteName>(route);
  const userMenuRef = useRef<HTMLDivElement | null>(null);
  const authRefreshTimerRef = useRef<number | null>(null);
  const lastSavedZipCodesRef = useRef<string[]>(zipCodes);

  const revalidateAuth = useCallback(async () => {
    try {
      const maybeMe = await fetchMe();
      if (maybeMe) {
        setAuthUser(maybeMe);
        setSessionExpired(false);
      } else {
        handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
      }
    } catch {
      // ignore revalidation errors
    }
  }, []);

  useEffect(() => {
    const onHashChange = () => setRoute(readRouteFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
    });
    return () => {
      setUnauthorizedHandler(null);
    };
  }, []);

  useEffect(() => {
    if (route !== 'auth') {
      return;
    }
    const hash = window.location.hash;
    if (hash === '#/signup' || hash.includes('mode=signup')) {
      setAuthMode('signup');
    } else {
      setAuthMode('login');
    }
  }, [route]);

  useEffect(() => {
    if (route !== 'auth') {
      setAuthMessage(null);
    }
    if (route !== 'forgot') {
      setForgotMessage(null);
    }
  }, [route]);

  useEffect(() => {
    if (!userMenuOpen) {
      return;
    }
    const handleClick = (event: MouseEvent) => {
      if (!userMenuRef.current) {
        return;
      }
      if (!userMenuRef.current.contains(event.target as Node)) {
        setUserMenuOpen(false);
      }
    };
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setUserMenuOpen(false);
      }
    };
    window.addEventListener('mousedown', handleClick);
    window.addEventListener('keydown', handleKey);
    return () => {
      window.removeEventListener('mousedown', handleClick);
      window.removeEventListener('keydown', handleKey);
    };
  }, [userMenuOpen]);

  useEffect(() => {
    setMobileNavOpen(false);
  }, [route]);

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
      setSignalsLoading(true);
      try {
        const maybeMe = fetchMe().catch((error) => {
          if (isUnauthorizedError(error)) {
            return null;
          }
          throw error;
        });
        const [healthResponse, signalsResponse, eventsResponse, metricsResponse, statusResponse, defaultsResponse, configResponse, meResponse, outboxResponse, newsSourceSettings] = await Promise.all([
          fetchHealth(),
          fetchSignals(),
          fetchEvents(100),
          fetchMetrics(),
          fetchCollectorStatus(),
          fetchCatalogDefaults(),
          fetchConfigView(),
          maybeMe,
          fetchDevOutbox(),
          fetchNewsSourceSettings()
        ]);
        setVersionInfo({
          version: healthResponse.version,
          buildTime: healthResponse.buildTime,
          gitSha: healthResponse.gitSha
        });
        if (!mounted) {
          return;
        }
        setHealth(healthResponse.status);
        setSnapshot(signalsResponse);
        logNewsDebug('bootstrap snapshot', {
          newsSourceCount: Object.keys(signalsResponse.news ?? {}).length,
          newsSources: Object.keys(signalsResponse.news ?? {}).slice(0, 12)
        });
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
        logNewsDebug('bootstrap news defaults', {
          defaultsNewsSources: defaultsResponse.defaultNewsSources.length,
          defaultsSelectedNewsSources: defaultsResponse.defaultSelectedNewsSources ?? [],
          settingsAvailableSources: newsSourceSettings.availableSources.length,
          settingsEffectiveSelectedSources: newsSourceSettings.effectiveSelectedSources
        });
        setConfigView(configResponse);
        setAuthUser(meResponse);
        setSessionExpired(false);
        setDevOutbox(outboxResponse);
        setSelectedNewsSourceIds(newsSourceSettings.effectiveSelectedSources.length > 0
          ? newsSourceSettings.effectiveSelectedSources
          : (defaultsResponse.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES));
        logNewsDebug('bootstrap selected news sources', {
          selectedNewsSourceIds: newsSourceSettings.effectiveSelectedSources.length > 0
            ? newsSourceSettings.effectiveSelectedSources
            : (defaultsResponse.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES)
        });
        if (meResponse) {
          try {
            const prefs = await fetchMyPreferences();
            setZipCodes(prefs.zipCodes);
            lastSavedZipCodesRef.current = prefs.zipCodes;
            setWatchlist(prefs.watchlist);
            setSelectedNewsSourceIds(
              prefs.newsSourceIds.length > 0
                ? prefs.newsSourceIds
                : (newsSourceSettings.effectiveSelectedSources.length > 0
                  ? newsSourceSettings.effectiveSelectedSources
                  : (defaultsResponse.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES))
            );
            setThemeMode(prefs.themeMode);
            setAccent(prefs.accent);
            logNewsDebug('bootstrap user preferences', {
              newsSourceIds: prefs.newsSourceIds,
              zipCodes: prefs.zipCodes,
              watchlist: prefs.watchlist
            });
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
      } finally {
        if (mounted) {
          setSignalsLoading(false);
        }
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
    if (route !== 'admin') {
      return;
    }
    if (!authUser) {
      setAdminTrends(emptyAdminTrends);
      setAdminTrendsError(null);
      setAdminEmailPreview(emptyAdminEmailPreview);
      setAdminEmailError(null);
      return;
    }

    let cancelled = false;
    const loadAdminData = async (): Promise<void> => {
      setAdminTrendsLoading(true);
      setAdminEmailLoading(true);
      try {
        const [trends, preview] = await Promise.all([fetchAdminTrends(), fetchAdminEmailPreview()]);
        if (cancelled) {
          return;
        }
        setAdminTrends(trends);
        setAdminTrendsError(null);
        setAdminEmailPreview(preview);
        setAdminEmailError(null);
      } catch (err) {
        if (cancelled) {
          return;
        }
        const message = err instanceof Error ? err.message : 'Failed to load admin diagnostics';
        setAdminTrendsError(message);
        setAdminEmailError(message);
      } finally {
        if (!cancelled) {
          setAdminTrendsLoading(false);
          setAdminEmailLoading(false);
        }
      }
    };

    void loadAdminData();
    return () => {
      cancelled = true;
    };
  }, [route, authUser]);

  useEffect(() => {
    if (authResolved && route === 'settings' && !authUser) {
      storeIntendedRoute('#/settings');
      window.location.hash = '#/auth?mode=login';
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
      const source = new EventSource(apiUrl('/api/stream'), { withCredentials: true });
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
            if (newsDebugEnabled) {
              const event = normalized.event as { source?: string; storyCount?: number };
              logNewsDebug('sse NewsUpdated', {
                source: event?.source,
                storyCount: event?.storyCount,
                timestampEpochMillis: normalized.timestampEpochMillis
              });
            }
            newsRefreshTimerRef.current = window.setTimeout(async () => {
              newsRefreshTimerRef.current = null;
              try {
                const refreshedSignals = await fetchSignals();
                setSnapshot(refreshedSignals);
                logNewsDebug('news refresh snapshot', {
                  newsSourceCount: Object.keys(refreshedSignals.news ?? {}).length,
                  newsSources: Object.keys(refreshedSignals.news ?? {}).slice(0, 12)
                });
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
        if (authUser) {
          void revalidateAuth();
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
      if (authRefreshTimerRef.current !== null) {
        window.clearTimeout(authRefreshTimerRef.current);
      }
      if (newsRefreshTimerRef.current !== null) {
        window.clearTimeout(newsRefreshTimerRef.current);
      }
      eventSourceRef.current?.close();
    };
  }, [authUser, revalidateAuth]);

  useEffect(() => {
    if (authRefreshTimerRef.current !== null) {
      window.clearTimeout(authRefreshTimerRef.current);
    }
    authRefreshTimerRef.current = window.setTimeout(() => {
      void revalidateAuth();
    }, 15 * 60 * 1000);
    return () => {
      if (authRefreshTimerRef.current !== null) {
        window.clearTimeout(authRefreshTimerRef.current);
      }
    };
  }, [revalidateAuth]);

  useEffect(() => {
    if (eventFeedPaused || pausedEventBuffer.length === 0) {
      return;
    }
    setEvents((previous) => [...previous, ...pausedEventBuffer].slice(-MAX_EVENTS));
    setPausedEventBuffer([]);
  }, [eventFeedPaused, pausedEventBuffer]);

  useEffect(() => {
    if (!newsDebugEnabled) {
      return;
    }
    const newsKeys = Object.keys(snapshot.news ?? {});
    const missingSelected = selectedNewsSourceIds.filter((id) => !newsKeys.includes(id));
    logNewsDebug('news selection vs snapshot', {
      selectedNewsSourceIds,
      selectedCount: selectedNewsSourceIds.length,
      snapshotNewsCount: newsKeys.length,
      snapshotNewsSources: newsKeys.slice(0, 12),
      missingSelected
    });
  }, [newsDebugEnabled, snapshot.news, selectedNewsSourceIds, logNewsDebug]);

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
  const newsSourceUrls = useMemo(() => {
    return new Map(
      catalogDefaults.defaultNewsSources.map((source) => [source.id, source.url])
    );
  }, [catalogDefaults.defaultNewsSources]);
  const marketEntries = useMemo(() => marketsSnapshot.items, [marketsSnapshot.items]);
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

  useEffect(() => {
    let cancelled = false;
    let timer: number | null = null;
    const effectiveSymbols = watchlist.map((value) => value.trim().toUpperCase()).filter((value) => value.length > 0);

    async function loadMarkets(): Promise<void> {
      setMarketsLoading(true);
      try {
        const payload = await fetchMarkets(effectiveSymbols);
        if (cancelled) {
          return;
        }
        setMarketsSnapshot(payload);
        setMarketsError(null);
      } catch (err) {
        if (cancelled) {
          return;
        }
        setMarketsError(err instanceof Error ? err.message : 'Markets unavailable');
      } finally {
        if (!cancelled) {
          setMarketsLoading(false);
        }
      }
    }

    loadMarkets();
    timer = window.setInterval(loadMarkets, 60_000);
    return () => {
      cancelled = true;
      if (timer !== null) {
        window.clearInterval(timer);
      }
    };
  }, [watchlist]);

  useEffect(() => {
    document.documentElement.dataset.theme = themeMode;
    document.documentElement.dataset.accent = accent;
  }, [themeMode, accent]);

  const addZip = (): ZipAddResult => {
    const parsed = parseZipInput(zipInput);
    let limitHit = false;
    const added: string[] = [];
    const duplicates: string[] = [];
    setZipCodes((previous) => {
      const next = [...previous];
      parsed.valid.forEach((zip) => {
        if (next.includes(zip)) {
          duplicates.push(zip);
          return;
        }
        if (next.length >= 10) {
          limitHit = true;
          return;
        }
        next.push(zip);
        added.push(zip);
      });
      return next;
    });
    setZipInput('');
    return { added, duplicates, invalid: parsed.invalid, limitHit };
  };

  const addSymbol = (): SymbolAddResult => {
    const parsed = parseSymbolInput(symbolInput);
    let limitHit = false;
    const added: string[] = [];
    const duplicates: string[] = [];
    setWatchlist((previous) => {
      const next = [...previous];
      parsed.valid.forEach((symbol) => {
        if (next.includes(symbol)) {
          duplicates.push(symbol);
          return;
        }
        if (next.length >= MAX_WATCHLIST) {
          limitHit = true;
          return;
        }
        next.push(symbol);
        added.push(symbol);
      });
      return next;
    });
    setSymbolInput('');
    return { added, duplicates, invalid: parsed.invalid, limitHit };
  };

  const reorderByValue = <T extends string>(values: T[], source: T, target: T): T[] => {
    const sourceIndex = values.indexOf(source);
    const targetIndex = values.indexOf(target);
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
      return values;
    }
    const next = [...values];
    const [moved] = next.splice(sourceIndex, 1);
    next.splice(targetIndex, 0, moved);
    return next;
  };

  const resetUiPreferences = async (): Promise<'ok' | 'error'> => {
    try {
      const response = await resetSettings('ui');
      setThemeMode(response.preferences.themeMode);
      setAccent(response.preferences.accent);
      setZipInput('');
      setSymbolInput('');
      return 'ok';
    } catch {
      return 'error';
    }
  };

  const resetCollectorDefaults = async (): Promise<'ok' | 'error'> => {
    try {
      const response = await resetSettings('collectors');
      setZipCodes(response.preferences.zipCodes);
      setWatchlist(response.preferences.watchlist);
      setSelectedNewsSourceIds(
        response.preferences.newsSourceIds.length > 0
          ? response.preferences.newsSourceIds
          : (catalogDefaults.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES)
      );
      setThemeMode(response.preferences.themeMode);
      setAccent(response.preferences.accent);
      setZipInput('');
      setSymbolInput('');
      return 'ok';
    } catch {
      return 'error';
    }
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
      const zipChanged = zipCodes.join(',') !== lastSavedZipCodesRef.current.join(',');
      saveMyPreferences({
        zipCodes,
        watchlist,
        newsSourceIds: selectedNewsSourceIds,
        themeMode,
        accent
      }).then(() => {
      if (zipChanged) {
        lastSavedZipCodesRef.current = [...zipCodes];
        void refreshAfterZipSave(zipCodes);
      }
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
  }, [authUser, zipCodes, watchlist, selectedNewsSourceIds, themeMode, accent]);

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

  const refreshAfterZipSave = async (targetZips: string[]) => {
    try {
      await triggerCollectorRefresh(['envCollector', 'localEventsCollector']);
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
      setThemeMode(DEFAULT_THEME_MODE);
      setAccent(DEFAULT_ACCENT);
      await refreshAfterAuthTransition(nextZips);
      window.location.hash = '#/';
    } catch (err) {
      setAuthMessage(err instanceof Error ? err.message : 'Sign out failed');
    }
  };

  const deleteAccount = async (): Promise<'ok' | 'error'> => {
    try {
      await deleteMyAccount();
      setAuthUser(null);
      setSessionExpired(false);
      setAuthMessage(null);
      setZipCodes(loadZipCodes());
      setWatchlist(loadWatchlist());
      setSelectedNewsSourceIds(catalogDefaults.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES);
      setThemeMode(DEFAULT_THEME_MODE);
      setAccent(DEFAULT_ACCENT);
      window.location.hash = '#/';
      return 'ok';
    } catch (error) {
      if (isUnauthorizedError(error)) {
        handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
      }
      return 'error';
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-container">
          <div className="card header-card">
            <div className="header-top">
              <h1>Today&apos;s Overview</h1>
              <button
                className="mobile-nav-button"
                type="button"
                aria-label={mobileNavOpen ? 'Close navigation menu' : 'Open navigation menu'}
                aria-expanded={mobileNavOpen}
                aria-controls="primary-nav"
                onClick={() => setMobileNavOpen((open) => !open)}
              >
                <span aria-hidden="true">☰</span>
              </button>
              <nav
                id="primary-nav"
                className={`nav${mobileNavOpen ? ' nav-open' : ''}`}
                onClick={(event) => {
                  const target = event.target as HTMLElement;
                  if (target.closest('a')) {
                    setMobileNavOpen(false);
                  }
                }}
              >
                <a href="#/" className={route === 'home' ? 'active' : ''}>Home</a>
                {authUser && <a href="#/settings" className={route === 'settings' ? 'active' : ''}>Settings</a>}
                <a href="#/admin" className={route === 'admin' ? 'active' : ''}>Admin / Diagnostics</a>
                <a href="#/about" className={route === 'about' ? 'active' : ''}>About</a>
              {!authUser && <a href="#/auth" className={route === 'auth' ? 'active' : ''}>Sign In</a>}
                {authUser && (
                  <div className="user-menu" ref={userMenuRef}>
                    <button
                      type="button"
                      className="user-menu-button"
                      aria-haspopup="menu"
                      aria-expanded={userMenuOpen}
                      aria-label="Open user menu"
                      onClick={() => setUserMenuOpen((open) => !open)}
                    >
                      <span className="user-menu-icon" aria-hidden="true">👤</span>
                      <span className="user-menu-caret" aria-hidden="true">▾</span>
                    </button>
                    {userMenuOpen && (
                      <div className="user-menu-panel" role="menu">
                        <p className="user-menu-label">Logged in as</p>
                        <p className="user-menu-email">{authUser.email}</p>
                        <div className="user-menu-divider" />
                        <button
                          type="button"
                          className="user-menu-item"
                          onClick={async () => {
                            setUserMenuOpen(false);
                            await doSignOut();
                          }}
                        >
                          Log out
                        </button>
                        <button
                          type="button"
                          className="user-menu-item destructive"
                          onClick={async () => {
                            if (!window.confirm('Delete your account? This cannot be undone.')) {
                              return;
                            }
                            setUserMenuOpen(false);
                            await deleteAccount();
                          }}
                        >
                          Delete account
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </nav>
            </div>
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
            resolveNewsSourceUrl={(sourceId) => newsSourceUrls.get(sourceId)}
            happeningsEntries={happeningsEntries}
            happeningsAttribution={happeningsAttribution}
            marketEntries={marketEntries}
            newsLoading={signalsLoading}
            happeningsLoading={signalsLoading}
            marketsLoading={marketsLoading}
            marketsError={marketsError}
            marketsAsOf={marketsSnapshot.asOf}
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
            reorderZip={(sourceZip, targetZip) => setZipCodes((previous) => reorderByValue(previous, sourceZip, targetZip))}
            watchlist={watchlist}
            symbolInput={symbolInput}
            setSymbolInput={setSymbolInput}
            addSymbol={addSymbol}
            maxWatchlist={MAX_WATCHLIST}
            saveState={settingsSaveState}
            removeSymbol={(symbol) => setWatchlist((previous) => previous.filter((value) => value !== symbol))}
            reorderSymbol={(sourceSymbol, targetSymbol) => setWatchlist((previous) => reorderByValue(previous, sourceSymbol, targetSymbol))}
            onRestorePlaces={restorePlacesDefaults}
            onRestoreWatchlist={restoreWatchlistDefaults}
            availableNewsSources={catalogDefaults.defaultNewsSources}
            selectedNewsSourceIds={selectedNewsSourceIds}
            themeMode={themeMode}
            accent={accent}
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
            onUpdateNewsSources={(next) => setSelectedNewsSourceIds(next)}
            onRestoreNewsSources={restoreNewsSourceDefaults}
            onThemeModeChange={setThemeMode}
            onAccentChange={setAccent}
            onResetUiPreferences={resetUiPreferences}
            onDeleteAccount={deleteAccount}
          />
          ) : authResolved ? (
            <AuthPage
              title="Login required"
              description="Sign in to manage server-side settings."
              submitLabel="Go to Login"
              fields={[]}
              onSubmit={async () => {
                window.location.hash = '#/auth?mode=login';
              }}
            />
          ) : (
            <main className="settings-page"><section className="card"><p className="meta">Loading account...</p></section></main>
          )
        ) : route === 'auth' ? (
          <UnifiedAuthPage
            mode={authMode}
            authMessage={authMessage}
            onClearAuthMessage={() => setAuthMessage(null)}
            onModeChange={(mode) => {
              setAuthMode(mode);
              setAuthMessage(null);
              window.location.hash = `#/auth?mode=${mode}`;
            }}
            onLogin={async (values) => {
              const user = await login(values.email, values.password);
              setAuthUser(user);
              setSessionExpired(false);
              setAuthMessage(null);
              const destination = consumeIntendedRoute();
              window.location.hash = destination;
              void (async () => {
                try {
                  const prefs = await fetchMyPreferences();
                  setZipCodes(prefs.zipCodes);
                  lastSavedZipCodesRef.current = prefs.zipCodes;
                  setWatchlist(prefs.watchlist);
                  setSelectedNewsSourceIds(
                    prefs.newsSourceIds.length > 0
                      ? prefs.newsSourceIds
                      : (catalogDefaults.defaultSelectedNewsSources ?? FALLBACK_DEFAULT_NEWS_SOURCES)
                  );
                  setThemeMode(prefs.themeMode);
                  setAccent(prefs.accent);
                  await refreshAfterAuthTransition(prefs.zipCodes);
                } catch (error) {
                  if (isUnauthorizedError(error)) {
                    handleSessionExpired(setAuthUser, setSessionExpired, setAuthMessage);
                  }
                }
              })();
            }}
            onSignup={async (values) => {
              const user = await signup(values.email, values.password);
              setAuthUser(user);
              setSessionExpired(false);
              setAuthMessage(null);
              window.location.hash = consumeIntendedRoute();
            }}
          />
        ) : route === 'about' ? (
          <AboutPage />
        ) : route === 'forgot' ? (
          <AuthPage
            title="Forgot password"
            description="Enter your email and we will send reset instructions."
            submitLabel="Send reset link"
            fields={[{ key: 'email', label: 'Email', type: 'email' }]}
            notice={forgotMessage}
            onBeforeSubmit={() => setForgotMessage(null)}
            onCancel={() => setForgotMessage(null)}
            onSubmit={async (values) => {
              await forgotPassword(values.email);
              setForgotMessage('Password reset is still under construction for this demo. Please contact the project author if you need help accessing your account.');
            }}
          />
        ) : route === 'reset' ? (
          <AuthPage
            title="Reset password"
            description="Set your new password."
            submitLabel="Reset password"
            fields={[{ key: 'password', label: 'New password', type: 'password' }]}
            onBeforeSubmit={() => setAuthMessage(null)}
            onSubmit={async (values) => {
              const token = readResetTokenFromHash();
              if (!token) {
                throw new Error('Reset token missing');
              }
              await resetPassword(token, values.password);
              setAuthMessage('Password reset complete. You can now log in.');
              window.location.hash = '#/auth?mode=login';
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
            selectedNewsSourceIds={selectedNewsSourceIds}
            newsSnapshot={snapshot.news}
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
            trends={adminTrends}
            trendsLoading={adminTrendsLoading}
            trendsError={adminTrendsError}
            emailPreview={adminEmailPreview}
            emailPreviewLoading={adminEmailLoading}
            emailPreviewError={adminEmailError}
            versionInfo={versionInfo}
          />
        )}
      </div>
      <footer className="app-footer">
        <div className="app-container">
          <p className="meta">Today&apos;s Overview • v{versionInfo.version ?? 'dev'}</p>
        </div>
      </footer>
    </div>
  );
}

type HomePageProps = {
  zipCodes: string[];
  envByZip: Record<string, EnvStatus>;
  newsEntries: SignalsSnapshot['news'][string][];
  resolveNewsSourceLabel: (sourceId: string) => string;
  resolveNewsSourceUrl: (sourceId: string) => string | undefined;
  happeningsEntries: LocalHappeningsSignal[];
  happeningsAttribution: string;
  marketEntries: MarketQuoteSignal[];
  newsLoading: boolean;
  happeningsLoading: boolean;
  marketsLoading: boolean;
  marketsError: string | null;
  marketsAsOf: string;
};

function HomePage(props: HomePageProps) {
  const sortedWeatherZips = [...props.zipCodes].sort((a, b) => {
    const normalizedA = normalizeZip(a) ?? a;
    const normalizedB = normalizeZip(b) ?? b;
    const labelA = props.envByZip[normalizedA]?.locationLabel ?? formatPlaceLabel(normalizedA);
    const labelB = props.envByZip[normalizedB]?.locationLabel ?? formatPlaceLabel(normalizedB);
    return compareLocationLabels(labelA, labelB);
  });

  const sortedHappenings = [...props.happeningsEntries].sort((a, b) => {
    const labelA = a.location.startsWith('lat:') ? 'Local area' : formatPlaceLabel(a.location);
    const labelB = b.location.startsWith('lat:') ? 'Local area' : formatPlaceLabel(b.location);
    return compareLocationLabels(labelA, labelB);
  });

  return (
    <main>
      <section className="primary-grid">
        <section className="card weather">
          <h2>Weather & Air Quality</h2>
          {props.zipCodes.length === 0 ? (
            <p className="empty">No ZIP codes selected. Add places in Settings.</p>
          ) : (
            <div className="card-body">
              <table className="weather-table">
                <thead>
                  <tr>
                    <th>City</th>
                    <th>Current Weather</th>
                    <th>Air Quality</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedWeatherZips.map((zip) => {
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
            {props.newsLoading ? (
              <div className="skeleton-block">
                <div className="skeleton-line" />
                <div className="skeleton-line short" />
                <div className="skeleton-line" />
              </div>
            ) : null}
            {!props.newsLoading && props.newsEntries.length === 0 ? (
              <p className="empty">No news items available from your selected sources.</p>
            ) : null}
            {!props.newsLoading && props.newsEntries.map((source) => (
              <article key={source.source} className="item">
                <h3 className="news-source-heading">
                  <NewsSourceFavicon
                    sourceId={source.source}
                    sourceLabel={props.resolveNewsSourceLabel(source.source)}
                    sourceUrl={props.resolveNewsSourceUrl(source.source)}
                  />
                  <span>{props.resolveNewsSourceLabel(source.source)}</span>
                </h3>
                {source.stories.length === 0 ? <p className="empty">No stories in current snapshot.</p> : (
                  <ul className="news-list top-news">
                    {source.stories.slice(0, 5).map((story, idx) => (
                      <li key={`${source.source}-${idx}`} className="content-row">
                        <StoryLink title={story.title} href={story.link} />
                      </li>
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
            {props.happeningsLoading ? (
              <div className="skeleton-block">
                <div className="skeleton-line" />
                <div className="skeleton-line short" />
                <div className="skeleton-line" />
              </div>
            ) : null}
            {!props.happeningsLoading && props.happeningsEntries.length === 0 ? (
              <p className="empty">No local events found for your ZIP codes yet.</p>
            ) : null}
            {!props.happeningsLoading && sortedHappenings.map((entry) => (
              <article key={entry.location} className="item">
                <h3>{entry.location.startsWith('lat:') ? 'Local area' : formatPlaceLabel(entry.location)}</h3>
                <ul>
                  {filterDisplayableHappeningItems(entry.items).map((item) => (
                    <li key={item.id} className="content-row">
                      <ContentLink title={item.name} href={item.url} />
                    </li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </section>

        <section className="card">
          <h2>Markets</h2>
          <p className="meta section-description">As of {props.marketsLoading ? <span className="skeleton skeleton-meta" /> : formatInstant(props.marketsAsOf)}</p>
          <div className="card-body">
            {props.marketsLoading ? (
              <div className="skeleton-chart" role="presentation">
                <div className="skeleton-line" />
                <div className="skeleton-line short" />
                <div className="skeleton-line" />
                <div className="skeleton-line short" />
              </div>
            ) : null}
            {!props.marketsLoading && props.marketsError ? <p className="empty">Market data unavailable: {props.marketsError}</p> : null}
            {!props.marketsLoading && !props.marketsError && props.marketEntries.length === 0 ? <p className="empty">No market data available right now.</p> : null}
            {!props.marketsLoading && !props.marketsError && props.marketEntries.map((entry) => (
              <article key={entry.symbol} className="item market-item">
                <a
                  href={buildMarketHref(entry.symbol)}
                  target="_blank"
                  rel="noreferrer"
                  className="market-row-link"
                  aria-label={`${entry.symbol} details`}
                >
                  <div className="market-left">
                    <h3>{entry.symbol}</h3>
                    <p className="market-company">{companyNameForSymbol(entry.symbol)}</p>
                    <svg className={`market-sparkline ${entry.change >= 0 ? 'up' : 'down'}`} viewBox="0 0 72 20" aria-hidden="true" focusable="false">
                      <polyline points={sparklinePoints(entry.symbol, entry.change)} />
                    </svg>
                  </div>
                  <div className="market-right">
                    <p className="market-price">${entry.price.toFixed(2)}</p>
                    <span className={`market-change-chip ${entry.change > 0 ? 'positive' : entry.change < 0 ? 'negative' : 'flat'}`}>
                      {entry.change > 0 ? '+' : ''}{entry.change.toFixed(2)}
                    </span>
                  </div>
                </a>
              </article>
            ))}
          </div>
        </section>
      </section>
    </main>
  );
}

function StoryLink({ title, href }: { title: string; href?: string }) {
  if (!href || !isValidHttpUrl(href)) {
    return <span>{title}</span>;
  }
  return (
    <a href={href} target="_blank" rel="noreferrer">
      {title}
    </a>
  );
}

function ContentLink({ title, href }: { title: string; href?: string }) {
  if (!href || !isValidHttpUrl(href)) {
    return <span>{title}</span>;
  }
  return (
    <a href={href} target="_blank" rel="noreferrer">
      {title}
    </a>
  );
}

function buildMarketHref(symbol: string): string {
  const encoded = encodeURIComponent(symbol.trim());
  return `https://finance.yahoo.com/quote/${encoded}`;
}

function isValidHttpUrl(value: string): boolean {
  const trimmed = value.trim();
  return /^https?:\/\//i.test(trimmed);
}

type ZipAddResult = {
  added: string[];
  duplicates: string[];
  invalid: string[];
  limitHit: boolean;
};

type SymbolAddResult = {
  added: string[];
  duplicates: string[];
  invalid: string[];
  limitHit: boolean;
};

type SettingsPageProps = {
  zipCodes: string[];
  resolveZipLabel: (zip: string) => string;
  zipInput: string;
  setZipInput: Dispatch<SetStateAction<string>>;
  addZip: () => ZipAddResult;
  removeZip: (zip: string) => void;
  reorderZip: (sourceZip: string, targetZip: string) => void;
  watchlist: string[];
  symbolInput: string;
  setSymbolInput: Dispatch<SetStateAction<string>>;
  addSymbol: () => SymbolAddResult;
  maxWatchlist: number;
  saveState: 'idle' | 'saving' | 'saved';
  removeSymbol: (symbol: string) => void;
  reorderSymbol: (sourceSymbol: string, targetSymbol: string) => void;
  onRestorePlaces: () => void;
  onRestoreWatchlist: () => void;
  availableNewsSources: CatalogDefaults['defaultNewsSources'];
  selectedNewsSourceIds: string[];
  themeMode: ThemeMode;
  accent: Accent;
  onToggleNewsSource: (sourceId: string, checked: boolean) => void;
  onUpdateNewsSources: (next: string[]) => void;
  onRestoreNewsSources: () => void;
  onThemeModeChange: (value: ThemeMode) => void;
  onAccentChange: (value: Accent) => void;
  onResetUiPreferences: () => Promise<'ok' | 'error'>;
  onDeleteAccount: () => Promise<'ok' | 'error'>;
};

function SettingsPage(props: SettingsPageProps) {
  const zipParse = parseZipInput(props.zipInput);
  const symbolParse = parseSymbolInput(props.symbolInput);
  const zipHasValid = zipParse.valid.length > 0;
  const zipHasInvalid = zipParse.invalid.length > 0;
  const symbolHasValid = symbolParse.valid.length > 0;
  const symbolHasInvalid = symbolParse.invalid.length > 0;
  const [zipHint, setZipHint] = useState<string>('');
  const [symbolHint, setSymbolHint] = useState<string>('');
  const [deleteHint, setDeleteHint] = useState<string>('');
  const [draggingZip, setDraggingZip] = useState<string | null>(null);
  const [draggingSymbol, setDraggingSymbol] = useState<string | null>(null);
  const selectableSourceIds = props.availableNewsSources
    .filter((source) => source.requiresConfig !== true)
    .map((source) => source.id);
  const selectedSelectableCount = selectableSourceIds.filter((id) => props.selectedNewsSourceIds.includes(id)).length;
  const allSourcesSelected = selectableSourceIds.length > 0 && selectedSelectableCount === selectableSourceIds.length;

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
              if (result.invalid.length > 0) {
                setZipHint(`Invalid ZIPs: ${result.invalid.join(', ')}`);
              } else if (result.added.length === 0 && result.duplicates.length === 0 && !result.limitHit) {
                setZipHint('Enter 5-digit ZIPs separated by commas.');
              } else if (result.added.length === 0 && result.duplicates.length > 0) {
                setZipHint('Already added');
              } else if (result.limitHit && result.added.length === 0) {
                setZipHint('You can add up to 10 ZIP codes');
              } else if (result.added.length > 0 && result.duplicates.length > 0) {
                setZipHint(`Added ${result.added.length}, skipped ${result.duplicates.length} duplicate`);
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
                props.setZipInput(e.target.value.replace(/[^\d,\s]/g, ''));
                setZipHint('');
              }}
              placeholder="ZIPs (e.g., 02108, 98101)"
            />
            <button type="submit" disabled={!zipHasValid}>Add ZIPs</button>
          </form>
          {props.zipInput.trim().length > 0 && zipHasInvalid && <p className="meta inline-hint">Enter 5-digit ZIPs separated by commas.</p>}
          {zipHint && <p className="meta inline-hint">{zipHint}</p>}
          <div className="chips">
            {props.zipCodes.map((zip) => (
              <div
                key={zip}
                className={`chip-item ${draggingZip === zip ? 'dragging' : ''}`}
                draggable
                onDragStart={(event) => {
                  setDraggingZip(zip);
                  event.dataTransfer.effectAllowed = 'move';
                  event.dataTransfer.setData('text/plain', zip);
                }}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  if (!draggingZip || draggingZip === zip) {
                    return;
                  }
                  props.reorderZip(draggingZip, zip);
                  setDraggingZip(null);
                }}
                onDragEnd={() => setDraggingZip(null)}
              >
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
              if (result.invalid.length > 0) {
                setSymbolHint(`Invalid symbols: ${result.invalid.join(', ')}`);
              } else if (result.added.length === 0 && result.duplicates.length === 0 && !result.limitHit) {
                setSymbolHint('Enter comma-separated symbols.');
              } else if (result.added.length === 0 && result.duplicates.length > 0) {
                setSymbolHint('Already added');
              } else if (result.limitHit && result.added.length === 0) {
                setSymbolHint(`You can add up to ${props.maxWatchlist} symbols`);
              } else if (result.added.length > 0 && result.duplicates.length > 0) {
                setSymbolHint(`Added ${result.added.length}, skipped ${result.duplicates.length} duplicate`);
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
                props.setSymbolInput(e.target.value.toUpperCase().replace(/[^A-Z0-9,.\-\s]/g, ''));
                setSymbolHint('');
              }}
              placeholder="Symbols (e.g., AAPL, MSFT, BTC-USD)"
            />
            <button type="submit" disabled={!symbolHasValid}>Add Symbols</button>
          </form>
          {props.symbolInput.trim().length > 0 && symbolHasInvalid && <p className="meta inline-hint">Enter comma-separated symbols.</p>}
          {symbolHint && <p className="meta inline-hint">{symbolHint}</p>}
          <div className="chips">
            {props.watchlist.map((symbol) => (
              <div
                key={symbol}
                className={`chip-item ${draggingSymbol === symbol ? 'dragging' : ''}`}
                draggable
                onDragStart={(event) => {
                  setDraggingSymbol(symbol);
                  event.dataTransfer.effectAllowed = 'move';
                  event.dataTransfer.setData('text/plain', symbol);
                }}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  if (!draggingSymbol || draggingSymbol === symbol) {
                    return;
                  }
                  props.reorderSymbol(draggingSymbol, symbol);
                  setDraggingSymbol(null);
                }}
                onDragEnd={() => setDraggingSymbol(null)}
              >
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
            <h2>Appearance</h2>
            <button
              type="button"
              className="link-button"
              onClick={async () => {
                await props.onResetUiPreferences();
              }}
            >
              Restore defaults
            </button>
          </div>
          <p className="meta">Theme updates apply immediately and are saved to your account.</p>
          <div className="settings-select-grid">
            <label className="settings-select-field">
              <span>Theme mode</span>
              <select
                aria-label="Theme mode"
                value={props.themeMode}
                onChange={(event) => props.onThemeModeChange(event.target.value as ThemeMode)}
              >
                <option value="dark">Dark</option>
                <option value="light">Light</option>
              </select>
            </label>
            <label className="settings-select-field">
              <span>Accent</span>
              <select
                aria-label="Accent"
                value={props.accent}
                onChange={(event) => props.onAccentChange(event.target.value as Accent)}
              >
                <option value="default">Default</option>
                <option value="gold">Gold</option>
                <option value="blue">Blue</option>
                <option value="green">Green</option>
                <option value="red">Red</option>
                <option value="orange">Orange</option>
                <option value="yellow">Yellow</option>
                <option value="purple">Purple</option>
                <option value="pink">Pink</option>
                <option value="white">White</option>
                <option value="lightGray">Light Gray</option>
                <option value="darkGray">Dark Gray</option>
                <option value="black">Black</option>
              </select>
            </label>
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
              }}
            >
              Restore defaults
            </button>
          </div>
          <p className="meta">Choose which sources appear in your news feed.</p>
          <div className="news-source-select-all">
            <button
              type="button"
              className="link-button"
              onClick={() => props.onUpdateNewsSources(selectableSourceIds)}
            >
              Select all
            </button>
            {selectedSelectableCount > 0 && !allSourcesSelected && <span className="meta small">(partial)</span>}
            {selectedSelectableCount > 0 && (
              <button
                type="button"
                className="link-button inline"
                onClick={() => props.onUpdateNewsSources(
                  props.selectedNewsSourceIds.filter((id) => !selectableSourceIds.includes(id))
                )}
              >
                Clear all
              </button>
            )}
          </div>
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
      {deleteHint && <p className="meta inline-hint">{deleteHint}</p>}
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
  embedded?: boolean;
  showForgot?: boolean;
  notice?: string | null;
  onBeforeSubmit?: () => void;
  onCancel?: () => void;
  onSubmit: (values: Record<'email' | 'password', string>) => Promise<void>;
};

type UnifiedAuthPageProps = {
  mode: 'login' | 'signup';
  authMessage: string | null;
  onClearAuthMessage: () => void;
  onModeChange: (mode: 'login' | 'signup') => void;
  onLogin: (values: Record<'email' | 'password', string>) => Promise<void>;
  onSignup: (values: Record<'email' | 'password', string>) => Promise<void>;
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
  const pendingLabel = props.submitLabel === 'Sign In'
    ? 'Signing In...'
    : props.submitLabel === 'Create Account'
      ? 'Creating Account...'
      : props.submitLabel === 'Sign in'
        ? 'Signing in...'
        : props.submitLabel === 'Create account'
          ? 'Creating account...'
      : props.submitLabel === 'Send reset link'
        ? 'Sending link...'
        : props.submitLabel === 'Reset password'
          ? 'Resetting password...'
          : 'Working...';

  const errorMessage = message;
  const content = (
    <>
      {props.title ? <h2>{props.title}</h2> : null}
      {props.description ? <p className="meta">{props.description}</p> : null}
      <form
        className="auth-form"
        onSubmit={async (event) => {
          event.preventDefault();
          props.onBeforeSubmit?.();
          setPending(true);
          setMessage(null);
          try {
            await props.onSubmit({ email, password });
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
              onChange={(e) => {
                setEmail(e.target.value);
                if (message) {
                  setMessage(null);
                }
              }}
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
              aria-label="Password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (message) {
                  setMessage(null);
                }
              }}
              placeholder="********"
              required
            />
            {passwordHelper ? (
              <p className={passwordError ? 'meta inline-hint' : 'meta'}>{passwordHelper}</p>
            ) : props.showForgot ? (
              <p className="meta auth-forgot-slot">
                <a href="#/forgot">Forgot password?</a>
              </p>
            ) : (
              <p className="meta auth-forgot-slot">&nbsp;</p>
            )}
          </label>
        )}
        <div className="auth-actions">
          <button
            type="button"
            className="auth-cancel"
            onClick={() => {
              props.onCancel?.();
              window.location.hash = '#/';
            }}
          >
            Cancel
          </button>
          <button type="submit" className={`auth-submit${pending ? ' is-pending' : ''}`} disabled={disableSubmit}>
            {pending ? (
              <>
                <span className="auth-spinner" aria-hidden="true" />
                <span>{pendingLabel}</span>
              </>
            ) : (
              props.submitLabel
            )}
          </button>
        </div>
      </form>
      {props.notice ? <div className="auth-notice" role="status">{props.notice}</div> : null}
      {errorMessage ? <div className="auth-error" role="alert">{errorMessage}</div> : null}
      {!props.fields.some((field) => field.key === 'password') && props.showForgot ? (
        <p className="meta auth-links">
          <a href="#/forgot">Forgot password?</a>
        </p>
      ) : null}
    </>
  );

  if (props.embedded) {
    return <>{content}</>;
  }

  return (
    <main className="settings-page auth-page">
      <section className="card controls auth-card">
        {content}
      </section>
    </main>
  );
}

function UnifiedAuthPage(props: UnifiedAuthPageProps) {
  const isSignup = props.mode === 'signup';
  const description = isSignup
    ? 'Create an account to save your dashboard settings.'
    : 'Sign in to save your settings and sync preferences.';
  const submitLabel = isSignup ? 'Create Account' : 'Sign In';
  const onSubmit = isSignup ? props.onSignup : props.onLogin;

  return (
    <main className="settings-page auth-page">
      <section className="card controls auth-card">
        <div className="auth-header">
          <h2>{isSignup ? 'Create Account' : 'Sign In'}</h2>
          <p className="meta">{description}</p>
        </div>
        <div className="auth-toggle">
          <button
            type="button"
            className={props.mode === 'login' ? 'active' : ''}
            onClick={() => props.onModeChange('login')}
          >
            Sign In
          </button>
          <button
            type="button"
            className={props.mode === 'signup' ? 'active' : ''}
            onClick={() => props.onModeChange('signup')}
          >
            Create Account
          </button>
        </div>
        <p className="meta auth-helper">
          Sign in or create an account to personalize your dashboard with your own news, weather, and market preferences.
        </p>
        <AuthPage
          key={props.mode}
          embedded
          title=""
          description=""
          submitLabel={submitLabel}
          passwordRequirement={isSignup ? 'Password must be at least 8 characters.' : undefined}
          showForgot={!isSignup}
          onBeforeSubmit={props.onClearAuthMessage}
          onCancel={props.onClearAuthMessage}
          fields={[
            { key: 'email', label: 'Email', type: 'email' },
            { key: 'password', label: 'Password', type: 'password' }
          ]}
          onSubmit={onSubmit}
        />
        {props.authMessage && <div className="auth-notice" role="status">{props.authMessage}</div>}
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

function parseZipInput(raw: string): { valid: string[]; invalid: string[] } {
  const tokens = raw.split(',').map((entry) => entry.trim()).filter((entry) => entry.length > 0);
  const valid: string[] = [];
  const invalid: string[] = [];
  const seen = new Set<string>();
  tokens.forEach((token) => {
    const normalized = normalizeZip(token);
    if (!normalized) {
      invalid.push(token);
      return;
    }
    if (!seen.has(normalized)) {
      seen.add(normalized);
      valid.push(normalized);
    }
  });
  return { valid, invalid };
}

function parseSymbolInput(raw: string): { valid: string[]; invalid: string[] } {
  const tokens = raw.split(',').map((entry) => entry.trim()).filter((entry) => entry.length > 0);
  const valid: string[] = [];
  const invalid: string[] = [];
  const seen = new Set<string>();
  tokens.forEach((token) => {
    const normalized = token.replace(/\s+/g, '').toUpperCase();
    if (!normalized) {
      return;
    }
    if (!/^[A-Z0-9.\-]+$/.test(normalized)) {
      invalid.push(token);
      return;
    }
    if (!seen.has(normalized)) {
      seen.add(normalized);
      valid.push(normalized);
    }
  });
  return { valid, invalid };
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
    if (title.length === 0) {
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
  if (window.location.hash.startsWith('#/auth') || window.location.hash === '#/login' || window.location.hash === '#/signup') {
    return 'auth';
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
  if (window.location.hash === '#/about') {
    return 'about';
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

function normalizeNewsSourceKey(value?: string): string {
  return (value ?? '').trim().toLowerCase().replace(/\(.*?\)/g, '').replace(/[-_/]/g, ' ').replace(/\s+/g, ' ').trim();
}

function parseNewsSourceDomain(sourceUrl?: string): string | null {
  if (!sourceUrl) {
    return null;
  }
  try {
    const parsed = new URL(sourceUrl);
    const canonical = unwrapRedirectSourceUrl(parsed) ?? parsed;
    const host = canonical.hostname.trim().toLowerCase();
    if (GENERIC_ICON_HOSTS.has(host)) {
      return null;
    }
    const resolvedHost = host.startsWith('www.')
      ? host.slice(4)
      : host;
    return resolvedHost || null;
  } catch {
    return null;
  }
}

function unwrapRedirectSourceUrl(parsedUrl: URL): URL | null {
  for (const key of WRAPPER_QUERY_KEYS) {
    const value = parsedUrl.searchParams.get(key);
    if (!value) {
      continue;
    }
    try {
      const candidate = new URL(value);
      const candidateHost = candidate.hostname.toLowerCase();
      if (GENERIC_ICON_HOSTS.has(candidateHost)) {
        continue;
      }
      return candidate;
    } catch {
      // ignore non-absolute values
    }
  }
  return null;
}

function resolveNewsSourceIconDomain(sourceId: string, sourceLabel: string, sourceUrl?: string): string | null {
  const mappedById = NEWS_SOURCE_ICON_DOMAIN_OVERRIDES_BY_ID[normalizeNewsSourceKey(sourceId)];
  if (mappedById) {
    return mappedById;
  }
  const mappedByLabel = NEWS_SOURCE_ICON_DOMAIN_OVERRIDES_BY_LABEL[normalizeNewsSourceKey(sourceLabel)];
  if (mappedByLabel) {
    return mappedByLabel;
  }
  return parseNewsSourceDomain(sourceUrl);
}

function NewsSourceFavicon({ sourceId, sourceLabel, sourceUrl }: {
  sourceId: string;
  sourceLabel: string;
  sourceUrl?: string;
}) {
  const domain = resolveNewsSourceIconDomain(sourceId, sourceLabel, sourceUrl);
  const [fallback, setFallback] = useState(false);
  if (!domain || fallback) {
    return <span className="news-source-icon news-logo-fallback">{sourceId.toUpperCase()}</span>;
  }
  return (
    <img
      className="news-source-icon"
      src={`https://www.google.com/s2/favicons?domain=${encodeURIComponent(domain)}&sz=32`}
      alt={`${sourceLabel} source icon`}
      onError={() => setFallback(true)}
      loading="lazy"
    />
  );
}

function compareLocationLabels(left: string, right: string): number {
  const a = extractLocationSortParts(left);
  const b = extractLocationSortParts(right);
  const stateCompare = a.state.localeCompare(b.state, undefined, { sensitivity: 'base' });
  if (stateCompare !== 0) {
    return stateCompare;
  }
  return a.city.localeCompare(b.city, undefined, { sensitivity: 'base' });
}

function extractLocationSortParts(label: string): { state: string; city: string } {
  const trimmed = label.trim();
  if (!trimmed) {
    return { state: 'ZZZ', city: '' };
  }
  const base = trimmed.replace(/\s*\([^)]*\)\s*$/, '').trim();
  const parts = base.split(',');
  if (parts.length >= 2) {
    const city = parts[0].trim();
    const state = parts[1].trim();
    if (state) {
      return { state, city };
    }
  }
  return { state: 'ZZZ', city: base };
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

function isNewsDebugEnabled(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }
  const stored = window.localStorage?.getItem(NEWS_DEBUG_STORAGE_KEY);
  if (stored && stored.trim().toLowerCase() === 'true') {
    return true;
  }
  const hash = window.location.hash ?? '';
  if (hash.includes('debugNews=true') || hash.includes('debug-news')) {
    return true;
  }
  const search = window.location.search ?? '';
  if (search.includes('debugNews=true')) {
    return true;
  }
  return false;
}

function sparklinePoints(symbol: string, change: number): string {
  const seed = Math.abs(Array.from(symbol).reduce((acc, ch) => ((acc * 31) + ch.charCodeAt(0)) | 0, 7));
  const points: string[] = [];
  let y = 10;
  for (let i = 0; i < 12; i++) {
    const drift = change >= 0 ? -0.35 : 0.35;
    const jitter = (((seed >> (i % 8)) & 3) - 1.5) * 0.65;
    y = Math.max(2, Math.min(18, y + drift + jitter));
    points.push(`${i * 6},${y.toFixed(2)}`);
  }
  return points.join(' ');
}
