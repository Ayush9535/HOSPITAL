import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import icuService from '../../../services/icuService';
import InfusionPanel from './InfusionPanel';

vi.mock('../../../services/icuService', () => ({
  default: {
    getInfusions: vi.fn(),
    getInfusionRates: vi.fn(),
    getInfusionRateUnits: vi.fn(),
    startInfusion: vi.fn(),
    titrateInfusion: vi.fn(),
    stopInfusion: vi.fn(),
    correctInfusionRate: vi.fn(),
    getIoBalance: vi.fn(),
  },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: vi.fn(() => ({ id: 42 })) },
}));

const UNITS = [
  { key: 'ML_HR', label: 'mL/hr' },
  { key: 'MCG_MIN', label: 'mcg/min' },
  { key: 'MCG_KG_MIN', label: 'mcg/kg/min' },
  { key: 'UNITS_HR', label: 'units/hr' },
];

const infusion = (o = {}) => ({
  id: 1,
  publicId: 'inf-1',
  medicineName: 'Noradrenaline',
  startedAt: '2026-08-26T08:00:00',
  stoppedAt: null,
  stopReason: null,
  prescriptionId: null,
  ...o,
});

const rate = (o = {}) => ({
  id: 1,
  publicId: 'rate-1',
  rateValue: '5.000',
  rateUnit: 'ML_HR',
  effectiveFrom: '2026-08-26T08:00:00',
  supersedesRateId: null,
  recordedByUserId: 42,
  ...o,
});

describe('InfusionPanel — ICU continuous infusions (ICU-6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    icuService.getInfusions.mockResolvedValue([]);
    icuService.getInfusionRates.mockResolvedValue([]);
    icuService.getInfusionRateUnits.mockResolvedValue(UNITS);
  });

  it('says there are no infusions when none has been started', async () => {
    render(<InfusionPanel admissionId={7} />);
    expect(await screen.findByText('No infusions')).toBeInTheDocument();
  });

  it('shows a running infusion with the rate currently in force', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([
      rate({ id: 2, publicId: 'rate-2', rateValue: '8.000', effectiveFrom: '2026-08-26T10:00:00' }),
      rate(),
    ]);

    render(<InfusionPanel admissionId={7} />);

    expect(await screen.findByText('Noradrenaline')).toBeInTheDocument();
    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.getByText('Current rate')).toBeInTheDocument();
    // The newest non-superseded rate, in the unit it was entered in.
    expect(screen.getByText('8 mL/hr')).toBeInTheDocument();
  });

  it('keeps the earlier rate on the chart after a titration', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([
      rate({ id: 2, publicId: 'rate-2', rateValue: '8.000', effectiveFrom: '2026-08-26T10:00:00' }),
      rate(),
    ]);

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Rate history (2)'));

    // Both rates are readable: the history is the reason the phase exists.
    expect(screen.getAllByText('8 mL/hr').length).toBeGreaterThan(0);
    expect(screen.getByText('5 mL/hr')).toBeInTheDocument();
  });

  it('strikes through a rate a correction superseded and marks both rows', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([
      rate({ id: 2, publicId: 'rate-2', rateValue: '15.000', supersedesRateId: 1 }),
      rate(),
    ]);

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Rate history (2)'));

    expect(screen.getByText('Correction')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    // The original is still on screen, struck through rather than removed.
    const original = screen.getByText('5 mL/hr').closest('li');
    expect(original.className).toContain('line-through');
    // The corrected value is the one in force.
    expect(screen.getAllByText('15 mL/hr').length).toBeGreaterThan(0);
  });

  it('records a titration through the append endpoint, not an edit', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);
    icuService.titrateInfusion.mockResolvedValue({});

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Change rate'));
    fireEvent.change(screen.getByLabelText('New rate'), { target: { value: '8' } });
    fireEvent.click(screen.getByText('Record rate change'));

    await waitFor(() =>
      expect(icuService.titrateInfusion).toHaveBeenCalledWith('inf-1', {
        rateValue: '8',
        rateUnit: 'ML_HR',
      })
    );
  });

  it('starts an infusion with the drug, rate and the unit as chosen', async () => {
    icuService.startInfusion.mockResolvedValue({});

    render(<InfusionPanel admissionId={7} />);
    await screen.findByText('Start an Infusion');
    await waitFor(() => expect(screen.getByLabelText('Unit')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Drug'), { target: { value: 'Fentanyl' } });
    fireEvent.change(screen.getByLabelText('Rate'), { target: { value: '0.05' } });
    fireEvent.change(screen.getByLabelText('Unit'), { target: { value: 'MCG_KG_MIN' } });
    fireEvent.click(screen.getByText('Start'));

    await waitFor(() =>
      expect(icuService.startInfusion).toHaveBeenCalledWith({
        ipdAdmissionId: 7,
        medicineName: 'Fentanyl',
        rateValue: '0.05',
        // Stored in the unit entered; the panel never converts it.
        rateUnit: 'MCG_KG_MIN',
      })
    );
  });

  it('stops an infusion with an optional reason', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);
    icuService.stopInfusion.mockResolvedValue({});

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Stop'));
    fireEvent.change(screen.getByLabelText('Reason (optional)'), {
      target: { value: 'weaned off' },
    });
    fireEvent.click(screen.getByText('Stop infusion'));

    await waitFor(() =>
      expect(icuService.stopInfusion).toHaveBeenCalledWith('inf-1', { stopReason: 'weaned off' })
    );
  });

  it('keeps a stopped infusion and its history readable', async () => {
    icuService.getInfusions.mockResolvedValue([
      infusion({ stoppedAt: '2026-08-26T12:00:00', stopReason: 'weaned off' }),
    ]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);

    render(<InfusionPanel admissionId={7} />);

    // Both the section heading and the card's status badge say "Stopped".
    expect(await screen.findAllByText('Stopped')).not.toHaveLength(0);
    expect(screen.getByText('Last rate')).toBeInTheDocument();
    expect(screen.getByText('5 mL/hr')).toBeInTheDocument();
    // A stopped infusion offers no rate change and no stop.
    expect(screen.queryByText('Change rate')).not.toBeInTheDocument();
  });

  it('offers a correction only for a rate this user recorded', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate({ recordedByUserId: 99 })]);

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Rate history (1)'));

    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
  });

  it('sends a correction to the correction endpoint, keyed by the rate', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);
    icuService.correctInfusionRate.mockResolvedValue({});

    render(<InfusionPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Rate history (1)'));
    fireEvent.click(screen.getByText('Correct'));
    fireEvent.change(screen.getByLabelText('Corrected rate'), { target: { value: '15' } });
    fireEvent.click(screen.getByText('Save correction'));

    await waitFor(() =>
      expect(icuService.correctInfusionRate).toHaveBeenCalledWith('rate-1', {
        rateValue: '15',
        rateUnit: 'ML_HR',
      })
    );
  });

  it('hides every write control when the role is read-only', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);

    render(<InfusionPanel admissionId={7} readOnly />);
    await screen.findByText('Noradrenaline');

    expect(screen.queryByText('Start an Infusion')).not.toBeInTheDocument();
    expect(screen.queryByText('Change rate')).not.toBeInTheDocument();
    expect(screen.queryByText('Stop')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Rate history (1)'));
    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
  });

  it('never reads or shows a fluid balance — infusions are not I/O (D-1)', async () => {
    icuService.getInfusions.mockResolvedValue([infusion()]);
    icuService.getInfusionRates.mockResolvedValue([rate()]);

    render(<InfusionPanel admissionId={7} />);
    await screen.findByText('Noradrenaline');

    expect(icuService.getIoBalance).not.toHaveBeenCalled();
    expect(screen.getByText(/not counted in the Intake \/ Output balance/i)).toBeInTheDocument();
  });
});
