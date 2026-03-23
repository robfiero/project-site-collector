import type { CollectorStatus } from '../models';

export function isCollectorRateLimited(status: CollectorStatus): boolean {
  const message = (status.lastErrorMessage ?? '').toLowerCase();
  return message.includes('429') || message.includes('rate limit') || message.includes('rate-limited');
}

export function collectorStatusLabel(status: CollectorStatus): string {
  if (status.lastSuccess === null || status.lastSuccess === undefined) {
    return 'Unknown';
  }
  if (status.lastSuccess) {
    return 'Healthy';
  }
  return isCollectorRateLimited(status) ? 'Rate limited' : 'Failed';
}

export function isCollectorDegraded(status: CollectorStatus): boolean {
  return status.lastSuccess === false && !isCollectorRateLimited(status);
}
