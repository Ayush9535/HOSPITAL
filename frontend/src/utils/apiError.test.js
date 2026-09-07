import { describe, expect, it } from 'vitest';
import { extractApiError } from './apiError';

describe('extractApiError', () => {
  it('returns canonical API errors before generic fallback messages', () => {
    expect(extractApiError({ response: { data: { error: 'Duplicate patient phone number' } } }))
      .toBe('Duplicate patient phone number');
  });

  it('normalizes validation error maps into a safe toast message', () => {
    expect(extractApiError({ response: { data: { errors: { name: 'Name is required' } } } }))
      .toBe('Name is required');
  });
});
