/**
 * Guards for data that belongs to an OPTIONAL tenant module.
 *
 * A tenant's plan decides which modules it holds, and an endpoint behind a module the tenant does
 * not hold answers 403. That is a normal, expected answer — not a fault — so a dashboard must
 * treat it as "this tenant has no such data", never as "loading failed".
 *
 * Getting this wrong is not a cosmetic bug. Appointments are optional, and the appointment calls
 * on the admin and receptionist dashboards were awaited bare at the top of the tab loader and
 * bundled into shared `Promise.all` blocks alongside the patient, doctor, queue and follow-up
 * loads. `Promise.all` rejects on the first failure, so for a walk-in-only hospital a single
 * appointment 403 emptied every tab of both dashboards behind one "Failed to load data" toast.
 *
 * The rule this encodes: an optional module's request is absent only when the module is absent.
 * A failure from an enabled module remains a real failure for the dashboard's normal error path.
 */

/**
 * Run `request` only if the tenant holds the module.
 *
 * @param {boolean} enabled  whether the tenant holds the module
 * @param {() => Promise<any>} request  the call to make; NOT invoked when `enabled` is false
 * @param {any} fallback  value to resolve with when skipped.
 * @returns {Promise<any>} resolves to the fallback only when the module is disabled
 */
export const fetchOptionalModuleData = (enabled, request, fallback) => {
  if (!enabled) {
    return Promise.resolve(fallback);
  }
  // An enabled module failing is an operational error, not an empty state. Defer invocation so
  // synchronous throws follow the same rejection path as API failures.
  return Promise.resolve().then(request);
};

/**
 * Bind {@link fetchOptionalModuleData} to one module flag, so a component reads
 * `fetchAppointmentData(() => api.getX(), [])` at each call site.
 */
export const createOptionalModuleFetcher =
  (enabled) =>
  (request, fallback) =>
    fetchOptionalModuleData(enabled, request, fallback);
