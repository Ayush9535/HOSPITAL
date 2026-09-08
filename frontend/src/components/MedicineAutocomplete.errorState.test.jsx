import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/hospitalService', () => ({
  default: { searchMedicines: vi.fn() },
}));

import hospitalService from '../services/hospitalService';
import MedicineAutocomplete from './MedicineAutocomplete';

/**
 * A failed catalogue lookup must not read as "this medicine does not exist".
 *
 * <p>The component swallowed the error into an empty list, so a prescriber searching mid-consult
 * saw "No medicines found in catalog. Add to catalog first." — an instruction to create a
 * duplicate entry, given on the strength of a request that never completed.
 */
describe('MedicineAutocomplete when the catalogue search fails', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  const type = async (text) => {
    const user = userEvent.setup();
    render(<MedicineAutocomplete value="" onChange={() => {}} onSelect={() => {}} />);
    await user.type(screen.getByRole('combobox'), text);
  };

  it('says the search failed rather than claiming the catalogue is empty', async () => {
    hospitalService.searchMedicines.mockRejectedValue({
      response: { data: { error: 'Catalogue service unavailable' } },
    });

    await type('para');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Catalogue service unavailable');
    expect(screen.queryByText(/Add to catalog first/i)).not.toBeInTheDocument();
  });

  it('never shows a raw backend exception', async () => {
    hospitalService.searchMedicines.mockRejectedValue(
      new Error('java.lang.NullPointerException at com.hms.service.MedicineService.search'),
    );

    await type('para');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).not.toContain('com.hms');
    expect(alert.textContent).not.toContain('NullPointerException');
    expect(alert).toHaveTextContent(/Couldn't search the medicine catalogue/i);
  });

  it('still reports a genuinely empty catalogue as empty', async () => {
    hospitalService.searchMedicines.mockResolvedValue([]);

    await type('para');

    await waitFor(() => {
      expect(screen.getByText(/Add to catalog first/i)).toBeInTheDocument();
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('clears the failure once a later search succeeds', async () => {
    hospitalService.searchMedicines
      .mockRejectedValueOnce({ response: { data: { error: 'Temporary outage' } } })
      .mockResolvedValue([{ id: 1, name: 'Paracetamol', strength: '500mg' }]);

    const user = userEvent.setup();
    render(<MedicineAutocomplete value="" onChange={() => {}} onSelect={() => {}} />);
    const box = screen.getByRole('combobox');
    await user.type(box, 'para');
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    await user.type(box, 'c');

    await waitFor(() => {
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });
});
