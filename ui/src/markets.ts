const SYMBOL_LABELS: Record<string, string> = {
  AAPL: 'Apple Inc.',
  AMZN: 'Amazon.com, Inc.',
  'BTC-USD': 'Bitcoin',
  DIS: 'The Walt Disney Company',
  'ETH-USD': 'Ethereum',
  HD: 'The Home Depot, Inc.',
  MSFT: 'Microsoft Corporation',
  NVDA: 'NVIDIA Corporation',
  ORCL: 'Oracle Corporation',
  '^GSPC': 'S&P 500',
  SPX: 'S&P 500',
  SPY: 'SPDR S&P 500 ETF Trust',
  TSLA: 'Tesla, Inc.'
};

export function formatMarketSymbolLabel(symbol: string): string {
  const normalized = symbol.trim().toUpperCase();
  const company = SYMBOL_LABELS[normalized];
  if (!company) {
    return normalized || symbol;
  }
  return `${normalized} - ${company}`;
}

export function companyNameForSymbol(symbol: string): string {
  const normalized = symbol.trim().toUpperCase();
  return SYMBOL_LABELS[normalized] ?? normalized;
}
