import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import icuService from '../../../services/icuService';
import SeverityScorePanel from './SeverityScorePanel';

vi.mock('../../../services/icuService', () => ({
  default: {
    getScoreChart: vi.fn(),
    recordScore: vi.fn(),
    correctScore: vi.fn(),
    getEnabledScoreTypes: vi.fn(),
  },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: vi.fn(() => ({ id: 42 })) },
}));

const component = (key, label) => ({ key, label, min: 0, max: 4 });

const SOFA = {
  key: 'SOFA',
  label: 'SOFA',
  totalOnly: false,
  totalMin: 0,
  totalMax: 24,
  enabled: true,
  components: [
    component('respiratory', 'Respiratory'),
    component('coagulation', 'Coagulation'),
    component('liver', 'Liver'),
    component('cardiovascular', 'Cardiovascular'),
    component('cns', 'CNS'),
    component('renal', 'Renal'),
  ],
};

const APACHE = {
  key: 'APACHE_II',
  label: 'APACHE II',
  totalOnly: true,
  totalMin: 0,
  totalMax: 71,
  enabled: true,
  components: [],
};

const entry = (o = {}) => ({
  id: 1,
  publicId: 'score-1',
  scoreType: 'SOFA',
  components: { respiratory: 2, renal: 2 },
  totalScore: 4,
  scoredAt: '2026-08-26T08:00:00',
  recordedByUserId: 42,
  supersedesScoreId: null,
  note: null,
  ...o,
});

const chart = (entries, types = [SOFA, APACHE], supersededIds = []) => ({
  entries,
  types,
  supersededIds,
  latest: {},
});

describe('SeverityScorePanel — ICU severity scores (ICU-8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    icuService.getScoreChart.mockResolvedValue(chart([]));
    icuService.getEnabledScoreTypes.mockResolvedValue([SOFA, APACHE]);
  });

  it('says nothing has been recorded when the chart is empty', async () => {
    render(<SeverityScorePanel admissionId={7} />);
    expect(await screen.findByText('No scores')).toBeInTheDocument();
    expect(screen.getByText('Nothing recorded yet.')).toBeInTheDocument();
  });

  it('renders SOFA component inputs from the type, not from a list of its own', async () => {
    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');

    expect(await screen.findByLabelText('Respiratory (0–4)')).toBeInTheDocument();
    expect(screen.getByLabelText('Cardiovascular (0–4)')).toBeInTheDocument();
    expect(screen.getByLabelText('Renal (0–4)')).toBeInTheDocument();
  });

  it('never offers GCS as a severity score', async () => {
    // D-1: GCS lives on the Vitals tab; a second one here would split neuro observations.
    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');

    expect(screen.queryByLabelText(/GCS/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /GCS/i })).not.toBeInTheDocument();
    expect(screen.getByText(/GCS is on the Vitals tab/i)).toBeInTheDocument();
  });

  it('shows the running total as the sum of the boxes on screen', async () => {
    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');
    await waitFor(() => expect(screen.getByLabelText('Respiratory (0–4)')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Respiratory (0–4)'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Cardiovascular (0–4)'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Renal (0–4)'), { target: { value: '2' } });

    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('records SOFA with the components entered', async () => {
    icuService.recordScore.mockResolvedValue({});

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');
    await waitFor(() => expect(screen.getByLabelText('Renal (0–4)')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Respiratory (0–4)'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Renal (0–4)'), { target: { value: '1' } });
    fireEvent.click(screen.getByText('Record'));

    await waitFor(() =>
      expect(icuService.recordScore).toHaveBeenCalledWith({
        ipdAdmissionId: 7,
        scoreType: 'SOFA',
        components: { respiratory: '2', renal: '1' },
        // The server sums; the panel never sends a total for a component score.
        totalScore: null,
      })
    );
  });

  it('records APACHE II as a total with no component inputs', async () => {
    icuService.recordScore.mockResolvedValue({});

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');
    await waitFor(() => expect(screen.getByLabelText('Score')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Score'), { target: { value: 'APACHE_II' } });
    const total = await screen.findByLabelText('Total (0–71)');
    expect(screen.queryByLabelText('Respiratory (0–4)')).not.toBeInTheDocument();

    fireEvent.change(total, { target: { value: '22' } });
    fireEvent.click(screen.getByText('Record'));

    await waitFor(() =>
      expect(icuService.recordScore).toHaveBeenCalledWith({
        ipdAdmissionId: 7,
        scoreType: 'APACHE_II',
        components: {},
        totalScore: 22,
      })
    );
  });

  it('shows the latest score per type and the history', async () => {
    icuService.getScoreChart.mockResolvedValue(
      chart([
        entry({ id: 2, publicId: 'score-2', totalScore: 9 }),
        entry({
          id: 3,
          publicId: 'score-3',
          scoreType: 'APACHE_II',
          components: {},
          totalScore: 22,
        }),
        entry(),
      ])
    );

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getByText('Latest SOFA')).toBeInTheDocument();
    expect(screen.getByText('Latest APACHE II')).toBeInTheDocument();
    expect(screen.getAllByText('9').length).toBeGreaterThan(0);
    expect(screen.getAllByText('22').length).toBeGreaterThan(0);
    expect(screen.getAllByText('4').length).toBeGreaterThan(0);
  });

  it('labels a recorded component from its type', async () => {
    icuService.getScoreChart.mockResolvedValue(chart([entry()]));

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getAllByText('Respiratory:').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Renal:').length).toBeGreaterThan(0);
  });

  it('keeps a disabled score type’s history and marks it no longer recorded', async () => {
    icuService.getEnabledScoreTypes.mockResolvedValue([SOFA]);
    icuService.getScoreChart.mockResolvedValue(
      chart(
        [entry({ scoreType: 'APACHE_II', components: {}, totalScore: 22 })],
        [SOFA, { ...APACHE, enabled: false }]
      )
    );

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getAllByText('APACHE II').length).toBeGreaterThan(0);
    expect(screen.getAllByText('22').length).toBeGreaterThan(0);
    expect(screen.getByText('no longer recorded')).toBeInTheDocument();
    // Gone from the entry form, kept on the chart.
    expect(screen.queryByRole('option', { name: 'APACHE II' })).not.toBeInTheDocument();
  });

  it('strikes through a superseded scoring and badges both rows', async () => {
    icuService.getScoreChart.mockResolvedValue(
      chart(
        [entry({ id: 2, publicId: 'score-2', totalScore: 7, supersedesScoreId: 1 }), entry()],
        [SOFA, APACHE],
        [1]
      )
    );

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.getByText('Correction')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    const superseded = screen.getAllByText('4')[0].closest('li');
    expect(superseded.className).toContain('line-through');
  });

  it('offers a correction only for a scoring this user recorded', async () => {
    icuService.getScoreChart.mockResolvedValue(chart([entry({ recordedByUserId: 99 })]));

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
  });

  it('sends a correction to the correction endpoint, keyed by the scoring', async () => {
    icuService.getScoreChart.mockResolvedValue(chart([entry()]));
    icuService.correctScore.mockResolvedValue({});

    render(<SeverityScorePanel admissionId={7} />);
    fireEvent.click(await screen.findByText('Correct'));
    const renal = await screen.findByLabelText('Renal (0–4)', {
      selector: '#score-correct-score-1-renal',
    });
    fireEvent.change(renal, { target: { value: '1' } });
    fireEvent.click(screen.getByText('Save correction'));

    await waitFor(() =>
      expect(icuService.correctScore).toHaveBeenCalledWith('score-1', {
        components: { respiratory: '2', renal: '1' },
        totalScore: null,
      })
    );
  });

  it('hides every write control when the role is read-only', async () => {
    icuService.getScoreChart.mockResolvedValue(chart([entry()]));

    render(<SeverityScorePanel admissionId={7} readOnly />);
    await screen.findByText('History');

    expect(screen.queryByText('Record a Score')).not.toBeInTheDocument();
    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
    expect(screen.getByText(/Read-only/)).toBeInTheDocument();
    // The chart itself is still fully readable.
    expect(screen.getAllByText('4').length).toBeGreaterThan(0);
  });

  it('drops a score an administrator disables mid-shift', async () => {
    const { rerender } = render(<SeverityScorePanel admissionId={7} refreshKey={0} />);
    await screen.findByText('Record a Score');
    await waitFor(() =>
      expect(screen.getByRole('option', { name: 'APACHE II' })).toBeInTheDocument()
    );

    icuService.getEnabledScoreTypes.mockResolvedValue([SOFA]);
    rerender(<SeverityScorePanel admissionId={7} refreshKey={1} />);

    await waitFor(() =>
      expect(screen.queryByRole('option', { name: 'APACHE II' })).not.toBeInTheDocument()
    );
    expect(screen.getByRole('option', { name: 'SOFA' })).toBeInTheDocument();
  });

  it('says so when no score is switched on, rather than showing an empty form', async () => {
    icuService.getEnabledScoreTypes.mockResolvedValue([]);

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('Record a Score');

    expect(await screen.findByText(/No severity scores are switched on/)).toBeInTheDocument();
  });

  it('shows no risk band, mortality figure or trend label', async () => {
    icuService.getScoreChart.mockResolvedValue(
      chart([entry({ id: 2, publicId: 'score-2', totalScore: 9 }), entry()])
    );

    render(<SeverityScorePanel admissionId={7} />);
    await screen.findByText('History');

    expect(screen.queryByText(/mortality/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/risk/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/improving|worsening|deteriorat/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/severe|critical/i)).not.toBeInTheDocument();
  });
});
