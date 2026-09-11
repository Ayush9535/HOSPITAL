import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import otService from '../../services/otService';

const ROLE_LABELS = {
  DOCTOR: 'Doctor',
  RECEPTIONIST: 'Receptionist',
  NURSE: 'Nurse',
  NURSE_INCHARGE: 'Nurse Incharge',
  OT_INCHARGE: 'OT Incharge',
  HOSPITAL_ADMIN: 'Hospital Admin',
};

/**
 * OtPermissionsCard - the OT permission matrix (role x permission).
 *
 * The workflow never asks who someone is, only whether they hold a permission. This
 * screen is where a hospital decides that: a small hospital can let its surgeon
 * schedule his own list; a corporate hospital can hand scheduling to an OT Coordinator.
 * Neither needs a code change.
 *
 * A hospital that has never edited this uses the built-in defaults; saving once
 * materialises the whole matrix.
 */
const OtPermissionsCard = () => {
  const { success, error: toastError } = useToast();
  const [catalogue, setCatalogue] = useState({ permissions: [], roles: [] });
  const [matrix, setMatrix] = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [cat, mtx] = await Promise.all([
        otService.getOtPermissionCatalogue(),
        otService.getOtPermissionMatrix(),
      ]);
      setCatalogue(cat || { permissions: [], roles: [] });
      setMatrix(mtx || {});
      setDirty(false);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load OT permissions');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  // Load once on mount. Do NOT depend on `load`: useToast() returns fresh identities
  // on every ToastProvider render, which would refetch each time a toast appears.
  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (role, code) => {
    setMatrix((prev) => {
      const held = new Set(prev[role] || []);
      if (held.has(code)) held.delete(code);
      else held.add(code);
      return { ...prev, [role]: Array.from(held) };
    });
    setDirty(true);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const saved = await otService.updateOtPermissionMatrix(matrix);
      setMatrix(saved || matrix);
      setDirty(false);
      success('OT permissions updated — users see the change after their next login');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save OT permissions');
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    setSaving(true);
    try {
      setMatrix(await otService.resetOtPermissions());
      setDirty(false);
      success('OT permissions reset to defaults');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to reset OT permissions');
    } finally {
      setSaving(false);
    }
  };

  if (loading)
    return (
      <div className="bg-white rounded-2xl border border-gray-200 p-6 text-gray-400">Loading…</div>
    );

  const roles = catalogue.roles || [];

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-lg font-semibold text-gray-900">OT Permissions</h3>
          <p className="text-xs text-gray-500 mt-1">
            Who may do what in the Operation Theatre. Changes apply the next time the user logs in.
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={handleReset}
            disabled={saving}
            className="text-xs text-gray-500 hover:text-gray-700 underline"
          >
            Reset to defaults
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving || !dirty}
            className={`px-4 py-2 rounded-lg text-xs font-semibold text-white ${saving || !dirty ? 'bg-gray-300' : 'bg-gray-900 hover:bg-gray-800'}`}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200">
              <th className="text-left py-2 pr-4 font-semibold text-gray-700">Permission</th>
              {roles.map((r) => (
                <th
                  key={r}
                  className="px-3 py-2 text-center text-xs font-semibold text-gray-600 whitespace-nowrap"
                >
                  {ROLE_LABELS[r] || r}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {(catalogue.permissions || []).map((p) => (
              <tr key={p.code} className="hover:bg-slate-50/60">
                <td className="py-2 pr-4">
                  <div className="font-medium text-gray-800">{p.description}</div>
                  <div className="text-[11px] text-gray-400 font-mono">{p.code}</div>
                </td>
                {roles.map((r) => (
                  <td key={r} className="px-3 py-2 text-center">
                    <input
                      type="checkbox"
                      className="h-4 w-4"
                      checked={(matrix[r] || []).includes(p.code)}
                      onChange={() => toggle(r, p.code)}
                      aria-label={`${p.code} for ${r}`}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {dirty && <p className="text-xs text-amber-600">Unsaved changes.</p>}
    </div>
  );
};

export default OtPermissionsCard;
