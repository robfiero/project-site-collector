import type { Dispatch, SetStateAction } from 'react';
import { formatEpochSecondsDateTime, formatEpochSecondsTime } from '../utils/date';
import { summarizeEvent } from '../eventFeed';
import type { EventEnvelope } from '../models';

type EventStreamCardProps = {
  filteredEvents: EventEnvelope[];
  eventTypeFilter: string;
  setEventTypeFilter: Dispatch<SetStateAction<string>>;
  searchQuery: string;
  setSearchQuery: Dispatch<SetStateAction<string>>;
  expandedEvents: Set<string>;
  setExpandedEvents: Dispatch<SetStateAction<Set<string>>>;
  knownTypes: readonly string[];
  maxEvents: number;
  paused: boolean;
  onTogglePause: () => void;
};

export default function EventStreamCard(props: EventStreamCardProps) {
  return (
    <section className="card events">
      <div className="event-header">
        <div>
          <h2 className="section-title">Live Activity</h2>
          <p className="meta section-description">Real-time stream of collector and system events.</p>
        </div>
        <div className="filters">
          <select value={props.eventTypeFilter} onChange={(e) => props.setEventTypeFilter(e.target.value)}>
            <option value="ALL">All types</option>
            {props.knownTypes.map((type) => <option key={type} value={type}>{type}</option>)}
          </select>
          <input value={props.searchQuery} onChange={(e) => props.setSearchQuery(e.target.value)} placeholder="Search events" />
          <button type="button" onClick={props.onTogglePause}>{props.paused ? 'Resume' : 'Pause'}</button>
        </div>
      </div>
      <p className="meta">Showing {props.filteredEvents.length} events (max stored: {props.maxEvents})</p>
      <div className="event-list">
        {props.filteredEvents.length === 0 ? <p className="empty">No events match current filters.</p> : props.filteredEvents.map((entry, index) => (
          <article key={`${entry.timestamp}-${index}`} className={`event-item event-card ${eventTone(entry.type)}`}>
            <div className="event-title event-row">
              <div className="event-time-group">
                <span className="event-time" title={formatEpochSecondsDateTime(entry.timestamp)}>
                  {relativeTime(entry.timestamp)} ({formatEpochSecondsTime(entry.timestamp)})
                </span>
              </div>
              <span className="event-type-badge">{entry.type}</span>
              <span className="event-summary">{summarizeEvent(entry)}</span>
            </div>
            <button type="button" className="toggle" onClick={() => toggleExpanded(eventRowId(entry, index), props.setExpandedEvents)}>
              {props.expandedEvents.has(eventRowId(entry, index)) ? 'Hide' : 'Details'}
            </button>
            {props.expandedEvents.has(eventRowId(entry, index)) && <pre>{JSON.stringify(entry, null, 2)}</pre>}
          </article>
        ))}
      </div>
    </section>
  );
}

function eventRowId(entry: EventEnvelope, index: number): string {
  return `${entry.type}-${entry.timestamp}-${index}`;
}

function toggleExpanded(rowId: string, setExpandedEvents: Dispatch<SetStateAction<Set<string>>>): void {
  setExpandedEvents((prev) => {
    const next = new Set(prev);
    if (next.has(rowId)) {
      next.delete(rowId);
    } else {
      next.add(rowId);
    }
    return next;
  });
}

function relativeTime(epochSeconds: number): string {
  const seconds = Math.max(0, Math.floor(Date.now() / 1000 - epochSeconds));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function eventTone(type: string): string {
  if (type === 'AlertRaised') {
    return 'alert';
  }
  if (type === 'SiteFetched') {
    return 'site';
  }
  if (type === 'WeatherUpdated' || type === 'EnvWeatherUpdated') {
    return 'weather';
  }
  if (type === 'EnvAqiUpdated') {
    return 'weather';
  }
  return 'neutral';
}
