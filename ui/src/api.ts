import type {
  AdminEmailPreview,
  AdminTrendsSnapshot,
  CatalogDefaults,
  CollectorStatus,
  EnvStatus,
  MarketsSnapshot,
  MetricsResponse,
  SignalsSnapshot
} from './models';
import { apiUrl } from './config/api';

export interface HealthResponse {
  status: string;
  version?: string;
  buildTime?: string;
  gitSha?: string;
}

let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const response = init === undefined ? await fetch(input) : await fetch(input, init);
  if (response.status === 401 && onUnauthorized) {
    onUnauthorized();
  }
  return response;
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await apiFetch(apiUrl('/api/health'));
  if (!response.ok) {
    throw new Error(`Health request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchSignals(): Promise<SignalsSnapshot> {
  const response = await apiFetch(apiUrl('/api/signals'), withCredentials());
  if (!response.ok) {
    throw new Error(`Signals request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchEvents(limit = 100): Promise<unknown[]> {
  const response = await apiFetch(apiUrl(`/api/events?limit=${limit}`), withCredentials());
  if (!response.ok) {
    throw new Error(`Events request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchMetrics(): Promise<MetricsResponse> {
  const response = await apiFetch(apiUrl('/api/metrics'));
  if (!response.ok) {
    throw new Error(`Metrics request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchMarkets(symbols: string[]): Promise<MarketsSnapshot> {
  const query = symbols.length > 0 ? `?symbols=${encodeURIComponent(symbols.join(','))}` : '';
  const response = await apiFetch(apiUrl(`/api/markets${query}`));
  if (!response.ok) {
    throw new Error(`Markets request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchCollectorStatus(): Promise<Record<string, CollectorStatus>> {
  const response = await apiFetch(apiUrl('/api/collectors/status'));
  if (!response.ok) {
    throw new Error(`Collector status request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchCatalogDefaults(): Promise<CatalogDefaults> {
  const response = await apiFetch(apiUrl('/api/catalog/defaults'));
  if (!response.ok) {
    throw new Error(`Catalog defaults request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchConfigView(): Promise<Record<string, unknown>> {
  const response = await apiFetch(apiUrl('/api/config'));
  if (!response.ok) {
    throw new Error(`Config request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchEnvironment(zips: string[]): Promise<EnvStatus[]> {
  const query = zips.length > 0 ? `?zips=${encodeURIComponent(zips.join(','))}` : '';
  const response = await apiFetch(apiUrl(`/api/env${query}`));
  if (response.ok) {
    return response.json();
  }
  if (zips.length <= 1) {
    throw new Error(`Environment request failed (${response.status})`);
  }

  const settled = await Promise.allSettled(
    zips.map(async (zip) => {
      const single = await apiFetch(apiUrl(`/api/env?zips=${encodeURIComponent(zip)}`));
      if (!single.ok) {
        throw new Error(`Environment request failed (${single.status}) for ZIP ${zip}`);
      }
      const payload = await single.json() as EnvStatus[];
      return payload;
    })
  );

  const merged: EnvStatus[] = [];
  settled.forEach((result) => {
    if (result.status === 'fulfilled') {
      merged.push(...result.value);
    }
  });

  if (merged.length === 0) {
    throw new Error(`Environment request failed (${response.status})`);
  }
  return merged;
}

export interface AuthUserView {
  id: string;
  email: string;
}

export interface PreferencesPayload {
  userId?: string;
  zipCodes: string[];
  watchlist: string[];
  newsSourceIds: string[];
  themeMode: 'light' | 'dark';
  accent: 'default' | 'gold' | 'blue' | 'green' | 'red' | 'orange' | 'yellow' | 'purple' | 'pink' | 'white' | 'lightGray' | 'darkGray' | 'black';
}

export interface DevOutboxEmail {
  to: string;
  subject: string;
  body: string;
  links: string[];
  createdAt: string;
}

export interface NewsSourceSettingsPayload {
  availableSources: CatalogDefaults['defaultNewsSources'];
  effectiveSelectedSources: string[];
}

export type SettingsResetScope = 'ui' | 'collectors' | 'all';

export interface SettingsResetResponse {
  scopeApplied: SettingsResetScope;
  preferences: PreferencesPayload;
}

async function postJson(path: string, payload: Record<string, unknown>): Promise<Response> {
  return apiFetch(apiUrl(path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

function withCredentials(init: RequestInit = {}): RequestInit {
  return { ...init, credentials: 'include' };
}

async function postJsonWithCredentials(path: string, payload: Record<string, unknown>): Promise<Response> {
  return apiFetch(
    apiUrl(path),
    withCredentials({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
  );
}

type ApiError = Error & { status?: number };

async function extractErrorMessage(response: Response): Promise<string> {
  try {
    const json = await response.clone().json() as { error?: string; message?: string };
    const candidate = json?.error ?? json?.message;
    if (typeof candidate === 'string' && candidate.trim().length > 0) {
      return candidate.trim();
    }
  } catch {
    // fall through to text / fallback
  }
  try {
    const text = (await response.text()).trim();
    if (text.length > 0) {
      return text;
    }
  } catch {
    // fallback below
  }
  return `Request failed (${response.status})`;
}

async function throwApiError(response: Response, fallback: string): Promise<never> {
  const message = await extractErrorMessage(response);
  const error = new Error(message || fallback) as ApiError;
  error.status = response.status;
  throw error;
}

export async function signup(email: string, password: string): Promise<AuthUserView> {
  const response = await postJsonWithCredentials('/api/auth/signup', { email, password });
  if (!response.ok) {
    await throwApiError(response, `Signup failed (${response.status})`);
  }
  return response.json();
}

export async function login(email: string, password: string): Promise<AuthUserView> {
  const response = await postJsonWithCredentials('/api/auth/login', { email, password });
  if (!response.ok) {
    await throwApiError(response, `Login failed (${response.status})`);
  }
  return response.json();
}

export async function logout(): Promise<void> {
  const response = await apiFetch(apiUrl('/api/auth/logout'), withCredentials({ method: 'POST' }));
  if (!response.ok) {
    await throwApiError(response, `Logout failed (${response.status})`);
  }
}

export async function deleteMyAccount(): Promise<void> {
  const response = await apiFetch(apiUrl('/api/me/delete'), withCredentials({ method: 'POST' }));
  if (!response.ok) {
    await throwApiError(response, `Account deletion failed (${response.status})`);
  }
}

export async function forgotPassword(email: string): Promise<void> {
  const response = await postJsonWithCredentials('/api/auth/forgot', { email });
  if (!response.ok) {
    await throwApiError(response, `Forgot password request failed (${response.status})`);
  }
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  const response = await postJsonWithCredentials('/api/auth/reset', { token, newPassword });
  if (!response.ok) {
    await throwApiError(response, `Password reset failed (${response.status})`);
  }
}

export async function fetchMe(): Promise<AuthUserView | null> {
  const response = await apiFetch(apiUrl('/api/me'), withCredentials());
  if (response.status === 401) {
    return null;
  }
  if (response.status === 404) {
    try {
      const body = await response.json() as { error?: string };
      if (body?.error === 'auth_disabled') {
        return null;
      }
    } catch {
      // fall through to generic error below
    }
  }
  if (!response.ok) {
    throw new Error(`Current user request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchMyPreferences(): Promise<PreferencesPayload> {
  const response = await apiFetch(apiUrl('/api/me/preferences'), withCredentials());
  if (!response.ok) {
    await throwApiError(response, `Preferences request failed (${response.status})`);
  }
  return response.json();
}

export async function saveMyPreferences(payload: PreferencesPayload): Promise<PreferencesPayload> {
  const response = await apiFetch(apiUrl('/api/me/preferences'), withCredentials({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }));
  if (!response.ok) {
    await throwApiError(response, `Preferences update failed (${response.status})`);
  }
  return response.json();
}

export async function fetchDevOutbox(): Promise<DevOutboxEmail[]> {
  const response = await apiFetch(apiUrl('/api/dev/outbox'), withCredentials());
  if (response.status === 404) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`Dev outbox request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchAdminTrends(): Promise<AdminTrendsSnapshot> {
  const response = await apiFetch(apiUrl('/api/admin/trends'), withCredentials());
  if (!response.ok) {
    throw new Error(`Admin trends request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchAdminEmailPreview(): Promise<AdminEmailPreview> {
  const response = await apiFetch(apiUrl('/api/admin/email/preview'), withCredentials());
  if (!response.ok) {
    throw new Error(`Admin email preview request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchNewsSourceSettings(): Promise<NewsSourceSettingsPayload> {
  const response = await apiFetch(apiUrl('/api/settings/newsSources'), withCredentials());
  if (!response.ok) {
    await throwApiError(response, `News source settings request failed (${response.status})`);
  }
  return response.json();
}

export async function saveNewsSourceSettings(selectedSources: string[]): Promise<NewsSourceSettingsPayload> {
  const response = await apiFetch(apiUrl('/api/settings/newsSources'), withCredentials({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ selectedSources })
  }));
  if (!response.ok) {
    await throwApiError(response, `News source settings update failed (${response.status})`);
  }
  return response.json();
}

export async function resetSettings(scope: SettingsResetScope): Promise<SettingsResetResponse> {
  const response = await apiFetch(apiUrl('/api/settings/reset'), withCredentials({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scope })
  }));
  if (!response.ok) {
    await throwApiError(response, `Settings reset failed (${response.status})`);
  }
  return response.json();
}

export async function triggerCollectorRefresh(collectors: string[] = ['envCollector', 'rssCollector', 'localEventsCollector']): Promise<void> {
  const response = await apiFetch(apiUrl('/api/collectors/refresh'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ collectors })
  });
  if (!response.ok && response.status !== 501) {
    await throwApiError(response, `Collector refresh failed (${response.status})`);
  }
}
