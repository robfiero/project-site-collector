import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchMe } from './api';

describe('fetchMe', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('treats 404 auth_disabled as anonymous mode', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'auth_disabled' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    const me = await fetchMe();
    expect(me).toBeNull();
  });

  it('throws for other 404 /api/me responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'not_found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(fetchMe()).rejects.toThrow('Current user request failed (404)');
  });
});
