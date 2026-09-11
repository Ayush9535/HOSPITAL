import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Booking an appointment for a walk-in whose mobile number is already registered here.
 *
 * This is the path that used to answer the question by itself: it reused the first active patient
 * with that number. A parent and a child share one mobile, so "first" could be the parent while
 * the child is the one being booked — and the appointment, the case and everything downstream
 * would land on the wrong record. Now the candidates come back and reception decides.
 */

const toastError = vi.fn();
const toastSuccess = vi.fn();
vi.spyOn(console, 'error').mockImplementation(() => {});

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError }),
}));

const hospitalService = {
  createAppointment: vi.fn(),
  getAppointmentsByDoctor: vi.fn().mockResolvedValue([]),
};
vi.mock('../services/hospitalService', () => ({ default: hospitalService }));

const { default: AppointmentModal } = await import('./AppointmentModal');

const parent = { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 };
const child = { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 };

const duplicatePhone409 = (conflicts) => ({
  response: { status: 409, data: { code: 'CONFLICT', error: 'already registered', conflicts } },
});

const onSuccess = vi.fn();
const onClose = vi.fn();
const doctors = [{ id: 1, name: 'Dr Mandal' }];

const tomorrow = () => {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return d;
};

const renderModal = () =>
  render(
    <AppointmentModal
      isOpen
      onClose={onClose}
      onSuccess={onSuccess}
      doctors={doctors}
      patients={[]}
    />
  );

/** Fills the booking as a brand-new walk-in and submits it. */
const bookNewPatient = async (user) => {
  await user.click(screen.getByLabelText(/New Patient/i));
  await user.type(screen.getByLabelText(/Patient Name/i), 'Aarav Patil');
  await user.type(screen.getByLabelText(/Phone/i), '9876500001');

  // Two date pickers are on screen — the date of birth first, the appointment date second.
  await user.selectOptions(screen.getAllByLabelText('Day')[0], '21');
  await user.selectOptions(screen.getAllByLabelText('Month')[0], '09');
  await user.selectOptions(screen.getAllByLabelText('Year')[0], '2017');
  await user.selectOptions(screen.getByLabelText(/Gender/i), 'Male');

  // A single doctor is assigned automatically and rendered read-only, so there is nothing to pick.

  const when = tomorrow();
  await user.selectOptions(screen.getAllByLabelText('Year')[1], String(when.getFullYear()));
  await user.selectOptions(
    screen.getAllByLabelText('Month')[1],
    String(when.getMonth() + 1).padStart(2, '0')
  );
  await user.selectOptions(
    screen.getAllByLabelText('Day')[1],
    String(when.getDate()).padStart(2, '0')
  );

  const slots = await screen.findAllByRole('button', { name: /Select slot for/i });
  await user.click(slots[0]);
  await user.click(screen.getByRole('button', { name: 'Schedule' }));
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AppointmentModal — duplicate phone', () => {
  it('offers the patients on that number rather than picking one', async () => {
    const user = userEvent.setup();
    hospitalService.createAppointment.mockRejectedValueOnce(duplicatePhone409([parent, child]));
    renderModal();

    await bookNewPatient(user);

    await screen.findByText('Mobile number already registered');
    expect(screen.getByText('Rahul Patil')).toBeInTheDocument();
    expect(screen.getByText('Aarav Patil')).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(toastError).not.toHaveBeenCalled();
  });

  /** The regression this whole checkpoint is about: the child, not whoever came first. */
  it('books against the exact patient chosen, not the first match', async () => {
    const user = userEvent.setup();
    hospitalService.createAppointment
      .mockRejectedValueOnce(duplicatePhone409([parent, child]))
      .mockResolvedValueOnce({ id: 900 });
    renderModal();
    await bookNewPatient(user);
    await screen.findByText('Mobile number already registered');

    const childRow = screen.getByText('Aarav Patil').closest('div.flex');
    await user.click(within(childRow).getByRole('button', { name: 'Use This Patient' }));

    await waitFor(() => expect(hospitalService.createAppointment).toHaveBeenCalledTimes(2));
    expect(hospitalService.createAppointment.mock.calls[1][0]).toMatchObject({ patientId: 145 });
    expect(hospitalService.createAppointment.mock.calls[1][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
  });

  it('"Register Different Patient" books a new person with an explicit acknowledgement', async () => {
    const user = userEvent.setup();
    hospitalService.createAppointment
      .mockRejectedValueOnce(duplicatePhone409([parent]))
      .mockResolvedValueOnce({ id: 901 });
    renderModal();
    await bookNewPatient(user);
    await screen.findByText('Mobile number already registered');

    await user.click(screen.getByRole('button', { name: 'Register Different Patient' }));

    await waitFor(() => expect(hospitalService.createAppointment).toHaveBeenCalledTimes(2));
    expect(hospitalService.createAppointment.mock.calls[0][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
    expect(hospitalService.createAppointment.mock.calls[1][1]).toMatchObject({
      acknowledgeDuplicatePhone: true,
    });
    // Still the walk-in's own details; no patient id was substituted behind the scenes.
    expect(hospitalService.createAppointment.mock.calls[1][0]).toMatchObject({
      patientName: 'Aarav Patil',
      patientPhone: '9876500001',
    });
    expect(hospitalService.createAppointment.mock.calls[1][0].patientId).toBeUndefined();
  });

  it('an ordinary failure is still reported as a failure', async () => {
    const user = userEvent.setup();
    hospitalService.createAppointment.mockRejectedValueOnce({
      response: { status: 400, data: { error: 'Doctor is not available' } },
    });
    renderModal();

    await bookNewPatient(user);

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Doctor is not available'));
    expect(screen.queryByText('Mobile number already registered')).not.toBeInTheDocument();
  });
});
