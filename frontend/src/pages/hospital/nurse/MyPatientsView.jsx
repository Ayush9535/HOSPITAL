import React, { useState, useEffect, useMemo } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import useDebounce from '../../../hooks/useDebounce';
import nurseService from '../../../services/nurseService';
import AdmissionFormModal from './AdmissionFormModal';

/**
 * MyPatientsView - the nurse's working list of assigned admitted patients.
 */
const MyPatientsView = ({ onOpenPatient, refreshKey }) => {
  const { error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [patients, setPatients] = useState([]);
  const [search, setSearch] = useState('');
  const [admitFormFor, setAdmitFormFor] = useState(null); // admissionId or null
  const [localRefresh, setLocalRefresh] = useState(0);
  const debouncedSearch = useDebounce(search, 250);

  useEffect(() => {
    let active = true;
    setLoading(true);
    nurseService
      .getMyPatients()
      .then((d) => {
        if (active) setPatients(Array.isArray(d) ? d : []);
      })
      .catch(() => {
        if (active) toastError('Failed to load your patients');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refreshKey, localRefresh, toastError]);

  const filtered = useMemo(() => {
    const q = (debouncedSearch || '').trim().toLowerCase();
    if (!q) return patients;
    return patients.filter(
      (p) =>
        (p.patientName || '').toLowerCase().includes(q) ||
        (p.bedCode || '').toLowerCase().includes(q) ||
        (p.ipdNumber || '').toLowerCase().includes(q)
    );
  }, [patients, debouncedSearch]);

  if (loading) return <LoadingSpinner />;

  if (patients.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No patients assigned yet"
        message="Your ward in-charge will assign patients to you."
      />
    );
  }

  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';

  return (
    <div className="space-y-4">
      <input
        type="text"
        placeholder="Search by name, bed, or IPD no."
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full sm:w-80 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
      />
      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
              <th className="px-4 py-3">PATIENT</th>
              <th className="px-4 py-3">AGE/SEX</th>
              <th className="px-4 py-3">BED</th>
              <th className="px-4 py-3">DIAGNOSIS</th>
              <th className="px-4 py-3">DOCTOR</th>
              <th className="px-4 py-3">ADMITTED</th>
              <th className="px-4 py-3">STATUS</th>
              <th className="px-4 py-3 text-right">ACTIONS</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((p) => (
              <tr key={p.ipdAdmissionId} className="border-b border-gray-100 hover:bg-gray-50">
                <td className="px-4 py-3 font-medium text-gray-900">{p.patientName || '—'}</td>
                <td className="px-4 py-3 text-gray-600">
                  {p.age != null ? p.age : '—'}
                  {p.gender ? ` / ${p.gender}` : ''}
                </td>
                <td className="px-4 py-3">{p.bedCode || '—'}</td>
                <td className="px-4 py-3 text-gray-600">{p.primaryDiagnosis || '—'}</td>
                <td className="px-4 py-3 text-gray-600">{p.doctorName || '—'}</td>
                <td className="px-4 py-3 text-gray-500">{fmt(p.admissionDateTime)}</td>
                <td className="px-4 py-3">
                  {p.admissionConfirmed ? (
                    <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                      ADMITTED
                    </span>
                  ) : (
                    <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-amber-100 text-amber-700">
                      ADMISSION PENDING
                    </span>
                  )}
                  {p.surgeryStatus && (
                    <div className="mt-1">
                      <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-indigo-100 text-indigo-700">
                        {p.surgeryStatus === 'IN_PROGRESS' ? 'IN SURGERY' : 'SURGERY REQUESTED'}
                      </span>
                    </div>
                  )}
                </td>
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {!p.admissionConfirmed && (
                    <button
                      onClick={() => setAdmitFormFor(p.ipdAdmissionId)}
                      className="mr-2 px-3 py-1 text-xs font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700"
                    >
                      Complete Admission
                    </button>
                  )}
                  <button
                    onClick={() => onOpenPatient(p.ipdAdmissionId)}
                    className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800"
                  >
                    Open
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {admitFormFor && (
        <AdmissionFormModal
          admissionId={admitFormFor}
          onClose={() => setAdmitFormFor(null)}
          onConfirmed={() => setLocalRefresh((k) => k + 1)}
        />
      )}
    </div>
  );
};

export default MyPatientsView;
