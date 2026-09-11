import { describe, it, expect } from 'vitest';
import { formatDate, formatDateTime, formatTime } from './date';

describe('date formatters', () => {
  it('return a dash for empty/invalid input', () => {
    expect(formatDate('')).toBe('-');
    expect(formatDate(null)).toBe('-');
    expect(formatDate('not-a-date')).toBe('-');
    expect(formatDateTime('')).toBe('-');
    expect(formatDateTime('nonsense')).toBe('-');
    expect(formatTime('')).toBe('-');
    expect(formatTime('nonsense')).toBe('-');
  });

  it('format valid ISO input to non-dash strings', () => {
    const iso = '2024-03-15T10:30:00';
    expect(formatDate(iso)).not.toBe('-');
    expect(formatDateTime(iso)).not.toBe('-');
    expect(formatTime(iso)).not.toBe('-');
  });
});
