import { beforeEach, describe, expect, it } from 'vitest';
import { loadWatchlist, loadZipCodes, saveWatchlist, saveZipCodes } from './preferences';

describe('preferences persistence', () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    const mockLocalStorage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
      clear: () => store.clear()
    };
    Object.defineProperty(globalThis, 'localStorage', { value: mockLocalStorage, configurable: true });
    localStorage.clear();
  });

  it('persists and loads zip codes with max and validation', () => {
    saveZipCodes(['02108', 'bad', '98101']);
    expect(loadZipCodes()).toEqual(['02108', '98101']);
  });

  it('persists and loads watchlist symbols uppercase', () => {
    saveWatchlist(['aapl', ' msft ', '']);
    expect(loadWatchlist()).toEqual(['AAPL', 'MSFT']);
  });
});
