import type { SignalsSnapshot } from './models';

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
