import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import IoChartPanel from './IoChartPanel';
import { bucketIoEntries } from './VitalsPanel';

vi.mock('../../../services/icuService', () => ({
  default: {
    getIoEntries: vi.fn(),
    getIoBalance: vi.fn(),
    recordIoEntry: vi.fn(),
    correctIoEntry: vi.fn(),
  },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: vi.fn(() => ({ id: 42 })) },
}));

import icuService from '../../../services/icuService';

const entry = (o = {}) => ({
  id: 1,
  publicId: 'io-1',
  direction: 'OUTPUT',
  route: 'URINE',
  volumeMl: 400,
  occurredAt: '2026-08-26T10:00:00',
  supersedesIoEntryId: null,
  recordedByUserId: 42,
  ...o,
});

describe('IoChartPanel — ICU I/O (ICU-5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    icuService.getIoEntries.mockResolvedValue([]);
    icuService.getIoBalance.mockResolvedValue({
      totalIntakeMl: 0,
      totalOutputMl: 0,
      netBalanceMl: 0,
      entryCount: 0,
    });
  });

  it('shows the totals the API computed, without recomputing them', async () => {
    icuService.getIoBalance.mockResolvedValue({
      totalIntakeMl: 1250,
      totalOutputMl: 750,
      netBalanceMl: 500,
      entryCount: 5,
    });

    render(<IoChartPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('1250')).toBeInTheDocument());
    expect(screen.getByText('750')).toBeInTheDocument();
    expect(screen.getByText('500')).toBeInTheDocument();
  });

  it('renders intake and output entries with their route and volume', async () => {
    icuService.getIoEntries.mockResolvedValue([
      entry({ id: 1, publicId: 'io-1', direction: 'INTAKE', route: 'IV_FLUIDS', volumeMl: 500 }),
      entry({ id: 2, publicId: 'io-2', direction: 'OUTPUT', route: 'URINE', volumeMl: 400 }),
    ]);

    render(<IoChartPanel admissionId={7} />);

    expect(await screen.findByText('I.V. Fluids: 500 mL')).toBeInTheDocument();
    expect(screen.getByText('Urine Output: 400 mL')).toBeInTheDocument();
    // "Intake"/"Output" also label the direction <select> options, so scope to the badges.
    expect(screen.getAllByText('Intake').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Output').length).toBeGreaterThan(0);
  });

  it('keeps a superseded entry visible and marks the correction', async () => {
    icuService.getIoEntries.mockResolvedValue([
      entry({ id: 2, publicId: 'io-2', volumeMl: 350, supersedesIoEntryId: 1 }),
      entry({ id: 1, publicId: 'io-1', volumeMl: 400 }),
    ]);

    render(<IoChartPanel admissionId={7} />);

    expect(await screen.findByText('Correction')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    // Both remain readable — the original value is not erased.
    expect(screen.getByText('Urine Output: 400 mL')).toBeInTheDocument();
    expect(screen.getByText('Urine Output: 350 mL')).toBeInTheDocument();
  });

  it('corrects an entry: original preserved, only the new value counts', async () => {
    // Record 400, correct to 350 -> the chart shows BOTH, and the balance uses 350, not 750.
    icuService.getIoEntries.mockResolvedValueOnce([
      entry({ id: 1, publicId: 'io-1', volumeMl: 400 }),
    ]);
    icuService.getIoBalance.mockResolvedValueOnce({
      totalIntakeMl: 0,
      totalOutputMl: 400,
      netBalanceMl: -400,
      entryCount: 1,
    });
    icuService.correctIoEntry.mockResolvedValue({});
    // After the correction the server returns both rows and the corrected balance.
    icuService.getIoEntries.mockResolvedValue([
      entry({ id: 2, publicId: 'io-2', volumeMl: 350, supersedesIoEntryId: 1 }),
      entry({ id: 1, publicId: 'io-1', volumeMl: 400 }),
    ]);
    icuService.getIoBalance.mockResolvedValue({
      totalIntakeMl: 0,
      totalOutputMl: 350,
      netBalanceMl: -350,
      entryCount: 1,
    });

    render(<IoChartPanel admissionId={7} />);

    fireEvent.click(await screen.findByText('Correct'));
    fireEvent.change(screen.getByLabelText('Corrected volume (mL)'), { target: { value: '350' } });
    fireEvent.click(screen.getByText('Save correction'));

    await waitFor(() => expect(icuService.correctIoEntry).toHaveBeenCalled());
    expect(icuService.correctIoEntry.mock.calls[0][0]).toBe('io-1');
    expect(icuService.correctIoEntry.mock.calls[0][1]).toMatchObject({
      volumeMl: 350,
      route: 'URINE',
    });

    // Original still on the chart, corrected value alongside it, balance uses 350 only.
    await waitFor(() => expect(screen.getByText('Urine Output: 350 mL')).toBeInTheDocument());
    expect(screen.getByText('Urine Output: 400 mL')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    expect(screen.getByText('350')).toBeInTheDocument();
    expect(screen.queryByText('750')).not.toBeInTheDocument();
  });

  it('offers no correction for an entry someone else recorded', async () => {
    icuService.getIoEntries.mockResolvedValue([entry({ recordedByUserId: 99 })]);

    render(<IoChartPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('Urine Output: 400 mL')).toBeInTheDocument());
    expect(screen.queryByText('Correct')).not.toBeInTheDocument();
  });

  it('offers no correction for an already superseded entry', async () => {
    icuService.getIoEntries.mockResolvedValue([
      entry({ id: 2, publicId: 'io-2', volumeMl: 350, supersedesIoEntryId: 1 }),
      entry({ id: 1, publicId: 'io-1', volumeMl: 400 }),
    ]);

    render(<IoChartPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('Superseded')).toBeInTheDocument());
    // Only the current row may be corrected, never the one already replaced.
    expect(screen.getAllByText('Correct')).toHaveLength(1);
  });

  it('shows an empty state and hides the form when read-only', async () => {
    render(<IoChartPanel admissionId={7} readOnly />);

    expect(await screen.findByText('No entries')).toBeInTheDocument();
    expect(screen.queryByText('Record Intake / Output')).not.toBeInTheDocument();
    expect(screen.getByText(/Read-only/)).toBeInTheDocument();
  });

  it('derives no clinical judgement from the balance', async () => {
    icuService.getIoBalance.mockResolvedValue({
      totalIntakeMl: 100,
      totalOutputMl: 900,
      netBalanceMl: -800,
      entryCount: 2,
    });

    render(<IoChartPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('-800')).toBeInTheDocument());
    expect(screen.queryByText(/critical|severe|high risk|deficit|dehydrat|overload/i)).toBeNull();
  });
});

describe('bucketIoEntries — NABH chart columns (ICU-5 D-2)', () => {
  const reading = (t) => ({ recordedAt: t });

  it('attributes each entry to the reading it follows', () => {
    const ordered = [reading('2026-08-26T08:00:00'), reading('2026-08-26T12:00:00')];
    const buckets = bucketIoEntries(ordered, [
      { id: 1, route: 'IV_FLUIDS', volumeMl: 500, occurredAt: '2026-08-26T09:00:00' },
      { id: 2, route: 'URINE', volumeMl: 300, occurredAt: '2026-08-26T13:00:00' },
    ]);

    expect(buckets[0].IV_FLUIDS).toBe(500);
    expect(buckets[1].URINE).toBe(300);
    expect(buckets[0].URINE).toBe(0);
  });

  it('excludes an entry superseded by a correction', () => {
    const ordered = [reading('2026-08-26T08:00:00')];
    const buckets = bucketIoEntries(ordered, [
      { id: 1, route: 'URINE', volumeMl: 400, occurredAt: '2026-08-26T09:00:00' },
      {
        id: 2,
        route: 'URINE',
        volumeMl: 350,
        occurredAt: '2026-08-26T09:00:00',
        supersedesIoEntryId: 1,
      },
    ]);

    expect(buckets[0].URINE).toBe(350);
  });

  it('leaves every column at zero when nothing was recorded', () => {
    const buckets = bucketIoEntries([reading('2026-08-26T08:00:00')], []);
    expect(buckets[0]).toEqual({
      IV_FLUIDS: 0,
      ORAL: 0,
      RYLES_ASPIRATION: 0,
      URINE: 0,
      VOMIT: 0,
    });
  });

  it('never reads a vitals urine figure (D-2)', () => {
    // The vitals row carries urineOutputMl; the chart must ignore it entirely.
    const ordered = [{ recordedAt: '2026-08-26T08:00:00', urineOutputMl: 750 }];
    const buckets = bucketIoEntries(ordered, []);
    expect(buckets[0].URINE).toBe(0);
  });
});
