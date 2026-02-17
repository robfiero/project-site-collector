import { describe, expect, it } from 'vitest';
import { epochSecondsToDate, formatDateTimeValue } from './date';

describe('epochSecondsToDate', () => {
  it('converts epoch seconds to Date', () => {
    const value = 1_700_000_000;
    const date = epochSecondsToDate(value);
    expect(date.toISOString()).toBe('2023-11-14T22:13:20.000Z');
  });

  it('supports fractional epoch seconds', () => {
    const value = 1_700_000_000.5;
    const date = epochSecondsToDate(value);
    expect(date.getUTCMilliseconds()).toBe(500);
  });
});

describe('formatDateTimeValue', () => {
  it('handles epoch seconds input without falling back to 1970', () => {
    const formatted = formatDateTimeValue(1771105748.313279);
    expect(formatted.includes('1970')).toBe(false);
  });

  it('handles ISO string input', () => {
    const formatted = formatDateTimeValue('2026-02-17T12:34:56Z');
    expect(formatted).not.toBe('—');
  });
});
