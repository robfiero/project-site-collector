export function epochSecondsToDate(v: number): Date {
  return new Date(v * 1000);
}

export function formatDateTimeValue(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  let parsed: Date;
  if (typeof value === 'number') {
    parsed = epochSecondsToDate(value);
  } else {
    const numeric = Number.parseFloat(value);
    if (Number.isFinite(numeric) && /^\d+(\.\d+)?$/.test(value.trim())) {
      parsed = epochSecondsToDate(numeric);
    } else {
      parsed = new Date(value);
    }
  }
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString();
}

export function formatEpochSecondsTime(v: number): string {
  const d = epochSecondsToDate(v);
  if (Number.isNaN(d.getTime())) {
    return '-';
  }
  return d.toLocaleTimeString([], { hour12: false });
}

export function formatEpochSecondsDateTime(v: number): string {
  const d = epochSecondsToDate(v);
  if (Number.isNaN(d.getTime())) {
    return '-';
  }
  return d.toLocaleString();
}
