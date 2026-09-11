import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import icuService from '../../services/icuService';
import AlertThresholdsCard from './AlertThresholdsCard';

vi.mock('../../services/icuService', () => ({
  default: {
    getAlertThresholds: vi.fn(),
    saveAlertThreshold: vi.fn(),
  },
}));
const toastError = vi.fn();
// One stable object, as the real provider returns. A fresh object per render would change the
// identity of the card's `load` callback and re-fire its effect on every render.
const toast = { success: vi.fn(), error: toastError };
vi.mock('../../context/ToastContext', () => ({
  useToast: () => toast,
}));

const metric = (key, label, o = {}) => ({
  source: 'VITALS',
  key,
  label,
  unit: 'mmHg',
  minValue: null,
  maxValue: null,
  enabled: false,
  configured: false,
  publicId: null,
  ...o,
});

const UNSET = [metric('map_mmhg', 'MAP'), metric('pulse', 'Pulse')];

describe('AlertThresholdsCard — ICU alert thresholds (ICU-9)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    icuService.getAlertThresholds.mockResolvedValue(UNSET);
    icuService.saveAlertThreshold.mockResolvedValue({});
  });

  it('renders every metric from the API with no threshold suggested', async () => {
    render(<AlertThresholdsCard />);
    await screen.findByText('MAP');

    // Empty inputs and "Off": nothing is proposed, because a proposed number would be the
    // system stating a clinical norm.
    expect(screen.getByLabelText('Alert below', { selector: '#alert-min-map_mmhg' })).toHaveValue(
      null
    );
    expect(screen.getByLabelText('Alert above', { selector: '#alert-max-map_mmhg' })).toHaveValue(
      null
    );
    expect(screen.getAllByText('Off')).toHaveLength(2);
  });

  it('holds no metric list of its own', async () => {
    icuService.getAlertThresholds.mockResolvedValue([metric('cvp_cmh2o', 'CVP')]);
    render(<AlertThresholdsCard />);

    expect(await screen.findByText('CVP')).toBeInTheDocument();
    expect(screen.queryByText('MAP')).not.toBeInTheDocument();
  });

  it('saves the bounds the admin typed', async () => {
    render(<AlertThresholdsCard />);
    await screen.findByText('MAP');

    fireEvent.change(screen.getByLabelText('Alert below', { selector: '#alert-min-map_mmhg' }), {
      target: { value: '65' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' })[0]);

    await waitFor(() =>
      expect(icuService.saveAlertThreshold).toHaveBeenCalledWith('map_mmhg', {
        minValue: 65,
        maxValue: null,
        enabled: true,
      })
    );
  });

  it('turns a configured threshold off without clearing its numbers', async () => {
    icuService.getAlertThresholds.mockResolvedValue([
      metric('map_mmhg', 'MAP', { minValue: 65, enabled: true, configured: true }),
    ]);

    render(<AlertThresholdsCard />);
    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));

    await waitFor(() =>
      expect(icuService.saveAlertThreshold).toHaveBeenCalledWith('map_mmhg', {
        minValue: 65,
        maxValue: null,
        enabled: false,
      })
    );
  });

  it('surfaces the server refusal for a threshold with no bound', async () => {
    icuService.saveAlertThreshold.mockRejectedValue({
      response: { data: { error: 'Set a minimum, a maximum, or both' } },
    });

    render(<AlertThresholdsCard />);
    await screen.findByText('MAP');
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' })[0]);

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Set a minimum, a maximum, or both')
    );
  });

  it('re-reads on a realtime refresh', async () => {
    const { rerender } = render(<AlertThresholdsCard refreshKey={0} />);
    await screen.findByText('MAP');

    icuService.getAlertThresholds.mockResolvedValue([
      metric('map_mmhg', 'MAP', { minValue: 70, enabled: true, configured: true }),
    ]);
    rerender(<AlertThresholdsCard refreshKey={1} />);

    await waitFor(() =>
      expect(screen.getByLabelText('Alert below', { selector: '#alert-min-map_mmhg' })).toHaveValue(
        70
      )
    );
  });

  it('shows no severity, colour or priority anywhere', async () => {
    icuService.getAlertThresholds.mockResolvedValue([
      metric('map_mmhg', 'MAP', { minValue: 65, enabled: true, configured: true }),
    ]);

    render(<AlertThresholdsCard />);
    await screen.findByText('MAP');

    expect(
      screen.queryByText(/critical|severe|urgent|priority|high risk/i)
    ).not.toBeInTheDocument();
  });
});
