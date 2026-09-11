import React, { useEffect, useState, useCallback } from 'react';
import { useToast } from '../../../context/ToastContext';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';
import useOtPermissions from '../../../hooks/useOtPermissions';

/**
 * SurgeryTeamModal - the surgical team on one case.
 *
 * A member holds a role that is built-in or a hospital custom (perfusionist, harvest
 * surgeon), and is either a listed surgeon or a free-typed external operator. No role
 * check lives here; the server gates on OT_ASSIGN_TEAM.
 */
const SurgeryTeamModal = ({ surgery, onClose }) => {
  const { success, error: toastError } = useToast();
  const [roles, setRoles] = useState([]);
  const [surgeons, setSurgeons] = useState([]);
  const [team, setTeam] = useState([]);
  const [loading, setLoading] = useState(true);
  // Reading the team needs OT_VIEW; changing it needs OT_ASSIGN_TEAM, which reception does not
  // hold. Without this the Add and Remove controls were drawn for anyone who could open the
  // modal and answered with Access Denied.
  const { can } = useOtPermissions();
  const canAssign = can('OT_ASSIGN_TEAM');

  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ caseRoleCode: '', staffId: '', externalName: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [r, t, s] = await Promise.all([
        otService.getCaseRoles(),
        otService.getTeam(surgery.surgeryId),
        otService.getSurgeons().catch(() => []),
      ]);
      setRoles(Array.isArray(r) ? r : []);
      setTeam(Array.isArray(t) ? t : []);
      setSurgeons(Array.isArray(s) ? s : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load team');
    } finally {
      setLoading(false);
    }
  }, [surgery.surgeryId, toastError]);

  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  const nameFor = (m) => {
    if (m.externalName) return m.externalName;
    const doc = surgeons.find(
      (s) => String(s.userId) === String(m.userId) || String(s.doctorId) === String(m.userId)
    );
    return doc ? doc.name : `User #${m.userId}`;
  };
  const labelFor = (code) => roles.find((r) => r.code === code)?.label || code;

  const add = async () => {
    if (!form.caseRoleCode) {
      toastError('Choose a role');
      return;
    }
    const useExternal = form.staffId === '__EXTERNAL__';
    if (!useExternal && !form.staffId) {
      toastError('Choose a staff member or external operator');
      return;
    }
    if (useExternal && !form.externalName.trim()) {
      toastError("Enter the external operator's name");
      return;
    }
    setBusy(true);
    try {
      const created = await otService.assignTeamMember(surgery.surgeryId, {
        caseRoleCode: form.caseRoleCode,
        ...(useExternal
          ? { externalName: form.externalName.trim() }
          : { userId: Number(form.staffId) }),
      });
      setTeam((prev) => [...prev, created]);
      setForm({ caseRoleCode: '', staffId: '', externalName: '' });
      success('Team member added');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to add team member');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (m) => {
    setBusy(true);
    try {
      await otService.removeTeamMember(surgery.surgeryId, m.id);
      setTeam((prev) => prev.filter((x) => x.id !== m.id));
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to remove');
    } finally {
      setBusy(false);
    }
  };

  const addCustomRole = async () => {
    const label = window.prompt('New role name (e.g. Perfusionist, Harvest Surgeon):');
    if (!label || !label.trim()) return;
    try {
      const r = await otService.addCaseRole(label.trim());
      setRoles((prev) => [...prev, { code: r.code, label: r.label, custom: true }]);
      set('caseRoleCode', r.code);
      success('Role added');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to add role');
    }
  };

  const useExternal = form.staffId === '__EXTERNAL__';

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-xl my-8">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Surgical Team</h2>
            <p className="text-xs text-gray-400">
              {surgery.patientName} · {surgery.procedureName}
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="px-6 py-4 space-y-4">
          {loading ? (
            <div className="text-center text-gray-400 py-8">Loading…</div>
          ) : (
            <>
              {team.length > 0 ? (
                <div className="border border-gray-200 rounded-lg divide-y divide-gray-100">
                  {team.map((m) => (
                    <div key={m.id} className="flex items-center justify-between px-3 py-2 text-sm">
                      <div>
                        <span className="font-semibold text-gray-800">{nameFor(m)}</span>
                        <span className="ml-2 text-xs text-gray-400">
                          {labelFor(m.caseRoleCode)}
                        </span>
                        {m.externalName && (
                          <span className="ml-2 text-[10px] px-1.5 py-0.5 bg-gray-100 text-gray-500 rounded">
                            external
                          </span>
                        )}
                      </div>
                      <button
                        disabled={busy || !canAssign}
                        title={canAssign ? undefined : 'Assigned by the surgeon or theatre incharge'}
                        onClick={() => remove(m)}
                        className="text-xs text-red-500 hover:text-red-700"
                      >
                        Remove
                      </button>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-gray-400">No team members yet.</p>
              )}

              <div className="bg-slate-50 border border-gray-200 rounded-lg p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <label htmlFor="fld-10026" className="text-xs font-semibold text-gray-600">
                    Add team member
                  </label>
                  <button
                    type="button"
                    onClick={addCustomRole}
                    className="text-xs text-gray-500 hover:text-gray-700 underline"
                  >
                    + custom role
                  </button>
                </div>
                <select
                  id="fld-10026"
                  value={form.caseRoleCode}
                  onChange={(e) => set('caseRoleCode', e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                >
                  <option value="">Select role…</option>
                  {roles.map((r) => (
                    <option key={r.code} value={r.code}>
                      {r.label}
                      {r.custom ? ' (custom)' : ''}
                    </option>
                  ))}
                </select>
                <select
                  value={form.staffId}
                  onChange={(e) => set('staffId', e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                >
                  <option value="">Select staff…</option>
                  {surgeons.map((s) => (
                    <option key={s.doctorId} value={s.userId || s.doctorId}>
                      {s.name}
                    </option>
                  ))}
                  <option value="__EXTERNAL__">External operator…</option>
                </select>
                {useExternal && (
                  <input
                    value={form.externalName}
                    onChange={(e) => set('externalName', e.target.value)}
                    placeholder="External operator's name"
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                  />
                )}
                <button
                  type="button"
                  disabled={busy || !canAssign}
                  title={canAssign ? undefined : 'Assigned by the surgeon or theatre incharge'}
                  onClick={add}
                  className="w-full px-4 py-2 rounded-lg text-sm font-semibold bg-gray-900 text-white hover:bg-gray-800 disabled:bg-gray-300"
                >
                  + Add
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default SurgeryTeamModal;
