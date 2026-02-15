export function epochSecondsToDate(v: number): Date {
  return new Date(v * 1000);
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
