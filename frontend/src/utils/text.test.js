import { describe, it, expect } from 'vitest';
import { titleCase } from './text';

describe('titleCase', () => {
  it('capitalizes the first letter of every word', () => {
    expect(titleCase('hospital one')).toBe('Hospital One');
    expect(titleCase('OPD consultation')).toBe('Opd Consultation');
  });

  it('handles single words and already-capitalized input', () => {
    expect(titleCase('pharmacy')).toBe('Pharmacy');
    expect(titleCase('Ramesh')).toBe('Ramesh');
  });

  it('is null/undefined/empty safe', () => {
    expect(titleCase('')).toBe('');
    expect(titleCase(null)).toBe('');
    expect(titleCase(undefined)).toBe('');
  });

  it('coerces non-strings without throwing', () => {
    expect(titleCase(123)).toBe('123');
  });
});
