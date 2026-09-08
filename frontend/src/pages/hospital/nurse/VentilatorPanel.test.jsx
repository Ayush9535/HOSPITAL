import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import icuService from '../../../services/icuService';
import VentilatorPanel from './VentilatorPanel';

vi.mock('../../../services/icuService', () => ({
  default: {
    getVentilatorChart: vi.fn(),
    recordVentilatorSetting: vi.fn(),
    correctVentilatorSetting: vi.fn(),
    getEnabledVentilatorParams: vi.fn(),
    getVentilatorModes: vi.fn(),
  },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: vi.fn(() => ({ id: 42 })) },
}));

const MODES = [
  { key: 'VC', label: 'Volume Control (VC)' },
  { key: 'PC', label: 'Pressure Control (PC)' },
];

const param = (key, displayName, category, valueType = 'NUMBER', unit = null) => ({
  key,
  displayName,
  unit,
  category,
  valueType,
  isCustom: false,
  enabled: true,
  publicId: null,
});

const ENABLED = [
  param('mode', 'Mode', 'SETTING', 'MODE'),
  param('fio2', 'FiO₂', 'SETTING', 'NUMBER', '%'),
  param('peep', 'PEEP', 'SETTING', 'NUMBER', 'cmH₂O'),
  param('peak_pressure', 'Peak Pressure', 'OBSERVATION', 'NUMBER', 'cmH₂O'),
];

const entry = (o = {}) => ({
  id: 1,
  publicId: 'vent-1',
  ventilationStatus: 'INVASIVE',
  values: { fio2: 60 },
  observedAt: '2026-08-26T08:00:00',
  recordedByUserId: 42,
  supersedesSettingId: null,
  note: null,
  ...o,
});

const chart = (entries, parameters, supersededIds = []) => ({
  entries,
  parameters,
  supersededIds,
});

const PARAMS_MAP = {
  fio2: { ...param('fio2', 'FiO₂', 'SETTING', 'NUMBER', '%') },
  mode: { ...param('mode', 'Mode', 'SETTING', 'MODE') },
};

describe('VentilatorPanel — ICU ventilator chart (ICU-7)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    icuService.getVentilatorChart.mockResolvedValue(chart([], {}));
    icuService.getEnabledVentilatorParams.mockResolvedValue(ENABLED);
    icuService.getVentilatorModes.mockResolvedValue(MODES);
  });

  it('says nothing has been recorded when the chart is empty', async () => {
    render(<VentilatorPanel admissionId={7} />);
    expect(await screen.findByText('No ventilator entries')).toBeInTheDocument();
    expect(screen.getByText('Nothing recorded yet.')).toBeInTheDocument();
  });

  it('renders the entry form from the catalogue, grouped by category', async () => {
    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');

    // Both groups come from `category`, not from a list in the component.
    await waitFor(() =>
      expect(screen.getAllByText('Ventilator Settings').length).toBeGreaterThan(0)
    );
    expect(screen.getAllByText('Ventilator Observations / Measurements').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('FiO₂ (%)')).toBeInTheDocument();
    expect(screen.getByLabelText('Peak Pressure (cmH₂O)')).toBeInTheDocument();
  });

  it('renders a custom parameter with no code change', async () => {
    icuService.getEnabledVentilatorParams.mockResolvedValue([
      ...ENABLED,
      {
        ...param('minute_ventilation', 'Minute Ventilation', 'OBSERVATION', 'NUMBER', 'L/min'),
        isCustom: true,
      },
    ]);

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');

    expect(await screen.findByLabelText('Minute Ventilation (L/min)')).toBeInTheDocument();
  });

  it('offers the controlled mode list rather than free text', async () => {
    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');

    const select = await screen.findByLabelText('Mode');
    expect(select.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: 'Volume Control (VC)' })).toBeInTheDocument();
  });

  it('records the status and only the values that were filled in', async () => {
    icuService.recordVentilatorSetting.mockResolvedValue({});

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');
    await waitFor(() => expect(screen.getByLabelText('FiO₂ (%)')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('FiO₂ (%)'), { target: { value: '60' } });
    fireEvent.change(screen.getByLabelText('Mode'), { target: { value: 'VC' } });
    fireEvent.click(screen.getByText('Record'));

    await waitFor(() =>
      expect(icuService.recordVentilatorSetting).toHaveBeenCalledWith({
        ipdAdmissionId: 7,
        ventilationStatus: 'INVASIVE',
        values: { fio2: '60', mode: 'VC' },
      })
    );
  });

  it('sends no values when the patient is not ventilated', async () => {
    icuService.recordVentilatorSetting.mockResolvedValue({});

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');

    fireEvent.change(screen.getByLabelText('Ventilation'), { target: { value: 'OFF' } });
    fireEvent.click(screen.getByText('Record'));

    await waitFor(() =>
      expect(icuService.recordVentilatorSetting).toHaveBeenCalledWith({
        ipdAdmissionId: 7,
        ventilationStatus: 'OFF',
        values: {},
      })
    );
  });

  it('shows the current entry and its history', async () => {
    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry({ id: 2, publicId: 'vent-2', values: { fio2: 40 } }), entry()], PARAMS_MAP)
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getAllByText('Invasive').length).toBeGreaterThan(0);
    // Newest non-superseded entry is "current".
    expect(screen.getAllByText('40').length).toBeGreaterThan(0);
    expect(screen.getByText('60')).toBeInTheDocument();
  });

  it('keeps a disabled parameter’s historical value and marks it no longer charted', async () => {
    // The D-5 guarantee, made visible: FiO₂ has been switched off since this was charted.
    icuService.getEnabledVentilatorParams.mockResolvedValue(
      ENABLED.filter((p) => p.key !== 'fio2')
    );
    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry()], { fio2: { ...PARAMS_MAP.fio2, enabled: false } })
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getAllByText('FiO₂:').length).toBeGreaterThan(0);
    expect(screen.getAllByText('60').length).toBeGreaterThan(0);
    expect(screen.getAllByText('no longer charted').length).toBeGreaterThan(0);
    // Gone from the entry form, kept on the chart.
    await waitFor(() => expect(screen.queryByLabelText('FiO₂ (%)')).not.toBeInTheDocument());
  });

  it('captions historical values with the current display name after a rename', async () => {
    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry()], { fio2: { ...PARAMS_MAP.fio2, displayName: 'Inspired O₂' } })
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getAllByText('Inspired O₂:').length).toBeGreaterThan(0);
    expect(screen.getAllByText('60').length).toBeGreaterThan(0);
  });

  it('strikes through a superseded entry and badges both rows', async () => {
    icuService.getVentilatorChart.mockResolvedValue(
      chart(
        [
          entry({ id: 2, publicId: 'vent-2', values: { fio2: 45 }, supersedesSettingId: 1 }),
          entry(),
        ],
        PARAMS_MAP,
        [1]
      )
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getByText('Correction')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    const superseded = screen.getByText('60').closest('li');
    expect(superseded.className).toContain('line-through');
  });

  it('offers a correction only for an entry this user recorded', async () => {
    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry({ recordedByUserId: 99 })], PARAMS_MAP)
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
  });

  it('sends a correction to the correction endpoint, keyed by the entry', async () => {
    icuService.getVentilatorChart.mockResolvedValue(chart([entry()], PARAMS_MAP));
    icuService.correctVentilatorSetting.mockResolvedValue({});

    render(<VentilatorPanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Correct'));
    await waitFor(() =>
      expect(
        screen.getByLabelText('FiO₂ (%)', { selector: '#vent-correct-vent-1-fio2' })
      ).toBeInTheDocument()
    );
    fireEvent.change(screen.getByLabelText('FiO₂ (%)', { selector: '#vent-correct-vent-1-fio2' }), {
      target: { value: '45' },
    });
    fireEvent.click(screen.getByText('Save correction'));

    await waitFor(() =>
      expect(icuService.correctVentilatorSetting).toHaveBeenCalledWith('vent-1', {
        ventilationStatus: 'INVASIVE',
        values: { fio2: '45' },
      })
    );
  });

  it('hides every write control when the role is read-only', async () => {
    icuService.getVentilatorChart.mockResolvedValue(chart([entry()], PARAMS_MAP));

    render(<VentilatorPanel admissionId={7} readOnly />);
    await screen.findByText('History');

    expect(screen.queryByText('Record Ventilator Entry')).not.toBeInTheDocument();
    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
    expect(screen.getByText(/Read-only/)).toBeInTheDocument();
    // The chart itself is still fully readable.
    expect(screen.getAllByText('60').length).toBeGreaterThan(0);
  });

  it('says so when no parameters are switched on, rather than showing an empty form', async () => {
    icuService.getEnabledVentilatorParams.mockResolvedValue([]);

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('Record Ventilator Entry');

    expect(await screen.findByText(/No ventilator parameters are switched on/)).toBeInTheDocument();
  });

  it('re-reads the chart when a realtime refresh arrives', async () => {
    icuService.getVentilatorChart.mockResolvedValue(chart([entry()], PARAMS_MAP));

    const { rerender } = render(<VentilatorPanel admissionId={7} refreshKey={0} />);
    await screen.findByText('History');
    expect(icuService.getVentilatorChart).toHaveBeenCalledTimes(1);

    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry({ id: 2, publicId: 'vent-2', values: { fio2: 35 } }), entry()], PARAMS_MAP)
    );
    rerender(<VentilatorPanel admissionId={7} refreshKey={1} />);

    // A reading another nurse charted appears without anyone pressing reload.
    await waitFor(() => expect(screen.getAllByText('35').length).toBeGreaterThan(0));
  });

  it('drops an input for a parameter an administrator disables mid-shift', async () => {
    const { rerender } = render(<VentilatorPanel admissionId={7} refreshKey={0} />);
    await screen.findByText('Record Ventilator Entry');
    await waitFor(() => expect(screen.getByLabelText('FiO₂ (%)')).toBeInTheDocument());

    // An administrator switches FiO₂ off while this chart is open. Without the catalogue
    // re-reading, the nurse keeps an input whose value the server would silently drop.
    icuService.getEnabledVentilatorParams.mockResolvedValue(
      ENABLED.filter((p) => p.key !== 'fio2')
    );
    rerender(<VentilatorPanel admissionId={7} refreshKey={1} />);

    await waitFor(() => expect(screen.queryByLabelText('FiO₂ (%)')).not.toBeInTheDocument());
    expect(screen.getByLabelText('PEEP (cmH₂O)')).toBeInTheDocument();
  });

  it('shows no derived or computed figure anywhere', async () => {
    icuService.getVentilatorChart.mockResolvedValue(
      chart([entry({ values: { fio2: 60, peak_pressure: 28 } })], {
        fio2: PARAMS_MAP.fio2,
        peak_pressure: param('peak_pressure', 'Peak Pressure', 'OBSERVATION', 'NUMBER', 'cmH₂O'),
      })
    );

    render(<VentilatorPanel admissionId={7} />);
    await screen.findByText('History');

    // FiO₂ and a pressure are both present — the inputs a P/F ratio or compliance would use.
    expect(screen.queryByText(/P\/F/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/compliance/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/ratio/i)).not.toBeInTheDocument();
  });
});
