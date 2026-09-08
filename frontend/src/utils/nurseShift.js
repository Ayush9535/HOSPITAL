/**
 * How a nurse's shift is described, in one place.
 *
 * The admin's staff list showed no shift at all while the incharge's roster showed a live one, so
 * the same nurse read differently on two screens. The server now answers both from a single
 * resolver; this makes them say it the same way too.
 *
 * A null/absent shift means nothing is rostered for today. That is stated plainly rather than
 * left blank, because a blank cell reads as "not loaded" and invites the reader to assume a shift
 * exists somewhere.
 */
export const NO_SHIFT = 'No shift assigned';

/** "Morning · 07:00–15:00", or "07:00–15:00" when the template has no name. */
export const describeShift = (row) => {
  if (!row) return NO_SHIFT;
  const { shiftName, shiftStartTime, shiftEndTime } = row;
  const window =
    shiftStartTime && shiftEndTime ? `${hhmm(shiftStartTime)}–${hhmm(shiftEndTime)}` : null;
  if (!shiftName && !window) return NO_SHIFT;
  return [shiftName, window].filter(Boolean).join(' · ');
};

/** Whether the nurse is inside that shift right now. Never inferred here — the server decides. */
export const isOnShiftNow = (row) => !!row?.onShiftNow;

/** Times arrive as LocalTime ("07:00" or "07:00:00"); show hours and minutes only. */
const hhmm = (value) => String(value).slice(0, 5);

export default describeShift;
