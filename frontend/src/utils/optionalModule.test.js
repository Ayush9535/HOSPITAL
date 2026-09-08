import { describe, it, expect, vi } from 'vitest';
import { fetchOptionalModuleData, createOptionalModuleFetcher } from './optionalModule';

/**
 * The invariant under test is the one that made a walk-in-only hospital unusable:
 * an optional module's request must never be able to abort a mandatory one.
 *
 * Every case here corresponds to something that actually happened on a dashboard —
 * a bare `await` at the top of a tab loader, and appointment calls sharing a
 * `Promise.all` with the patient, doctor, queue and follow-up loads.
 */
describe('fetchOptionalModuleData', () => {
  it('does not call the endpoint at all when the tenant lacks the module', async () => {
    const request = vi.fn();

    await expect(fetchOptionalModuleData(false, request, [])).resolves.toEqual([]);
    expect(request).not.toHaveBeenCalled();
  });

  it('preserves a server failure when the module is enabled', async () => {
    const serverError = Object.assign(new Error('Server Error'), { response: { status: 500 } });
    const request = vi.fn().mockRejectedValue(serverError);

    await expect(fetchOptionalModuleData(true, request, { content: [] })).rejects.toBe(serverError);
  });

  it('passes real data through untouched when the tenant holds the module', async () => {
    const payload = { content: [{ id: 1 }], totalElements: 1 };
    const request = vi.fn().mockResolvedValue(payload);

    await expect(fetchOptionalModuleData(true, request, { content: [] })).resolves.toBe(payload);
  });

  it('preserves a synchronous throw as a rejection when the module is enabled', async () => {
    const request = () => {
      throw new TypeError('built a URL from undefined');
    };

    await expect(fetchOptionalModuleData(true, request, [])).rejects.toThrow('built a URL from undefined');
  });

  it('preserves a network rejection rather than pretending the module is empty', async () => {
    const networkError = new Error('Network Error');
    const request = vi.fn().mockRejectedValue(networkError);

    await expect(fetchOptionalModuleData(true, request, null)).rejects.toBe(networkError);
  });

  /**
   * The regression itself. Promise.all rejects on the first failure, which is how one
   * appointment 403 used to take the doctor lookup, the patient lookup and today's follow-ups
   * down with it — follow-ups in particular have nothing to do with appointments and are the
   * walk-in hospital's actual return-visit mechanism.
   */
  it('skips the optional request so mandatory calls in the same Promise.all resolve', async () => {
    const appointments = vi.fn().mockRejectedValue(new Error('403'));
    const doctors = vi.fn().mockResolvedValue(['Dr A']);
    const followUps = vi.fn().mockResolvedValue(['follow-up']);

    const [appts, docs, fups] = await Promise.all([
      fetchOptionalModuleData(false, appointments, { content: [] }),
      doctors(),
      followUps(),
    ]);

    expect(appts).toEqual({ content: [] });
    expect(docs).toEqual(['Dr A']);
    expect(fups).toEqual(['follow-up']);
  });

  it('rejects the same Promise.all for an enabled module failure', async () => {
    const appointments = () => Promise.reject(new Error('403'));
    const doctors = () => Promise.resolve(['Dr A']);

    await expect(
      Promise.all([fetchOptionalModuleData(true, appointments, { content: [] }), doctors()])
    ).rejects.toThrow('403');
  });
});

describe('createOptionalModuleFetcher', () => {
  it('binds the module flag once and skips every call while it is off', async () => {
    const request = vi.fn();
    const fetchAppointmentData = createOptionalModuleFetcher(false);

    await expect(fetchAppointmentData(request, {})).resolves.toEqual({});
    await expect(fetchAppointmentData(request, [])).resolves.toEqual([]);
    expect(request).not.toHaveBeenCalled();
  });

  it('runs and returns the call while the module is on', async () => {
    const fetchAppointmentData = createOptionalModuleFetcher(true);
    const request = vi.fn().mockResolvedValue({ today: 3 });

    await expect(fetchAppointmentData(request, {})).resolves.toEqual({ today: 3 });
    expect(request).toHaveBeenCalledTimes(1);
  });
});
