import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Structural guard for the Overview redesign.
 *
 * <p>HospitalAdminDashboard is a ten-thousand line component wired to a dozen services, several
 * modals and a websocket; rendering it here would test the mocks more than the page. What these
 * assertions are for is narrower and worth stating plainly: the patient list and patient counters
 * that used to occupy the Overview are gone from the file, the analytics panel took their place,
 * and nothing about patient *management* was removed along with them. Behaviour of the analytics
 * itself is covered by AdminOverviewAnalytics.test.jsx, which renders the real component.
 */

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'HospitalAdminDashboard.jsx'),
  'utf8'
);

describe('Hospital Admin Overview composition', () => {
  it('renders the analytics panel on the Overview tab', () => {
    expect(source).toContain(
      "import AdminOverviewAnalytics from '../../components/AdminOverviewAnalytics'"
    );
    expect(source).toContain('<AdminOverviewAnalytics />');
  });

  it('no longer carries the Overview patient counters', () => {
    // These three cards were the top of the old Overview. Two of them duplicated numbers the
    // analytics panel now reports properly, and the third was mislabelled: it counted active
    // patients while calling them registered.
    expect(source).not.toContain('Total Registered Patients');
    expect(source).not.toContain('Patients This Month');
    expect(source).not.toContain('Patients Today');
  });

  it('no longer carries the Overview patient list panel', () => {
    expect(source).not.toContain('Manage registered hospital patients');
    expect(source).not.toContain('{/* Left Div: Patients */}');
  });

  it('keeps patient management intact on its own tab', () => {
    // Removing a section from one screen must not remove the feature from the product.
    expect(source).toContain("{ id: 'patients', label: 'Patients'");
    expect(source).toContain("activeTab === 'patients'");
    expect(source).toContain('<PatientsTable');
    expect(source).toContain('const loadPatients');
    // Adding a patient is reached through the shared PageHeader add button, which is keyed off
    // the active tab. The Overview panel's own hard-coded handleAdd('patients') went with it.
    expect(source).toContain("activeTab === 'patients' ? 'Patient'");
  });

  it('keeps Today’s Appointments on the Overview, still module-gated', () => {
    expect(source).toContain('Today&apos;s Appointments');
    expect(source).toContain('{hasAppointments && (');
  });

  it('does not leave the remaining Overview panel in a half-empty two-column grid', () => {
    // The old grid was lg:grid-cols-2 with the patient list on the left. With the left column
    // gone, keeping two columns would strand Today's Appointments in half a screen — the same
    // shape as the Doctor Overview regression.
    expect(source).not.toContain('grid grid-cols-1 lg:grid-cols-2 gap-8 mt-8');
    expect(source).toContain('grid grid-cols-1 gap-8 mt-8');
  });
});
