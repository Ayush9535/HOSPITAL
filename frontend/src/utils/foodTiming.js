/**
 * When a dose is taken relative to food.
 *
 * The vocabulary is the server's (entity/FoodTiming). It lives in its own column rather than
 * inside the free-text instructions field, because that field is general — "take with plenty of
 * water", "crush before giving" — and turning it into a four-value dropdown would have removed
 * the ability to record any of that.
 */
export const FOOD_TIMING_OPTIONS = [
  ['BEFORE_FOOD', 'Before food'],
  ['AFTER_FOOD', 'After food'],
  ['WITH_FOOD', 'With food'],
  ['NOT_SPECIFIED', 'Not specified'],
];

const LABELS = Object.fromEntries(FOOD_TIMING_OPTIONS);

/**
 * How to show a stored value.
 *
 * Anything unrecognised is shown as it was written. Orders predate this field, and an older
 * client could still send something else; a clinical record shows what it holds rather than
 * hiding a value it does not recognise.
 */
export const describeFoodTiming = (value) => {
  if (!value) return null;
  return LABELS[value] ?? String(value);
};

export default describeFoodTiming;
