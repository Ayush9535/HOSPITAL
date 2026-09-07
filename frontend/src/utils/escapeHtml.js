/**
 * Escape a value for safe interpolation into HTML — both element text AND attribute values.
 *
 * The print/consent forms build raw HTML strings (patient names, notes, free-text answers) and
 * hand them to `printHtml`. Those values are user-controlled, so every one must be escaped before
 * it lands in the markup. Escaping `& < >` alone is NOT enough: a value dropped inside a
 * double-quoted attribute (`value="${escapeHtml(x)}"`) can still break out with a `"` and inject
 * markup. This escapes the full set — `& < > " '` — so the same helper is safe in text and
 * attribute contexts alike. Consolidated from ~20 identical inline copies that each missed the
 * quote characters (CWE-79 / CodeQL "Incomplete HTML attribute sanitization").
 *
 * @param {*} v any value; null/undefined become an empty string
 * @returns {string} the HTML-escaped string
 */
export const escapeHtml = (v) =>
  v == null
    ? ''
    : String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');

export default escapeHtml;
