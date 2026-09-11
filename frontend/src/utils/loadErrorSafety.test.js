import { describe, expect, it } from 'vitest';
import { safeLoadMessage } from './apiError';

/**
 * The one rule every failed-load banner depends on: show what the API said, never what the JVM
 * or the transport said. A raw `java.lang.NullPointerException at com.hms…` reached a user-facing
 * banner during testing through `err.message`, which is why this helper exists separately from
 * extractApiError.
 */
describe('safeLoadMessage', () => {
  it('prefers the error field the API deliberately returned', () => {
    expect(safeLoadMessage({ response: { data: { error: 'Ward is closed' } } }, 'fallback'))
      .toBe('Ward is closed');
  });

  it('falls back to the message field of the response body', () => {
    expect(safeLoadMessage({ response: { data: { message: 'Try later' } } }, 'fallback'))
      .toBe('Try later');
  });

  it('accepts a plain string body', () => {
    expect(safeLoadMessage({ response: { data: 'Upstream timeout' } }, 'fallback'))
      .toBe('Upstream timeout');
  });

  it('never surfaces err.message, however tempting it looks', () => {
    const err = new Error('java.lang.NullPointerException at com.hms.service.PatientService');
    expect(safeLoadMessage(err, "Couldn't load patients.")).toBe("Couldn't load patients.");
  });

  it('uses the fallback for a network error with no response at all', () => {
    expect(safeLoadMessage({ code: 'ERR_NETWORK' }, "Couldn't load patients."))
      .toBe("Couldn't load patients.");
  });

  it('uses the fallback when the body carries a non-string error', () => {
    expect(safeLoadMessage({ response: { data: { error: { code: 500 } } } }, 'fallback'))
      .toBe('fallback');
  });

  it('survives null and undefined', () => {
    expect(safeLoadMessage(null, 'fallback')).toBe('fallback');
    expect(safeLoadMessage(undefined, 'fallback')).toBe('fallback');
  });

  it('ignores a blank string body', () => {
    expect(safeLoadMessage({ response: { data: '   ' } }, 'fallback')).toBe('fallback');
  });
});
