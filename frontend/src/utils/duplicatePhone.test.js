import { describe, it, expect } from 'vitest';
import { extractPhoneConflicts } from './duplicatePhone';

/**
 * Every patient-creating flow has to tell "this number already belongs to someone here" apart
 * from an ordinary failure, so the recognition lives in one place and is pinned here.
 */
describe('extractPhoneConflicts', () => {
  const conflict = (conflicts) => ({ response: { status: 409, data: { conflicts } } });

  it('returns the matched patients from a duplicate-phone 409', () => {
    const matches = [
      { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 },
      { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 },
    ];

    expect(extractPhoneConflicts(conflict(matches))).toEqual(matches);
  });

  it('ignores a 409 that carries no conflicts — other races answer 409 too', () => {
    expect(
      extractPhoneConflicts({ response: { status: 409, data: { error: 'Bed already taken' } } })
    ).toBeNull();
    expect(extractPhoneConflicts(conflict([]))).toBeNull();
  });

  it('ignores every other status, including a 400 that happens to carry a list', () => {
    expect(
      extractPhoneConflicts({ response: { status: 400, data: { conflicts: [{ id: 1 }] } } })
    ).toBeNull();
    expect(extractPhoneConflicts({ response: { status: 500, data: {} } })).toBeNull();
  });

  it('survives a network error with no response at all', () => {
    expect(extractPhoneConflicts(new Error('Network Error'))).toBeNull();
    expect(extractPhoneConflicts(undefined)).toBeNull();
  });
});
