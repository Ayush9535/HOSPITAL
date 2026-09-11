import React, { useState, useEffect } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseService from '../../../services/nurseService';

/**
 * NurseOverviewView - dashboard landing for a nurse (Phase 1).
 * Stat cards + recent assigned patients. Task/vitals/notification widgets
 * arrive with later milestones (M4, M7, M8).
 */
const StatCard = ({ label, value, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl p-5">
    <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</p>
    <p className={`text-3xl font-bold mt-2 ${accent || 'text-gray-900'}`}>{value}</p>
  </div>
);

const NurseOverviewView = ({ onOpenPatient, refreshKey }) => {
  const { error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    nurseService
      .getDashboard()
      .then((d) => {
        if (active) setData(d);
      })
      .catch(() => {
        if (active) toastError('Failed to load dashboard');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refreshKey, toastError]);

  if (loading) return <LoadingSpinner />;

  const recent = data?.recentPatients || [];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="Assigned Patients" value={data?.assignedPatientCount ?? 0} />
        {/* Placeholders for later milestones — kept visible for layout stability */}
        <StatCard label="Pending Tasks" value="—" accent="text-gray-400" />
        <StatCard label="Vitals Today" value="—" accent="text-gray-400" />
        <StatCard label="Unread Alerts" value="—" accent="text-gray-400" />
      </div>

      <div className="bg-white border border-gray-200 rounded-xl">
        <div className="px-5 py-4 border-b border-gray-100">
          <h3 className="font-bold text-gray-800">Recent Patients</h3>
        </div>
        {recent.length === 0 ? (
          <EmptyState
            icon={null}
            title="No patients assigned yet"
            message="Your ward in-charge will assign patients to you."
          />
        ) : (
          <ul className="divide-y divide-gray-100">
            {recent.map((p) => (
              <li key={p.ipdAdmissionId}>
                <button
                  type="button"
                  className="w-full text-left px-5 py-3 flex items-center justify-between hover:bg-gray-50 cursor-pointer"
                  onClick={() => onOpenPatient(p.ipdAdmissionId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onOpenPatient(p.ipdAdmissionId);
                    }
                  }}
                >
                  <div>
                    <p className="font-medium text-gray-900">{p.patientName || '—'}</p>
                    <p className="text-xs text-gray-500">
                      {p.ipdNumber}
                      {p.bedCode ? ` · Bed ${p.bedCode}` : ''}
                      {p.primaryDiagnosis ? ` · ${p.primaryDiagnosis}` : ''}
                    </p>
                  </div>
                  <span className="text-xs font-semibold text-gray-400">View →</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default NurseOverviewView;
