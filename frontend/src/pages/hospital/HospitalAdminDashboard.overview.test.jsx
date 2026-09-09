import { readdirSync, readFileSync } from 'node:fs';
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

  it('shows the analytics panel to hospital tenants only', () => {
    // The analytics endpoint is HOSPITAL-only, and apiService rewrites /hospital/** to
    // /clinic/** for a clinic session — a clinic would not merely be shown the wrong screen,
    // its request would never reach the endpoint at all.
    expect(source).toContain('{isHospitalTenant ? (');
    expect(source).toMatch(/\{isHospitalTenant \? \(\s*<AdminOverviewAnalytics \/>/);
    expect(source.match(/<AdminOverviewAnalytics \/>/g)).toHaveLength(1);
  });

  it('derives the tenant from the session user, not from what the response happens to contain', () => {
    expect(source).toContain(
      "user?.hospitalType !== 'CLINIC' && user?.hospitalType !== 'PHARMACY'"
    );
  });

  it('keeps the previous Overview for clinic tenants', () => {
    // Recovered from the base commit rather than rewritten: a clinic sees exactly the screen it
    // saw before this feature existed.
    expect(source).toContain('Total Registered Patients');
    expect(source).toContain('Patients This Month');
    expect(source).toContain('Patients Today');
    expect(source).toContain('Manage registered hospital patients');
    expect(source).toContain('{!isHospitalTenant && (');
  });

  it('gives the patient counters and list to the clinic branch only', () => {
    // Everything the hospital Overview dropped now lives behind the clinic side of the branch,
    // so a hospital admin cannot see the patient list return through the back door.
    const clinicBranch = source.slice(
      source.indexOf('{isHospitalTenant ? ('),
      source.indexOf('Today&apos;s Appointments')
    );
    expect(clinicBranch).toContain('Total Registered Patients');
    expect(clinicBranch).toContain('Manage registered hospital patients');
    expect(clinicBranch).toContain('{!isHospitalTenant && (');
  });

  it('leaves the pharmacy Overview untouched', () => {
    expect(source).toContain("{activeTab === 'overview' && !loading && isPharmacyTenant ? (");
  });

  it('reaches the analytics endpoint from exactly one component', () => {
    // The clinic and pharmacy Overviews cannot call an endpoint nothing on their path calls.
    // Only the service that declares it and the component the hospital branch renders may name
    // it; a second call site would be a route around the tenant check above.
    const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
    const callers = [];
    // withFileTypes returns each entry's kind from the directory read itself, so the type is
    // never established by a second stat() on a path that could be something else by the time it
    // is opened. Same files, one syscall, and no check-then-use pair for CodeQL to flag.
    const walk = (dir) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (/\.(js|jsx)$/.test(entry.name) && !/\.test\./.test(entry.name)) {
          if (readFileSync(full, 'utf8').includes('getDashboardOverview')) {
            callers.push(entry.name);
          }
        }
      }
    };
    walk(srcRoot);

    expect(callers.sort()).toEqual(['AdminOverviewAnalytics.jsx', 'hospitalService.js']);
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

  it('drops to one column for hospitals, where the patient list no longer sits beside it', () => {
    // Two columns with the left one gone would strand Today's Appointments in half a screen —
    // the same shape as the Doctor Overview regression. A clinic still has both, so it keeps two.
    expect(source).toContain("? 'grid grid-cols-1 gap-8 mt-8'");
    expect(source).toContain(": 'grid grid-cols-1 lg:grid-cols-2 gap-8 mt-8'");
  });
});
