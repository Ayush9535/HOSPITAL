import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import formAccessService from '../../services/formAccessService';

// The "other" editor depends on the hospital: a nursing hospital edits with nurses; a
// non-nursing hospital (the IPD Forms setting) edits with reception. BOTH means "doctor + that
// other role", so the stored value works either way.
const ACCESS_OPTIONS_BY_CONTEXT = {
  NURSE: [
    { value: 'DOCTOR', label: 'Doctor' },
    { value: 'NURSE', label: 'Nurse' },
    { value: 'BOTH', label: 'Both' },
  ],
  RECEPTION: [
    { value: 'DOCTOR', label: 'Doctor' },
    { value: 'RECEPTION', label: 'Reception' },
    { value: 'BOTH', label: 'Both' },
  ],
};

/**
 * FilesAndAccessCard - admin table controlling which forms are active and who
 * may edit each (Files & Access, Phase 1). Rows come from the backend registry;
 * each change PUTs an override.
 *
 * `category` optionally narrows the card to one group ('NURSING' or 'OT'), so the
 * two groups can be shown as separate Settings boxes. Omit it for the full card.
 */
const FilesAndAccessCard = ({ category = null, title, description, accessContext = 'NURSE' }) => {
  const { success, error: toastError } = useToast();
  const accessOptions = ACCESS_OPTIONS_BY_CONTEXT[accessContext] || ACCESS_OPTIONS_BY_CONTEXT.NURSE;
  const [forms, setForms] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setForms(await formAccessService.list());
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load forms');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  // Load once on mount only. Do NOT depend on `load`: useToast() returns fresh
  // success/error identities on every ToastProvider render (e.g. when a toast
  // appears then auto-dismisses), which would otherwise re-run this effect and
  // refetch the list twice — swapping the card content and scrolling to top.
  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Update this one row in place — optimistic, with an in-place revert on
  // error — so the page never reloads or scrolls; only a toast confirms it.
  const save = async (form, patch) => {
    const prev = form;
    const next = { ...form, ...patch };
    setForms((list) => list.map((f) => (f.key === form.key ? next : f)));
    try {
      await formAccessService.update(form.key, {
        enabled: next.enabled,
        accessRole: next.accessRole,
      });
      success('Form access updated');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to update');
      setForms((list) => list.map((f) => (f.key === form.key ? prev : f)));
    }
  };

  if (loading) return <div className="p-6 text-gray-500 text-sm">Loading forms…</div>;

  const allGroups = [
    { category: 'NURSING', title: 'Nursing Records' },
    { category: 'OT', title: 'OT / Surgery Forms' },
  ];
  const groups = category ? allGroups.filter((g) => g.category === category) : allGroups;
  const heading = title || 'Files & Access';
  const subheading =
    description ||
    'Turn forms on or off for this hospital and choose who can edit each one. Off forms are hidden everywhere.';

  return (
    <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6 mt-6">
      <h3 className="text-lg font-bold text-gray-900 mb-1">{heading}</h3>
      <p className="text-sm text-gray-500 mb-5">{subheading}</p>
      {groups.map((g) => {
        const rows = forms.filter((f) => f.category === g.category);
        if (rows.length === 0) return null;
        return (
          <div key={g.category} className="mb-6 last:mb-0">
            {!category && (
              <h4 className="text-xs font-bold uppercase tracking-wider text-gray-400 mb-2">
                {g.title}
              </h4>
            )}
            <div className="border border-gray-200 rounded-xl overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Form</th>
                    <th className="px-4 py-2.5 text-left font-semibold text-gray-600">
                      Accessed by
                    </th>
                    <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((f) => (
                    <tr
                      key={f.key}
                      className={`border-b border-gray-100 last:border-0 ${!f.enabled ? 'bg-gray-50/60' : ''}`}
                    >
                      <td
                        className={`px-4 py-3 font-medium ${f.enabled ? 'text-gray-900' : 'text-gray-400'}`}
                      >
                        {f.label}
                      </td>
                      <td className="px-4 py-3">
                        <select
                          value={f.accessRole}
                          disabled={!f.enabled}
                          onChange={(e) => save(f, { accessRole: e.target.value })}
                          className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg disabled:bg-gray-100 disabled:text-gray-400"
                        >
                          {accessOptions.map((o) => (
                            <option key={o.value} value={o.value}>
                              {o.label}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-3">
                        <button
                          type="button"
                          onClick={() => save(f, { enabled: !f.enabled })}
                          className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${f.enabled ? 'bg-gray-900' : 'bg-gray-300'}`}
                          aria-label={f.enabled ? 'On' : 'Off'}
                        >
                          <span
                            className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${f.enabled ? 'translate-x-6' : 'translate-x-1'}`}
                          />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default FilesAndAccessCard;
