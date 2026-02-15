export type EventType =
  | 'CollectorTickStarted'
  | 'CollectorTickCompleted'
  | 'SiteFetched'
  | 'ContentChanged'
  | 'NewsUpdated'
  | 'WeatherUpdated'
  | 'AlertRaised';

export interface BaseEvent {
  type: EventType;
  timestamp: number;
}

export interface CollectorTickStartedEvent extends BaseEvent {
  type: 'CollectorTickStarted';
  collectorName: string;
}

export interface CollectorTickCompletedEvent extends BaseEvent {
  type: 'CollectorTickCompleted';
  collectorName: string;
  success: boolean;
  durationMillis: number;
}

export interface SiteFetchedEvent extends BaseEvent {
  type: 'SiteFetched';
  siteId: string;
  url: string;
  status: number;
  durationMillis: number;
}

export interface ContentChangedEvent extends BaseEvent {
  type: 'ContentChanged';
  siteId: string;
  url: string;
  oldHash: string;
  newHash: string;
}

export interface NewsUpdatedEvent extends BaseEvent {
  type: 'NewsUpdated';
  source: string;
  storyCount: number;
}

export interface WeatherUpdatedEvent extends BaseEvent {
  type: 'WeatherUpdated';
  location: string;
  tempF: number;
  conditions: string;
}

export interface AlertRaisedEvent extends BaseEvent {
  type: 'AlertRaised';
  category: string;
  message: string;
  details?: Record<string, unknown>;
}

export type SignalEvent =
  | CollectorTickStartedEvent
  | CollectorTickCompletedEvent
  | SiteFetchedEvent
  | ContentChangedEvent
  | NewsUpdatedEvent
  | WeatherUpdatedEvent
  | AlertRaisedEvent;

export interface NewsStory {
  title: string;
  link: string;
  publishedAt: string;
  source: string;
}

export interface SiteSignal {
  siteId: string;
  url: string;
  hash: string;
  title: string | null;
  linkCount: number;
  lastChecked: string;
  lastChanged: string;
}

export interface NewsSignal {
  source: string;
  stories: NewsStory[];
  updatedAt: string;
}

export interface WeatherSignal {
  location: string;
  tempF: number;
  conditions: string;
  alerts: string[];
  updatedAt: number;
}

export interface SignalsSnapshot {
  sites: Record<string, SiteSignal>;
  news: Record<string, NewsSignal>;
  weather: Record<string, WeatherSignal>;
}

export interface EventEnvelope {
  type: string;
  timestamp: number;
  event: Record<string, unknown>;
}
