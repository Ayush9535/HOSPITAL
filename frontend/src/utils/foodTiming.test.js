import { describe, expect, it } from 'vitest';
import { describeFoodTiming, FOOD_TIMING_OPTIONS } from './foodTiming';

describe('food timing', () => {
  it('offers exactly the vocabulary the server accepts', () => {
    expect(FOOD_TIMING_OPTIONS.map(([v]) => v)).toEqual([
      'BEFORE_FOOD',
      'AFTER_FOOD',
      'WITH_FOOD',
      'NOT_SPECIFIED',
    ]);
  });

  it('labels the known values readably', () => {
    expect(describeFoodTiming('BEFORE_FOOD')).toBe('Before food');
    expect(describeFoodTiming('WITH_FOOD')).toBe('With food');
  });

  it('shows nothing when the order never stated one', () => {
    expect(describeFoodTiming(null)).toBeNull();
    expect(describeFoodTiming('')).toBeNull();
  });

  /** Historical and unrecognised values must render, not disappear. */
  it('renders an unrecognised value as written rather than hiding it', () => {
    expect(describeFoodTiming('After meals, twice')).toBe('After meals, twice');
    expect(describeFoodTiming('EMPTY_STOMACH')).toBe('EMPTY_STOMACH');
  });
});
