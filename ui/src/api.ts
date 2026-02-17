import type { CatalogDefaults, CollectorStatus, MetricsResponse, SignalsSnapshot } from './models';

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
