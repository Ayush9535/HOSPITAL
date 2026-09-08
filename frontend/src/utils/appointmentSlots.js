/**
 * The bookable times in a day, and which of them are still available.
 *
 * Extracted from AppointmentModal so the rule is stated once and can be checked directly: the
 * modal only renders this list once a date and a doctor are chosen, which made the rule itself
 * hard to see and impossible to test on its own.
 */

/** Minutes between one bookable start time and the next. */
export const SLOT_INTERVAL_MINUTES = 30;

/**
 * Every bookable start time in a day, 00:00 through 23:30.
 *
 * The list used to run 09:00 to 17:00. That was not a rule anyone had configured — it was the
 * range someone typed — and it made the product unusable for a hospital running nights or a
 * clinic with evening hours. Worse, on the current day the past-time filter below cut into that
 * fixed range, so after 17:00 there was nothing left and same-day booking stopped working
 * entirely for the rest of the shift.
 *
 * The interval stays at 30 minutes because the server enforces exactly that: it refuses a second
 * appointment starting at an identical time for the same doctor. Offering times the server would
 * reject would trade one broken screen for another.
 */
export const buildDaySlots = () =>
  Array.from({ length: (24 * 60) / SLOT_INTERVAL_MINUTES }, (_, i) => {
    const totalMinutes = i * SLOT_INTERVAL_MINUTES;
    const hours = String(Math.floor(totalMinutes / 60)).padStart(2, '0');
    const minutes = String(totalMinutes % 60).padStart(2, '0');
    return `${hours}:${minutes}`;
  });

/**
 * The slots a user may still pick for a given date.
 *
 * Times already past are dropped only when the chosen date is today; a future date offers the
 * whole day. Comparison is lexicographic, which is exact for zero-padded HH:mm.
 *
 * @param {string} selectedDate  yyyy-MM-dd, or empty when nothing is chosen yet
 * @param {string} todayString   yyyy-MM-dd for the user's own calendar day
 * @param {string} currentTime   HH:mm now, in the user's own clock
 */
export const availableSlotsFor = (selectedDate, todayString, currentTime) => {
  const slots = buildDaySlots();
  if (!selectedDate) return slots;
  if (selectedDate !== todayString) return slots;
  return slots.filter((slot) => slot > currentTime);
};

export default buildDaySlots;
