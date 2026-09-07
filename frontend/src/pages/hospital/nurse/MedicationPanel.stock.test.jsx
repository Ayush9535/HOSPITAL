import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('../../../services/nurseService', () => ({
  default: {
    getMedicationChart: vi.fn(),
    getMedicationHistory: vi.fn(),
    recordMedication: vi.fn(),
    getAdmissionForm: vi.fn().mockResolvedValue({ wardId: 1 }),
    getSeparateNurseLogin: vi.fn().mockResolvedValue(true),
    getWardStaffNurses: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: () => ({ userId: 1, role: 'NURSE' }) },
}));

vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

import nurseService from '../../../services/nurseService';
import MedicationPanel from './MedicationPanel';

/**
 * The reported bug, at the surface a nurse actually looks at.
 *
 * <p>A prescription the inventory cannot account for was being drawn as though the facility held
 * none of it. "Nobody linked this order to a stock row" and "the shelf is empty" are different
 * facts with different responses, and showing them identically told nurses a drug was unavailable
 * when nothing of the sort was known. What must never happen either way is the medication itself
 * dropping off the chart.
 */
describe('MedicationPanel — inventory reconciliation', () => {
  const row = (over) => ({
    prescriptionId: 1,
    medicineName: 'Med',
    dosage: '500mg',
    frequency: '1-0-1',
    route: 'ORAL',
    status: 'ACTIVE',
    courseActive: true,
    ...over,
  });

  beforeEach(() => {
    nurseService.getMedicationHistory.mockResolvedValue([]);
  });

  const rowFor = async (name) => {
    const cell = await screen.findByText(name);
    return cell.closest('tr');
  };

  it('shows unknown stock as NOT LINKED, never as an empty shelf', async () => {
    nurseService.getMedicationChart.mockResolvedValue([
      row({ prescriptionId: 1, medicineName: 'Handwritten mixture', inventoryStatus: 'UNLINKED' }),
    ]);

    render(<MedicationPanel admissionId={7} />);

    const tr = await rowFor('Handwritten mixture');
    expect(within(tr).getByText('NOT LINKED')).toBeInTheDocument();
    expect(within(tr).queryByText(/NO USABLE STOCK/)).not.toBeInTheDocument();
    expect(within(tr).queryByText(/·\s*0/)).not.toBeInTheDocument();
  });

  it('distinguishes an empty shelf from an unlinked order', async () => {
    nurseService.getMedicationChart.mockResolvedValue([
      row({ prescriptionId: 2, medicineName: 'Metformin', medicineId: 9, inventoryStatus: 'LINKED_NO_STOCK', availableQuantity: 0 }),
    ]);

    render(<MedicationPanel admissionId={7} />);

    const tr = await rowFor('Metformin');
    expect(within(tr).getByText('NO USABLE STOCK')).toBeInTheDocument();
    expect(within(tr).queryByText('NOT LINKED')).not.toBeInTheDocument();
  });

  it('shows the usable count and the lot that would go out first', async () => {
    nurseService.getMedicationChart.mockResolvedValue([
      row({
        prescriptionId: 3,
        medicineName: 'Ceftriaxone',
        medicineId: 4,
        inventoryStatus: 'LINKED_AVAILABLE',
        availableQuantity: 50,
        earliestExpiry: '2027-03-31',
      }),
    ]);

    render(<MedicationPanel admissionId={7} />);

    const tr = await rowFor('Ceftriaxone');
    const badge = within(tr).getByText(/IN STOCK/);
    expect(badge).toHaveTextContent('50');
    expect(badge).toHaveAttribute('title', expect.stringContaining('2027-03-31'));
  });

  it('keeps every medication on the chart whatever inventory says', async () => {
    nurseService.getMedicationChart.mockResolvedValue([
      row({ prescriptionId: 1, medicineName: 'Linked drug', medicineId: 4, inventoryStatus: 'LINKED_AVAILABLE', availableQuantity: 12 }),
      row({ prescriptionId: 2, medicineName: 'Unlinked drug', inventoryStatus: 'UNLINKED' }),
      row({ prescriptionId: 3, medicineName: 'Out of stock drug', medicineId: 5, inventoryStatus: 'LINKED_NO_STOCK', availableQuantity: 0 }),
    ]);

    render(<MedicationPanel admissionId={7} />);

    expect(await screen.findByText('Linked drug')).toBeInTheDocument();
    expect(screen.getByText('Unlinked drug')).toBeInTheDocument();
    expect(screen.getByText('Out of stock drug')).toBeInTheDocument();
  });

  it('falls back to NOT LINKED when the server sends no reconciliation at all', async () => {
    // An older server, or a chart built before this field existed: the safe reading of silence is
    // "unknown", not "none in stock".
    nurseService.getMedicationChart.mockResolvedValue([
      row({ prescriptionId: 4, medicineName: 'Legacy order' }),
    ]);

    render(<MedicationPanel admissionId={7} />);

    const tr = await rowFor('Legacy order');
    expect(within(tr).getByText('NOT LINKED')).toBeInTheDocument();
  });
});
