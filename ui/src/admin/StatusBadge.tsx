import { type ReactNode } from 'react';

type StatusTone = 'success' | 'warning' | 'error' | 'neutral';

type StatusBadgeProps = {
  status: string;
  children?: ReactNode;
  tone?: StatusTone;
};

export function classifyStatus(status: string): StatusTone {
  const normalized = status.trim().toLowerCase();
  if (['healthy', 'connected', 'enabled', 'ok', 'active', 'success'].includes(normalized)) {
    return 'success';
  }
  if (['warning', 'degraded', 'reconnecting', 'partial', 'off', 'downstream'].includes(normalized)) {
    return 'warning';
  }
  if (['error', 'failed', 'failure', 'fatal', 'down', 'offline', 'unauthorized', 'forbidden'].includes(normalized)) {
    return 'error';
  }
  if (['disabled', 'none', 'unknown', 'not', 'n/a', 'na', 'neutral'].includes(normalized)) {
    return 'neutral';
  }
  return 'neutral';
}

function labelFromStatus(status: string): string {
  const normalized = status.trim().toLowerCase();
  if (normalized.length === 0) {
    return 'Unknown';
  }
  return normalized
    .split(/[\s_\-]+/)
    .filter(Boolean)
    .map((part) => `${part[0].toUpperCase()}${part.slice(1)}`)
    .join(' ');
}

export function resolveStatusLabel(status: string): string {
  const normalized = status.trim().toLowerCase();
  const mapped: Record<string, string> = {
    healthy: 'Healthy',
    connected: 'Connected',
    enabled: 'Enabled',
    disabled: 'Disabled',
    warning: 'Warning',
    error: 'Error'
  };
  return mapped[normalized] ?? labelFromStatus(status);
}

export default function StatusBadge(props: StatusBadgeProps) {
  const resolvedTone = props.tone ?? classifyStatus(props.status);
  const resolvedText = props.children ? String(props.children).trim() : resolveStatusLabel(props.status);
  return (
    <span className={`status-badge ${resolvedTone}`}>
      {resolvedText}
    </span>
  );
}
