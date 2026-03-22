export function normalizeApiBaseUrl(rawBaseUrl: string | undefined | null): string {
  return (rawBaseUrl ?? '').trim().replace(/\/+$/, '');
}

export function joinApiUrl(baseUrl: string, path: string): string {
  if (!path) {
    return baseUrl;
  }
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${baseUrl}${normalizedPath}`;
}

export const API_BASE = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL ?? '');

export function apiUrl(path: string): string {
  return joinApiUrl(API_BASE, path);
}
