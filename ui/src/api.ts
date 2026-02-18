import type { CatalogDefaults, CollectorStatus, EnvStatus, MetricsResponse, SignalsSnapshot } from './models';

export interface HealthResponse {
  status: string;
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health');
  if (!response.ok) {
    throw new Error(`Health request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchSignals(): Promise<SignalsSnapshot> {
  const response = await fetch('/api/signals');
  if (!response.ok) {
    throw new Error(`Signals request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchEvents(limit = 100): Promise<unknown[]> {
  const response = await fetch(`/api/events?limit=${limit}`);
  if (!response.ok) {
    throw new Error(`Events request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchMetrics(): Promise<MetricsResponse> {
  const response = await fetch('/api/metrics');
  if (!response.ok) {
    throw new Error(`Metrics request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchCollectorStatus(): Promise<Record<string, CollectorStatus>> {
  const response = await fetch('/api/collectors/status');
  if (!response.ok) {
    throw new Error(`Collector status request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchCatalogDefaults(): Promise<CatalogDefaults> {
  const response = await fetch('/api/catalog/defaults');
  if (!response.ok) {
    throw new Error(`Catalog defaults request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchConfigView(): Promise<Record<string, unknown>> {
  const response = await fetch('/api/config');
  if (!response.ok) {
    throw new Error(`Config request failed (${response.status})`);
  }
  return response.json();
}

export async function fetchEnvironment(zips: string[]): Promise<EnvStatus[]> {
  const query = zips.length > 0 ? `?zips=${encodeURIComponent(zips.join(','))}` : '';
  const response = await fetch(`/api/env${query}`);
  if (!response.ok) {
    throw new Error(`Environment request failed (${response.status})`);
  }
  return response.json();
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
}

export interface DevOutboxEmail {
  to: string;
  subject: string;
  body: string;
  links: string[];
  createdAt: string;
}

async function postJson(path: string, payload: Record<string, unknown>): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function signup(email: string, password: string): Promise<AuthUserView> {
  const response = await postJson('/api/auth/signup', { email, password });
  if (!response.ok) {
    throw new Error(`Signup failed (${response.status})`);
  }
  return response.json();
}

export async function login(email: string, password: string): Promise<AuthUserView> {
  const response = await postJson('/api/auth/login', { email, password });
  if (!response.ok) {
    throw new Error(`Login failed (${response.status})`);
  }
  return response.json();
}

export async function logout(): Promise<void> {
  const response = await fetch('/api/auth/logout', { method: 'POST' });
  if (!response.ok) {
    throw new Error(`Logout failed (${response.status})`);
  }
}

export async function forgotPassword(email: string): Promise<void> {
  const response = await postJson('/api/auth/forgot', { email });
  if (!response.ok) {
    throw new Error(`Forgot password request failed (${response.status})`);
  }
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  const response = await postJson('/api/auth/reset', { token, newPassword });
  if (!response.ok) {
    throw new Error(`Password reset failed (${response.status})`);
  }
}

export async function fetchMe(): Promise<AuthUserView | null> {
  const response = await fetch('/api/me');
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
  const response = await fetch('/api/me/preferences');
  if (!response.ok) {
    throw new Error(`Preferences request failed (${response.status})`);
  }
  return response.json();
}

export async function saveMyPreferences(payload: PreferencesPayload): Promise<PreferencesPayload> {
  const response = await fetch('/api/me/preferences', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    throw new Error(`Preferences update failed (${response.status})`);
  }
  return response.json();
}

export async function fetchDevOutbox(): Promise<DevOutboxEmail[]> {
  const response = await fetch('/api/dev/outbox');
  if (response.status === 404) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`Dev outbox request failed (${response.status})`);
  }
  return response.json();
}
