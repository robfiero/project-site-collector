const ZIPS_KEY = 'signal-sentinel:zip-codes';
const WATCHLIST_KEY = 'signal-sentinel:watchlist';
const MAX_ZIPS = 10;

const DEFAULT_ZIPS = ['02108', '98101'];
const DEFAULT_WATCHLIST = ['AAPL', 'MSFT', 'SPY', 'BTC-USD', 'ETH-USD'];

export function loadZipCodes(): string[] {
  return loadList(ZIPS_KEY, DEFAULT_ZIPS).slice(0, MAX_ZIPS);
}

export function saveZipCodes(zips: string[]): void {
  const normalized = zips
    .map((zip) => zip.trim())
    .filter((zip) => /^\d{5}$/.test(zip))
    .slice(0, MAX_ZIPS);
  saveList(ZIPS_KEY, normalized);
}

export function loadWatchlist(): string[] {
  return loadList(WATCHLIST_KEY, DEFAULT_WATCHLIST);
}

export function saveWatchlist(symbols: string[]): void {
  const normalized = symbols
    .map((symbol) => symbol.trim().toUpperCase())
    .filter((symbol) => symbol.length > 0);
  saveList(WATCHLIST_KEY, normalized);
}

function loadList(key: string, fallback: string[]): string[] {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) {
      return [...fallback];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [...fallback];
    }
    return parsed.filter((item) => typeof item === 'string');
  } catch {
    return [...fallback];
  }
}

function saveList(key: string, values: string[]): void {
  localStorage.setItem(key, JSON.stringify(values));
}
