import { describe, expect, it } from 'vitest';
import { describeShift, isOnShiftNow, NO_SHIFT } from './nurseShift';

/**
 * The reported inconsistency: the admin saw nothing where the incharge saw a live shift. Both
 * screens now render through this, so "same nurse, same answer" is a property of the code rather
 * than of two screens happening to agree.
 */
describe('nurse shift description', () => {
  it('says so plainly when nothing is rostered today', () => {
    expect(describeShift(null)).toBe(NO_SHIFT);
    expect(describeShift({})).toBe(NO_SHIFT);
    expect(describeShift({ shiftName: null, shiftStartTime: null, shiftEndTime: null })).toBe(NO_SHIFT);
  });

  it('names the shift and its window', () => {
    expect(
      describeShift({ shiftName: 'Morning', shiftStartTime: '07:00:00', shiftEndTime: '15:00:00' })
    ).toBe('Morning · 07:00–15:00');
  });

  it('still shows the window when the template has no name', () => {
    expect(describeShift({ shiftStartTime: '22:00', shiftEndTime: '06:00' })).toBe('22:00–06:00');
  });

  it('reports on-shift only when the server says so — it is never inferred here', () => {
    expect(isOnShiftNow({ onShiftNow: true })).toBe(true);
    expect(isOnShiftNow({ shiftName: 'Morning' })).toBe(false);
    expect(isOnShiftNow(null)).toBe(false);
  });

  /** A substitution is written as today's schedule, so it arrives as an ordinary shift. */
  it('renders a substituted shift exactly like any other', () => {
    expect(
      describeShift({ shiftName: 'Night (cover)', shiftStartTime: '22:00:00', shiftEndTime: '06:00:00' })
    ).toBe('Night (cover) · 22:00–06:00');
  });
});
