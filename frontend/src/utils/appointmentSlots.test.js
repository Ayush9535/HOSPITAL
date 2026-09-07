import { describe, expect, it } from 'vitest';
import { buildDaySlots, availableSlotsFor, SLOT_INTERVAL_MINUTES } from './appointmentSlots';

/**
 * The picker offered 09:00 to 17:00 and nothing else. A hospital running nights could not book
 * outside it at all, and because the same-day filter cut into that fixed range, after 17:00 there
 * were no slots left and same-day booking stopped working for the rest of the shift.
 */
describe('appointment slots', () => {
  it('covers the whole day, from midnight to the last half hour', () => {
    const slots = buildDaySlots();
    expect(slots).toHaveLength(48);
    expect(slots[0]).toBe('00:00');
    expect(slots[slots.length - 1]).toBe('23:30');
  });

  it('includes the hours the old fixed range could not express', () => {
    const slots = buildDaySlots();
    for (const slot of ['00:00', '00:30', '03:30', '08:30', '17:30', '22:00', '23:30']) {
      expect(slots).toContain(slot);
    }
  });

  it('still offers every working-hours slot it always had', () => {
    const slots = buildDaySlots();
    for (const slot of ['09:00', '09:30', '12:30', '16:30', '17:00']) {
      expect(slots).toContain(slot);
    }
  });

  it('stays on the 30-minute boundary the server enforces', () => {
    expect(SLOT_INTERVAL_MINUTES).toBe(30);
    // Offering a time the booking rule would reject trades one broken screen for another.
    for (const slot of buildDaySlots()) {
      expect(slot).toMatch(/^\d\d:(00|30)$/);
    }
  });

  it('offers the whole day for a future date', () => {
    expect(availableSlotsFor('2027-05-11', '2027-05-10', '16:45')).toHaveLength(48);
  });

  it('drops only the times already past when the date is today', () => {
    const remaining = availableSlotsFor('2027-05-10', '2027-05-10', '16:45');
    expect(remaining).not.toContain('16:30');
    expect(remaining).toContain('17:00');
    expect(remaining).toContain('23:30');
  });

  /** The exact evening failure: after 17:00 there used to be nothing left to pick. */
  it('still has slots left after the old 17:00 cut-off', () => {
    const remaining = availableSlotsFor('2027-05-10', '2027-05-10', '18:10');
    expect(remaining.length).toBeGreaterThan(0);
    expect(remaining[0]).toBe('18:30');
  });

  it('offers everything before a date is chosen', () => {
    expect(availableSlotsFor('', '2027-05-10', '16:45')).toHaveLength(48);
  });
});
