import React, { useState, useEffect, useCallback } from 'react';
import DateSelect from '../../../components/DateSelect';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseService from '../../../services/nurseService';

const today = () => new Date().toISOString().slice(0, 10);
const fmtDate = (v) => {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleDateString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  } catch {
    return String(v);
  }
};

/** Active if today falls within [fromDate, toDate]; otherwise upcoming/past. */
const rangeState = (from, to) => {
  const t = today();
  if (to && to < t) return { label: 'Ended', badge: 'bg-gray-100 text-gray-500' };
  if (from && from > t) return { label: 'Upcoming', badge: 'bg-amber-100 text-amber-700' };
  return { label: 'Active', badge: 'bg-green-100 text-green-700' };
};

/**
 * CoverageView - Nurse Incharge / Admin management of temporary ward
 * assignments and nurse substitutions (Nursing Mgmt Phase F). Both are
 * date-ranged and auto-revert; the primary ward/assignment is never changed.
 * Nurse and ward names are resolved from the incharge's wards and their staff.
 */
const CoverageView = () => {
  const { success, error: toastError } = useToast();

  const [loading, setLoading] = useState(true);
  const [wards, setWards] = useState([]); // [{ wardId, wardName }]
  const [nurses, setNurses] = useState([]); // [{ id, name, wardId, wardName }]
  const [tempAssignments, setTempAssignments] = useState([]);
  const [substitutions, setSubstitutions] = useState([]);
  const [refreshKey, setRefreshKey] = useState(0);

  const reload = () => setRefreshKey((k) => k + 1);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const wardData = await nurseService.getMyWards();
      const wardList = (Array.isArray(wardData) ? wardData : []).map((w) => ({
        wardId: w.wardId,
        wardName: w.wardName,
      }));
      setWards(wardList);

      // Build a nurse directory across all wards (id -> name + home ward).
      const perWard = await Promise.all(
        wardList.map((w) =>
          nurseService
            .getWardStaffNurses(w.wardId)
            .then((list) =>
              (Array.isArray(list) ? list : []).map((n) => ({
                ...n,
                wardId: w.wardId,
                wardName: w.wardName,
              }))
            )
            .catch(() => [])
        )
      );
      const seen = new Set();
      const directory = [];
      perWard.flat().forEach((n) => {
        if (!seen.has(n.id)) {
          seen.add(n.id);
          directory.push(n);
        }
      });
      setNurses(directory);

      const [temps, subs] = await Promise.all([
        nurseService.getTempAssignments().catch(() => []),
        nurseService.getSubstitutions().catch(() => []),
      ]);
      setTempAssignments(Array.isArray(temps) ? temps : []);
      setSubstitutions(Array.isArray(subs) ? subs : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load coverage');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  const nurseName = (profileId) =>
    nurses.find((n) => n.id === profileId)?.name || `Nurse #${profileId}`;
  const wardName = (wardId) =>
    wards.find((w) => w.wardId === wardId)?.wardName || `Ward #${wardId}`;

  const removeTemp = async (publicId) => {
    try {
      await nurseService.removeTempAssignment(publicId);
      success('Temporary assignment removed');
      reload();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to remove');
    }
  };
  const removeSub = async (publicId) => {
    try {
      await nurseService.removeSubstitution(publicId);
      success('Substitution removed');
      reload();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to remove');
    }
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="space-y-8">
      {/* Temporary Ward Assignments */}
      <section className="space-y-3">
        <div>
          <h3 className="text-base font-bold text-gray-800">Temporary Ward Assignments</h3>
          <p className="text-xs text-gray-500">
            Move a nurse to another ward for a period. Reverts automatically when the window ends.
          </p>
        </div>

        <TempAssignmentForm
          nurses={nurses}
          wards={wards}
          onDone={() => {
            success('Temporary assignment created');
            reload();
          }}
          onError={toastError}
        />

        {tempAssignments.length === 0 ? (
          <EmptyState
            icon={null}
            title="No temporary assignments"
            message="No active or upcoming ward moves."
          />
        ) : (
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                  <th className="px-4 py-3">NURSE</th>
                  <th className="px-4 py-3">TEMP WARD</th>
                  <th className="px-4 py-3">FROM</th>
                  <th className="px-4 py-3">TO</th>
                  <th className="px-4 py-3">REASON</th>
                  <th className="px-4 py-3">STATE</th>
                  <th className="px-4 py-3 text-right">ACTIONS</th>
                </tr>
              </thead>
              <tbody>
                {tempAssignments.map((a) => {
                  const st = rangeState(a.fromDate, a.toDate);
                  return (
                    <tr key={a.publicId} className="border-b border-gray-100 hover:bg-gray-50">
                      <td className="px-4 py-3 font-medium text-gray-900">
                        {nurseName(a.nurseProfileId)}
                      </td>
                      <td className="px-4 py-3 text-gray-600">{wardName(a.tempWardId)}</td>
                      <td className="px-4 py-3 text-gray-600">{fmtDate(a.fromDate)}</td>
                      <td className="px-4 py-3 text-gray-600">{fmtDate(a.toDate)}</td>
                      <td className="px-4 py-3 text-gray-500">{a.reason || '—'}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${st.badge}`}
                        >
                          {st.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          onClick={() => removeTemp(a.publicId)}
                          className="px-3 py-1 text-xs font-semibold rounded-lg bg-red-50 text-red-600 hover:bg-red-100"
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Substitutions */}
      <section className="space-y-3">
        <div>
          <h3 className="text-base font-bold text-gray-800">Substitutions</h3>
          <p className="text-xs text-gray-500">
            A replacement nurse covers a primary nurse&apos;s patients for a period, then reverts
            automatically.
          </p>
        </div>

        <SubstitutionForm
          nurses={nurses}
          onDone={() => {
            success('Substitution created');
            reload();
          }}
          onError={toastError}
        />

        {substitutions.length === 0 ? (
          <EmptyState
            icon={null}
            title="No substitutions"
            message="No active or upcoming substitutions."
          />
        ) : (
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                  <th className="px-4 py-3">PRIMARY</th>
                  <th className="px-4 py-3">REPLACEMENT</th>
                  <th className="px-4 py-3">FROM</th>
                  <th className="px-4 py-3">TO</th>
                  <th className="px-4 py-3">REASON</th>
                  <th className="px-4 py-3">STATE</th>
                  <th className="px-4 py-3 text-right">ACTIONS</th>
                </tr>
              </thead>
              <tbody>
                {substitutions.map((s) => {
                  const st = rangeState(s.fromDate, s.toDate);
                  return (
                    <tr key={s.publicId} className="border-b border-gray-100 hover:bg-gray-50">
                      <td className="px-4 py-3 font-medium text-gray-900">
                        {nurseName(s.primaryNurseProfileId)}
                      </td>
                      <td className="px-4 py-3 text-gray-600">
                        {nurseName(s.replacementNurseProfileId)}
                      </td>
                      <td className="px-4 py-3 text-gray-600">{fmtDate(s.fromDate)}</td>
                      <td className="px-4 py-3 text-gray-600">{fmtDate(s.toDate)}</td>
                      <td className="px-4 py-3 text-gray-500">{s.reason || '—'}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${st.badge}`}
                        >
                          {st.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          onClick={() => removeSub(s.publicId)}
                          className="px-3 py-1 text-xs font-semibold rounded-lg bg-red-50 text-red-600 hover:bg-red-100"
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
};

const inputCls =
  'px-3 py-1.5 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500';

/** Inline add form for a temporary ward assignment. */
const TempAssignmentForm = ({ nurses, wards, onDone, onError }) => {
  const [nurseProfileId, setNurseProfileId] = useState('');
  const [tempWardId, setTempWardId] = useState('');
  const [fromDate, setFromDate] = useState(today());
  const [toDate, setToDate] = useState(today());
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    if (!nurseProfileId || !tempWardId) {
      onError('Select a nurse and a ward');
      return;
    }
    setSaving(true);
    try {
      await nurseService.createTempAssignment({
        nurseProfileId: Number(nurseProfileId),
        tempWardId: Number(tempWardId),
        fromDate,
        toDate,
        reason: reason.trim() || undefined,
      });
      setNurseProfileId('');
      setTempWardId('');
      setReason('');
      onDone();
    } catch (err) {
      onError(err?.response?.data?.error || 'Failed to create temporary assignment');
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      onSubmit={submit}
      className="bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap items-end gap-3"
    >
      <div>
        <label htmlFor="fld-157" className="block text-xs font-medium text-gray-500 mb-1">
          Nurse
        </label>
        <select
          id="fld-157"
          value={nurseProfileId}
          onChange={(e) => setNurseProfileId(e.target.value)}
          className={inputCls}
        >
          <option value="">Select nurse</option>
          {nurses.map((n) => (
            <option key={n.id} value={n.id}>
              {n.name} ({n.wardName})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="fld-156" className="block text-xs font-medium text-gray-500 mb-1">
          Temporary Ward
        </label>
        <select
          id="fld-156"
          value={tempWardId}
          onChange={(e) => setTempWardId(e.target.value)}
          className={inputCls}
        >
          <option value="">Select ward</option>
          {wards.map((w) => (
            <option key={w.wardId} value={w.wardId}>
              {w.wardName}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="fld-155" className="block text-xs font-medium text-gray-500 mb-1">
          From
        </label>
        <DateSelect value={fromDate} onChange={(v) => setFromDate(v)} />
      </div>
      <div>
        <label htmlFor="fld-154" className="block text-xs font-medium text-gray-500 mb-1">
          To
        </label>
        <DateSelect value={toDate} onChange={(v) => setToDate(v)} />
      </div>
      <div className="flex-1 min-w-[160px]">
        <label htmlFor="fld-153" className="block text-xs font-medium text-gray-500 mb-1">
          Reason (optional)
        </label>
        <input
          id="fld-153"
          type="text"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className={`${inputCls} w-full`}
          placeholder="e.g. staffing shortage"
        />
      </div>
      <button
        type="submit"
        disabled={saving}
        className={`px-4 py-2 text-sm font-semibold rounded-lg text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
      >
        {saving ? 'Adding…' : 'Add'}
      </button>
    </form>
  );
};

/** Inline add form for a nurse substitution. */
const SubstitutionForm = ({ nurses, onDone, onError }) => {
  const [primaryNurseProfileId, setPrimary] = useState('');
  const [replacementNurseProfileId, setReplacement] = useState('');
  const [fromDate, setFromDate] = useState(today());
  const [toDate, setToDate] = useState(today());
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    if (!primaryNurseProfileId || !replacementNurseProfileId) {
      onError('Select both nurses');
      return;
    }
    if (primaryNurseProfileId === replacementNurseProfileId) {
      onError('Primary and replacement must differ');
      return;
    }
    setSaving(true);
    try {
      await nurseService.createSubstitution({
        primaryNurseProfileId: Number(primaryNurseProfileId),
        replacementNurseProfileId: Number(replacementNurseProfileId),
        fromDate,
        toDate,
        reason: reason.trim() || undefined,
      });
      setPrimary('');
      setReplacement('');
      setReason('');
      onDone();
    } catch (err) {
      onError(err?.response?.data?.error || 'Failed to create substitution');
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      onSubmit={submit}
      className="bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap items-end gap-3"
    >
      <div>
        <label htmlFor="fld-152" className="block text-xs font-medium text-gray-500 mb-1">
          Primary Nurse
        </label>
        <select
          id="fld-152"
          value={primaryNurseProfileId}
          onChange={(e) => setPrimary(e.target.value)}
          className={inputCls}
        >
          <option value="">Select nurse</option>
          {nurses.map((n) => (
            <option key={n.id} value={n.id}>
              {n.name} ({n.wardName})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="fld-151" className="block text-xs font-medium text-gray-500 mb-1">
          Replacement Nurse
        </label>
        <select
          id="fld-151"
          value={replacementNurseProfileId}
          onChange={(e) => setReplacement(e.target.value)}
          className={inputCls}
        >
          <option value="">Select nurse</option>
          {nurses.map((n) => (
            <option key={n.id} value={n.id}>
              {n.name} ({n.wardName})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="fld-150" className="block text-xs font-medium text-gray-500 mb-1">
          From
        </label>
        <DateSelect value={fromDate} onChange={(v) => setFromDate(v)} />
      </div>
      <div>
        <label htmlFor="fld-149" className="block text-xs font-medium text-gray-500 mb-1">
          To
        </label>
        <DateSelect value={toDate} onChange={(v) => setToDate(v)} />
      </div>
      <div className="flex-1 min-w-[160px]">
        <label htmlFor="fld-148" className="block text-xs font-medium text-gray-500 mb-1">
          Reason (optional)
        </label>
        <input
          id="fld-148"
          type="text"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className={`${inputCls} w-full`}
          placeholder="e.g. sick leave"
        />
      </div>
      <button
        type="submit"
        disabled={saving}
        className={`px-4 py-2 text-sm font-semibold rounded-lg text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
      >
        {saving ? 'Adding…' : 'Add'}
      </button>
    </form>
  );
};

export default CoverageView;
