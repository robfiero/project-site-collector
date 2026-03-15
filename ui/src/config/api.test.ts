import { describe, expect, it } from 'vitest';
import { joinApiUrl, normalizeApiBaseUrl } from './api';

describe('api URL helper', () => {
  it('returns relative paths when base is empty', () => {
    const base = normalizeApiBaseUrl('');
    expect(joinApiUrl(base, '/api/health')).toBe('/api/health');
    expect(joinApiUrl(base, '/api/stream')).toBe('/api/stream');
  });

  it('joins full origin without trailing slash', () => {
    const base = normalizeApiBaseUrl('https://example.com');
    expect(joinApiUrl(base, '/api/health')).toBe('https://example.com/api/health');
    expect(joinApiUrl(base, '/api/stream')).toBe('https://example.com/api/stream');
  });

  it('joins full origin with trailing slash', () => {
    const base = normalizeApiBaseUrl('https://example.com/');
    expect(joinApiUrl(base, '/api/health')).toBe('https://example.com/api/health');
    expect(joinApiUrl(base, '/api/stream')).toBe('https://example.com/api/stream');
  });

  it('normalizes paths without a leading slash', () => {
    const base = normalizeApiBaseUrl('https://example.com');
    expect(joinApiUrl(base, 'api/health')).toBe('https://example.com/api/health');
  });

  it('returns base url when path is empty', () => {
    const base = normalizeApiBaseUrl('https://example.com/');
    expect(joinApiUrl(base, '')).toBe('https://example.com');
  });

  it('trims whitespace and removes duplicate trailing slashes', () => {
    const base = normalizeApiBaseUrl('  https://example.com///  ');
    expect(base).toBe('https://example.com');
    expect(joinApiUrl(base, '/api/health')).toBe('https://example.com/api/health');
  });

  it('treats null or undefined base as empty', () => {
    expect(normalizeApiBaseUrl(undefined)).toBe('');
    expect(normalizeApiBaseUrl(null)).toBe('');
    expect(joinApiUrl(normalizeApiBaseUrl(undefined), '/api/health')).toBe('/api/health');
  });
});
