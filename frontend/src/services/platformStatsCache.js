/**
 * Short-lived cache for the Super Admin overview counts.
 *
 * Lifted out of PlatformDashboard so two things become possible that a private
 * module-level object could not do: a mutation elsewhere in the page can invalidate it,
 * and logout can drop it. The mechanism itself is unchanged — the same object with the
 * same 60-second TTL, avoiding a refetch every time the dashboard tab is revisited.
 *
 * Entries are stamped with the session that fetched them. sessionStorage is per tab, so
 * signing out and signing in as someone else reuses the same module instance; without the
 * owner check the next admin would be shown the previous one's counts until the TTL ran
 * out. A changed owner invalidates immediately rather than being merely stale.
 */
const TTL_MS = 60_000;

const currentOwner = () => {
  try {
    return sessionStorage.getItem('token');
  } catch {
    // storage unavailable (private mode, blocked cookies) — treat every read as a miss
    return null;
  }
};

const statsCache = {
  data: null,
  fetchedAt: 0,
  owner: null,
  TTL_MS,

  isValid() {
    return (
      this.data !== null &&
      this.owner === currentOwner() &&
      Date.now() - this.fetchedAt < this.TTL_MS
    );
  },

  set(data) {
    this.data = data;
    this.fetchedAt = Date.now();
    this.owner = currentOwner();
  },

  clear() {
    this.data = null;
    this.fetchedAt = 0;
    this.owner = null;
  },
};

export default statsCache;
