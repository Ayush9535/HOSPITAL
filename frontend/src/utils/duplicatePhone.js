/**
 * Reading the one structured error the API returns: a registration that landed on a phone number
 * an active patient of this hospital already holds.
 *
 * A phone number is a lookup key, not an identity — a parent and a child legitimately share one
 * mobile — so the server refuses to guess which of them the caller meant and hands back the
 * candidates instead. Every caller that creates a patient has to be able to tell that shape of
 * 409 apart from an ordinary failure, which is why it is recognised in one place.
 */

/**
 * The matched patients carried by a duplicate-phone 409, or null if this is any other error.
 *
 * Returns null rather than an empty array for a non-conflict, so callers can write
 * `const conflicts = extractPhoneConflicts(err); if (conflicts) { ... }` and keep their existing
 * error handling for everything else.
 *
 * @param {unknown} err a caught Axios error
 * @returns {Array<{id:number, publicId:string, customId:string, name:string, age:number}>|null}
 */
export const extractPhoneConflicts = (err) => {
  if (err?.response?.status !== 409) return null;
  const conflicts = err?.response?.data?.conflicts;
  if (!Array.isArray(conflicts) || conflicts.length === 0) return null;
  return conflicts;
};

export default extractPhoneConflicts;
