import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import hospitalService from '../../../services/hospitalService';
import icuService from '../../../services/icuService';
import IcuStayCard from './IcuStayCard';

vi.mock('../../../services/icuService', () => ({
  default: { getStaysForAdmission: vi.fn(), setIntensivist: vi.fn() },
}));
vi.mock('../../../services/hospitalService', () => ({
  default: { getDoctors: vi.fn() },
}));
const toastError = vi.fn();
// One stable object, as the real provider returns.
const toast = { success: vi.fn(), error: toastError };
vi.mock('../../../context/ToastContext', () => ({ useToast: () => toast }));

let role = 'DOCTOR';
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: () => ({ id: 42, role }) },
}));

const stay = (o = {}) => ({
  publicId: 'stay-1',
  status: 'ACTIVE',
  admittedAt: '2026-08-26T08:00:00',
  admissionReason: 'Septic shock',
  intensivistDoctorId: null,
  intensivistName: null,
  disposition: null,
  ...o,
});

describe('IcuStayCard — ICU stay intensivist (ICU-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    role = 'DOCTOR';
    icuService.getStaysForAdmission.mockResolvedValue([stay()]);
    icuService.setIntensivist.mockResolvedValue({});
    hospitalService.getDoctors.mockResolvedValue({
      content: [
        { id: 3, name: 'Dr Rao' },
        { id: 4, name: 'Dr Iyer' },
      ],
    });
  });

  it('renders nothing for an admission with no ICU stay', async () => {
    icuService.getStaysForAdmission.mockResolvedValue([]);
    const { container } = render(<IcuStayCard admissionId={7} />);

    await waitFor(() => expect(icuService.getStaysForAdmission).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the stay and says who is responsible when no intensivist is set', async () => {
    render(<IcuStayCard admissionId={7} />);

    expect(await screen.findByText('ICU Stay')).toBeInTheDocument();
    expect(screen.getByText('In ICU')).toBeInTheDocument();
    expect(screen.getByText('Not set')).toBeInTheDocument();
    expect(screen.getByText(/admitting doctor remains responsible/i)).toBeInTheDocument();
  });

  it('shows the resolved intensivist name when one is set', async () => {
    icuService.getStaysForAdmission.mockResolvedValue([
      stay({ intensivistDoctorId: 3, intensivistName: 'Dr Rao' }),
    ]);

    render(<IcuStayCard admissionId={7} />);

    expect(await screen.findByText('Dr Rao')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument();
    expect(screen.queryByText(/admitting doctor remains responsible/i)).not.toBeInTheDocument();
  });

  it('sets the intensivist through the existing endpoint', async () => {
    render(<IcuStayCard admissionId={7} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Set' }));

    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());
    fireEvent.change(await screen.findByLabelText('Intensivist'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(icuService.setIntensivist).toHaveBeenCalledWith('stay-1', 4));
  });

  it('clears the intensivist by choosing the empty option', async () => {
    icuService.getStaysForAdmission.mockResolvedValue([
      stay({ intensivistDoctorId: 3, intensivistName: 'Dr Rao' }),
    ]);

    render(<IcuStayCard admissionId={7} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Change' }));
    fireEvent.change(await screen.findByLabelText('Intensivist'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(icuService.setIntensivist).toHaveBeenCalledWith('stay-1', null));
  });

  it('offers no editor to a role the endpoint would refuse', async () => {
    role = 'NURSE_INCHARGE';
    icuService.getStaysForAdmission.mockResolvedValue([
      stay({ intensivistDoctorId: 3, intensivistName: 'Dr Rao' }),
    ]);

    render(<IcuStayCard admissionId={7} />);

    // Readable, not editable — the same two roles the server already allows.
    expect(await screen.findByText('Dr Rao')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument();
  });

  it('offers no editor on a closed stay', async () => {
    // ICU-1: a closed stay can no longer be changed, and the server enforces it.
    icuService.getStaysForAdmission.mockResolvedValue([
      stay({
        status: 'DISCHARGED',
        disposition: 'WARD',
        intensivistDoctorId: 3,
        intensivistName: 'Dr Rao',
      }),
    ]);

    render(<IcuStayCard admissionId={7} />);

    expect(await screen.findByText(/Closed/)).toBeInTheDocument();
    expect(screen.getByText('Dr Rao')).toBeInTheDocument();
    // Neither label: an intensivist IS set here, so "Change" is the one that would appear if the
    // closed-stay gate were missing. Asserting both stops this passing for the wrong reason.
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set' })).not.toBeInTheDocument();
  });

  it('keeps earlier stays readable and notes they are not editable', async () => {
    icuService.getStaysForAdmission.mockResolvedValue([
      stay({ publicId: 'stay-2' }),
      stay({ publicId: 'stay-1', status: 'DISCHARGED', disposition: 'WARD' }),
    ]);

    render(<IcuStayCard admissionId={7} />);

    expect(await screen.findByText(/2 ICU stays on this admission/)).toBeInTheDocument();
    // The newest stay is the one on show and the one that can be edited.
    expect(screen.getByText('In ICU')).toBeInTheDocument();
  });

  it('surfaces a server refusal without changing what is displayed', async () => {
    icuService.setIntensivist.mockRejectedValue({
      response: { data: { error: 'Doctor not found' } },
    });

    render(<IcuStayCard admissionId={7} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Set' }));
    fireEvent.change(await screen.findByLabelText('Intensivist'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Doctor not found'));
    expect(screen.getByLabelText('Intensivist')).toBeInTheDocument();
  });

  it('re-reads on a realtime refresh', async () => {
    const { rerender } = render(<IcuStayCard admissionId={7} refreshKey={0} />);
    await screen.findByText('Not set');

    icuService.getStaysForAdmission.mockResolvedValue([
      stay({ intensivistDoctorId: 3, intensivistName: 'Dr Rao' }),
    ]);
    rerender(<IcuStayCard admissionId={7} refreshKey={1} />);

    expect(await screen.findByText('Dr Rao')).toBeInTheDocument();
  });
});
