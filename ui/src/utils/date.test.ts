import { describe, expect, it } from 'vitest';
import { epochSecondsToDate } from './date';

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
