import { describe, expect, it } from 'vitest';
import { toToastMessage } from './ToastContext';

describe('toToastMessage', () => {
  it('normalizes standard API error bodies to a renderable message', () => {
    expect(toToastMessage({ error: 'Ward has occupied beds' })).toBe('Ward has occupied beds');
    expect(toToastMessage({ message: 'Invalid request' })).toBe('Invalid request');
    expect(toToastMessage({ errors: { name: 'Name is required' } })).toBe('Name is required');
  });

  it('never returns an object for an unexpected error body', () => {
    expect(toToastMessage({ unexpected: true })).toBe('Something went wrong. Please try again.');
  });
});
