const ZIP_LABELS: Record<string, string> = {
  '02108': 'Boston, MA',
  '98101': 'Seattle, WA'
};

export function formatPlaceLabel(zipOrLocation: string): string {
  const value = zipOrLocation.trim();
  if (/^\d{5}$/.test(value)) {
    const knownLabel = ZIP_LABELS[value];
    return knownLabel ? `${knownLabel} (${value})` : `ZIP ${value}`;
  }
  return value || 'Unknown place';
}
