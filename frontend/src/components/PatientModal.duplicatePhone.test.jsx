import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Add Patient meets a mobile number that already belongs to a patient here.
 *
 * This modal is the Add Patient form on the receptionist, admin and doctor dashboards, so the
 * behaviour pinned here is the one all three get. The rule: a match is a question for the person
 * at the desk, never an answer the software supplies. Picking an existing patient must create
 * nobody; registering a different person must say so explicitly.
 */

const toastError = vi.fn();
const toastSuccess = vi.fn();
vi.spyOn(console, 'error').mockImplementation(() => {});
vi.spyOn(console, 'log').mockImplementation(() => {});

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError }),
}));

const hospitalService = { addPatient: vi.fn(), updatePatient: vi.fn() };
vi.mock('../services/hospitalService', () => ({ default: hospitalService }));

const { default: PatientModal } = await import('./PatientModal');

const parent = { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 };
const child = { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 };

const duplicatePhone409 = (conflicts) => ({
  response: { status: 409, data: { code: 'CONFLICT', error: 'already registered', conflicts } },
});

const onSuccess = vi.fn();
const onClose = vi.fn();

const renderModal = () =>
  render(<PatientModal isOpen onClose={onClose} onSuccess={onSuccess} initialData={null} />);

const fillAndSubmit = async (user) => {
  await user.type(screen.getByPlaceholderText(/full name/i), 'Aarav Patil');
  await user.type(screen.getByPlaceholderText(/phone number/i), '9876500001');
  await user.selectOptions(screen.getByLabelText('Day'), '21');
  await user.selectOptions(screen.getByLabelText('Month'), '09');
  await user.selectOptions(screen.getByLabelText('Year'), '2017');
  await user.selectOptions(screen.getByLabelText(/Gender/i), 'MALE');
  await user.click(screen.getByRole('button', { name: /Add Patient|Save|Create/i }));
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('PatientModal — duplicate phone', () => {
  it('shows the existing patients instead of a failure toast', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient.mockRejectedValueOnce(duplicatePhone409([parent, child]));
    renderModal();

    await fillAndSubmit(user);

    await screen.findByText('Mobile number already registered');
    expect(screen.getByText('Rahul Patil')).toBeInTheDocument();
    expect(screen.getByText('Aarav Patil')).toBeInTheDocument();
    expect(toastError).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('"Use This Patient" hands that patient back without creating one', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient.mockRejectedValueOnce(duplicatePhone409([parent, child]));
    renderModal();
    await fillAndSubmit(user);
    await screen.findByText('Mobile number already registered');

    const childRow = screen.getByText('Aarav Patil').closest('div.flex');
    await user.click(within(childRow).getByRole('button', { name: 'Use This Patient' }));

    expect(onSuccess).toHaveBeenCalledWith(child);
    expect(onClose).toHaveBeenCalled();
    // Only the attempt that was refused. Nothing was registered a second time.
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
  });

  it('"Register Different Patient" resubmits with an explicit acknowledgement', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient
      .mockRejectedValueOnce(duplicatePhone409([parent]))
      .mockResolvedValueOnce({ id: 146, name: 'Aarav Patil', customId: 'PAT146' });
    renderModal();
    await fillAndSubmit(user);
    await screen.findByText('Mobile number already registered');

    await user.click(screen.getByRole('button', { name: 'Register Different Patient' }));

    await waitFor(() => expect(hospitalService.addPatient).toHaveBeenCalledTimes(2));
    expect(hospitalService.addPatient.mock.calls[0][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
    expect(hospitalService.addPatient.mock.calls[1][1]).toMatchObject({
      acknowledgeDuplicatePhone: true,
    });
    expect(onSuccess).toHaveBeenCalledWith({ id: 146, name: 'Aarav Patil', customId: 'PAT146' });
  });

  it('cancelling returns to the form with the details still typed in', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient.mockRejectedValueOnce(duplicatePhone409([parent]));
    renderModal();
    await fillAndSubmit(user);
    await screen.findByText('Mobile number already registered');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByPlaceholderText(/full name/i)).toHaveValue('Aarav Patil');
    expect(screen.getByPlaceholderText(/phone number/i)).toHaveValue('9876500001');
    expect(onSuccess).not.toHaveBeenCalled();
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
  });

  it('an ordinary failure is still an ordinary failure', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient.mockRejectedValueOnce({
      response: { status: 400, data: { error: 'Phone number must be exactly 10 digits' } },
    });
    renderModal();

    await fillAndSubmit(user);

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(screen.queryByText('Mobile number already registered')).not.toBeInTheDocument();
  });

  it('a clean registration asks nothing and passes no acknowledgement', async () => {
    const user = userEvent.setup();
    hospitalService.addPatient.mockResolvedValueOnce({ id: 9, name: 'Aarav Patil' });
    renderModal();

    await fillAndSubmit(user);

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(hospitalService.addPatient.mock.calls[0][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
    expect(screen.queryByText('Mobile number already registered')).not.toBeInTheDocument();
  });
});
