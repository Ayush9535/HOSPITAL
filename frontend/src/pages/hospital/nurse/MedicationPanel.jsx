import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import { describeFoodTiming } from '../../../utils/foodTiming';

/**
 * MedicationPanel - the nurse's medication chart + administration recording.
 * Shows every doctor order (ACTIVE / STOPPED / COMPLETED — never deleted) with
 * its course progress and a "pending today" reminder while the course runs.
 * Recording is only allowed against ACTIVE orders.
 */
/** readOnly: hide the "Record Administration" form (e.g. a doctor viewing the MAR). */
const MedicationPanel = ({ admissionId, readOnly = false }) => {
  const { success, error: toastError } = useToast();
  const currentUser = authService.getCurrentUser();
  const currentUserId = currentUser?.userId;
  const isNurse = currentUser?.role === 'NURSE' || currentUser?.role === 'NURSE_INCHARGE';

  const [loading, setLoading] = useState(true);
  const [chart, setChart] = useState([]);
  const [history, setHistory] = useState([]);
  const [submitting, setSubmitting] = useState(false);

  const [selectedPrescriptionId, setSelectedPrescriptionId] = useState('');
  const [status, setStatus] = useState('GIVEN');
  const [administeredTime, setAdministeredTime] = useState('');
  const [remarks, setRemarks] = useState('');

  // Separate Nurse Login OFF ("Shared Login") -> a required "Performed By
  // Nurse" dropdown is shown and its selection is sent with the payload.
  const [separateLogin, setSeparateLogin] = useState(true);
  const [wardId, setWardId] = useState(null);
  const [nurses, setNurses] = useState([]);
  const [performedByNurseId, setPerformedByNurseId] = useState('');

  const activeMeds = chart.filter((c) => (c.status || '').toUpperCase() === 'ACTIVE');

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [chartData, histData] = await Promise.all([
        nurseService.getMedicationChart(admissionId),
        nurseService.getMedicationHistory(admissionId),
      ]);
      const c = Array.isArray(chartData) ? chartData : [];
      setChart(c);
      setHistory(Array.isArray(histData) ? histData : []);
      const firstActive = c.find((x) => (x.status || '').toUpperCase() === 'ACTIVE');
      setSelectedPrescriptionId(firstActive ? String(firstActive.prescriptionId) : '');
    } catch (err) {
      toastError('Failed to load medication details');
    } finally {
      setLoading(false);
    }
  }, [admissionId, toastError]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Both only feed the (hidden) record form, and hit nurse-only endpoints.
  useEffect(() => {
    if (readOnly) return;
    let active = true;
    nurseService
      .getAdmissionForm(admissionId)
      .then((d) => {
        if (active) setWardId(d?.wardId ?? null);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [admissionId, readOnly]);

  useEffect(() => {
    if (readOnly) return;
    let active = true;
    nurseService
      .getSeparateNurseLogin()
      .then((v) => {
        if (active) setSeparateLogin(v);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [readOnly]);

  useEffect(() => {
    if (isNurse && separateLogin === false && wardId) {
      nurseService
        .getWardStaffNurses(wardId)
        .then((list) => setNurses(Array.isArray(list) ? list : []))
        .catch(() => setNurses([]));
    }
  }, [isNurse, separateLogin, wardId]);

  useEffect(() => {
    if (status === 'GIVEN' || status === 'DELAYED') {
      const now = new Date();
      setAdministeredTime(
        new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
      );
    } else {
      setAdministeredTime('');
    }
  }, [status]);

  const handleSubmit = async () => {
    if (!selectedPrescriptionId) {
      toastError('Please select a medicine');
      return;
    }
    if (separateLogin === false && !performedByNurseId) {
      toastError('Select the nurse who performed this');
      return;
    }
    const payload = {
      ipdAdmissionId: admissionId,
      prescriptionId: Number(selectedPrescriptionId),
      status,
      remarks: remarks.trim() || null,
    };
    if (administeredTime) payload.administeredTime = new Date(administeredTime).toISOString();
    if (separateLogin === false) payload.performedByNurseId = Number(performedByNurseId);

    setSubmitting(true);
    try {
      await nurseService.recordMedication(payload);
      success('Medication administration recorded');
      setRemarks('');
      setPerformedByNurseId('');
      loadData();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to record medication';
      toastError(typeof msg === 'string' ? msg : 'Failed to record medication');
    } finally {
      setSubmitting(false);
    }
  };

  const medName = (presId) => {
    const found = chart.find((p) => p.prescriptionId === presId);
    return found ? found.medicineName : `Prescription #${presId}`;
  };

  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';

  const marStatusColor = (s) => {
    switch (s) {
      case 'GIVEN':
        return 'bg-green-100 text-green-800';
      case 'SKIPPED':
        return 'bg-yellow-100 text-yellow-800';
      case 'DELAYED':
        return 'bg-orange-100 text-orange-800';
      case 'REFUSED':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  const orderBadge = (c) => {
    const s = (c.status || '').toUpperCase();
    if (s === 'STOPPED')
      return (
        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-red-100 text-red-700">
          STOPPED BY DOCTOR
        </span>
      );
    if (s === 'COMPLETED')
      return (
        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-gray-200 text-gray-600">
          COMPLETED
        </span>
      );
    return (
      <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
        ACTIVE
      </span>
    );
  };

  /**
   * What inventory knows about this order — advisory, never a reason to hide the medication.
   *
   * <p>"Not linked" and "no stock" are deliberately different answers. An order written as free
   * text has no inventory row attached to it, so the facility's stock level for it is unknown,
   * not zero. Showing those two states the same way told nurses a drug was unavailable when in
   * fact nobody had reconciled the order — a stock-keeping gap dressed up as a clinical one.
   */
  const stockBadge = (c) => {
    const status = c.inventoryStatus;
    if (status === 'LINKED_AVAILABLE') {
      return (
        <span
          className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700"
          title={c.earliestExpiry ? `Earliest expiry ${c.earliestExpiry}` : undefined}
        >
          IN STOCK{typeof c.availableQuantity === 'number' ? ` · ${c.availableQuantity}` : ''}
        </span>
      );
    }
    if (status === 'LINKED_NO_STOCK') {
      return (
        <span
          className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-red-100 text-red-700"
          title="Every batch is empty, expired or withdrawn"
        >
          NO USABLE STOCK
        </span>
      );
    }
    return (
      <span
        className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-gray-100 text-gray-600"
        title="No inventory item selected for this order — stock is unknown, not zero"
      >
        NOT LINKED
      </span>
    );
  };

  const courseText = (c) => {
    if (c.dayOfCourse == null) return c.duration || '—';
    if (c.durationDays) {
      const day = Math.min(c.dayOfCourse, c.durationDays);
      return `Day ${day} of ${c.durationDays}`;
    }
    return c.duration || `Day ${c.dayOfCourse}`;
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="space-y-5">
      {/* Record Section (ACTIVE orders only) — hidden for read-only viewers (e.g. doctors) */}
      {!readOnly && (
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h3 className="font-bold text-gray-800 text-sm mb-4">Record Medication Administration</h3>
          {activeMeds.length === 0 ? (
            <p className="text-sm text-gray-500">
              No active prescriptions available to administer.
            </p>
          ) : (
            <div className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <div>
                  <label htmlFor="fld-169" className="block text-xs font-medium text-gray-600 mb-1">
                    Select Medicine
                  </label>
                  <select
                    id="fld-169"
                    value={selectedPrescriptionId}
                    onChange={(e) => setSelectedPrescriptionId(e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                  >
                    {activeMeds.map((p) => (
                      <option key={p.prescriptionId} value={p.prescriptionId}>
                        {p.medicineName} ({p.dosage || 'No dose'} · {p.route || 'Oral'})
                        {p.pending ? ' — pending today' : ''}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="fld-168" className="block text-xs font-medium text-gray-600 mb-1">
                    Status
                  </label>
                  <select
                    id="fld-168"
                    value={status}
                    onChange={(e) => setStatus(e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                  >
                    <option value="GIVEN">GIVEN</option>
                    <option value="SKIPPED">SKIPPED</option>
                    <option value="DELAYED">DELAYED</option>
                    <option value="REFUSED">REFUSED</option>
                    <option value="NOT_AVAILABLE">NOT AVAILABLE</option>
                  </select>
                </div>
                <div>
                  <label htmlFor="fld-167" className="block text-xs font-medium text-gray-600 mb-1">
                    Administered Time
                  </label>
                  <input
                    id="fld-167"
                    type="datetime-local"
                    value={administeredTime}
                    onChange={(e) => setAdministeredTime(e.target.value)}
                    disabled={status !== 'GIVEN' && status !== 'DELAYED'}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent disabled:bg-gray-100 disabled:text-gray-400"
                  />
                </div>
              </div>
              <div>
                <label htmlFor="fld-166" className="block text-xs font-medium text-gray-600 mb-1">
                  Remarks
                </label>
                <input
                  id="fld-166"
                  type="text"
                  value={remarks}
                  onChange={(e) => setRemarks(e.target.value)}
                  placeholder="e.g., Tolerated well, refused due to nausea, etc."
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
              {separateLogin === false && (
                <div>
                  <label htmlFor="fld-165" className="block text-xs font-medium text-gray-600 mb-1">
                    Performed By Nurse <span className="text-red-600">*</span>
                  </label>
                  <select
                    id="fld-165"
                    value={performedByNurseId}
                    onChange={(e) => setPerformedByNurseId(e.target.value)}
                    required
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                  >
                    <option value="">Select nurse…</option>
                    {nurses.map((n) => (
                      <option key={n.id} value={n.id}>
                        {n.name}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div className="flex justify-end pt-2">
                <button
                  onClick={handleSubmit}
                  disabled={submitting}
                  className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
                >
                  {submitting ? 'Saving…' : 'Record Administration'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Medication Chart — all orders with status + course reminders */}
      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <h3 className="font-bold text-gray-800 text-sm">Medication Chart</h3>
        </div>
        {chart.length === 0 ? (
          <p className="px-5 py-6 text-sm text-gray-500">No prescriptions yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50">
                <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                  <th className="px-5 py-3">MEDICINE</th>
                  <th className="px-5 py-3">DOSE</th>
                  <th className="px-5 py-3">FREQ</th>
                  <th className="px-5 py-3">ROUTE</th>
                  <th className="px-5 py-3">COURSE</th>
                  <th className="px-5 py-3">STATUS</th>
                  <th className="px-5 py-3">STOCK</th>
                  <th className="px-5 py-3">TODAY</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {chart.map((c) => {
                  const stopped = (c.status || '').toUpperCase() === 'STOPPED';
                  return (
                    <tr key={c.prescriptionId} className={stopped ? 'bg-red-50/40' : ''}>
                      <td className="px-5 py-3">
                        <div
                          className={`font-medium ${stopped ? 'text-gray-500 line-through' : 'text-gray-900'}`}
                        >
                          {c.medicineName}
                        </div>
                        {describeFoodTiming(c.foodTiming) && (
                          <div className="text-[11px] text-gray-500">
                            {describeFoodTiming(c.foodTiming)}
                          </div>
                        )}
                        {c.instructions && (
                          <div className="text-[11px] text-gray-400 italic">{c.instructions}</div>
                        )}
                      </td>
                      <td className="px-5 py-3 text-gray-600">{c.dosage || '—'}</td>
                      <td className="px-5 py-3 text-gray-600">{c.frequency || '—'}</td>
                      <td className="px-5 py-3 text-gray-600">
                        <span className="px-2 py-0.5 text-xs font-medium bg-gray-100 text-gray-800 rounded">
                          {c.route || 'ORAL'}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-gray-600">{courseText(c)}</td>
                      <td className="px-5 py-3">{orderBadge(c)}</td>
                      <td className="px-5 py-3">{stockBadge(c)}</td>
                      <td className="px-5 py-3">
                        {c.pending ? (
                          <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-amber-100 text-amber-700">
                            PENDING TODAY
                          </span>
                        ) : c.courseActive && c.administeredToday ? (
                          <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                            GIVEN TODAY
                          </span>
                        ) : (
                          <span className="text-gray-300">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <p className="px-5 py-2 text-[11px] text-gray-400 border-t border-gray-100">
          Stopped medicines are kept for history — the doctor asked to stop them; they are not
          deleted.
        </p>
      </div>

      {/* MAR Timeline */}
      <div className="bg-white border border-gray-200 rounded-xl">
        <div className="px-5 py-3 border-b border-gray-100">
          <h3 className="font-bold text-gray-800 text-sm">
            Medication Administration History (MAR)
          </h3>
        </div>
        {history.length === 0 ? (
          <p className="px-5 py-6 text-sm text-gray-500">No administration records yet.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {history.map((mar) => (
              <li key={mar.publicId || mar.id} className="px-5 py-4">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-gray-900">
                      {medName(mar.prescriptionId)}
                    </span>
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-semibold ${marStatusColor(mar.status)}`}
                    >
                      {mar.status}
                    </span>
                  </div>
                  <div className="text-xs text-gray-500">
                    Administered: <span className="font-medium">{fmt(mar.administeredTime)}</span>
                    {mar.nurseUserId && (
                      <span className="ml-2 font-normal text-gray-400">
                        (By Nurse{' '}
                        {mar.nurseUserId === currentUserId ? 'Me' : `ID: ${mar.nurseUserId}`})
                      </span>
                    )}
                  </div>
                  {mar.remarks && (
                    <p className="text-xs text-gray-600 bg-gray-50 p-2 rounded mt-2 border border-gray-100 italic">
                      Remarks: {mar.remarks}
                    </p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default MedicationPanel;
