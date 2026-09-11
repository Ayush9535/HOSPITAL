/**
 * Title-case a string: capitalize the first letter of every word.
 * e.g. "hospital one" -> "Hospital One". Safe for null/undefined.
 */
export const titleCase = (str) => {
  if (!str) return str || '';
  return String(str)
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
};

export default titleCase;
