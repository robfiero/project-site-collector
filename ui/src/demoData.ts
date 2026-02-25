import type { AirQualitySignal, LocalHappeningsSignal, MarketQuoteSignal } from './models';

export function demoAirQuality(zip: string): AirQualitySignal {
  const seed = deterministicSeed(zip);
  const aqi = 40 + (seed % 120);
  return {
    location: zip,
    aqi,
    category: aqiCategory(aqi),
    updatedAt: new Date().toISOString()
  };
}

export function demoLocalHappenings(zip: string): LocalHappeningsSignal {
  const seed = deterministicSeed(zip);
  const themes = ['Farmers Market', 'Live Music', 'School Event', 'Road Closure', 'Community Meetup'];
  return {
    location: zip,
    items: [
      {
        id: `${zip}-demo-1`,
        name: `${themes[seed % themes.length]} this evening`,
        startDateTime: new Date().toISOString(),
        venueName: 'Community Center',
        city: 'Local',
        state: '',
        url: '',
        category: 'community',
        source: 'ticketmaster'
      },
      {
        id: `${zip}-demo-2`,
        name: `${themes[(seed + 2) % themes.length]} this weekend`,
        startDateTime: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
        venueName: 'Downtown',
        city: 'Local',
        state: '',
        url: '',
        category: 'community',
        source: 'ticketmaster'
      }
    ],
    sourceAttribution: 'Powered by Ticketmaster',
    updatedAt: new Date().toISOString()
  };
}

export function demoQuote(symbol: string): MarketQuoteSignal {
  const seed = deterministicSeed(symbol);
  const price = 50 + (seed % 450) + ((seed % 100) / 100);
  const change = ((seed % 200) - 100) / 100;
  return {
    symbol,
    price,
    change,
    updatedAt: new Date().toISOString()
  };
}

function deterministicSeed(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    hash = ((hash << 5) - hash + input.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function aqiCategory(aqi: number): string {
  if (aqi <= 50) {
    return 'Good';
  }
  if (aqi <= 100) {
    return 'Moderate';
  }
  if (aqi <= 150) {
    return 'Unhealthy for Sensitive Groups';
  }
  return 'Unhealthy';
}
