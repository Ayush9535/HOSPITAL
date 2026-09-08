import React, { useEffect, useState, useCallback } from 'react';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';
import { printHtml } from '../../../utils/printHtml';

/**
 * SurgeryFormFrame - shared shell for every OT/NABH surgery form.
 *
 * Handles the common plumbing so each form only declares its fields + print
 * layout:
 *  - loads the saved values (otService.getSurgeryForm) and the patient's
 *    admission record (for pre-fill + hospital branding),
 *  - renders the form body via `renderFields({ data, set, prefill })`,
 *  - Save persists the values; editing after a save marks it dirty again,
 *  - Print is enabled only once saved, and opens `buildPrintHtml(data, prefill, hospital)`.
 *
 * Props:
 *  admissionId, formType, title, code
 *  surgeryId       - the procedure this form belongs to. Preferred: without it the
 *                    backend resolves the admission's active surgery, which is
 *                    ambiguous once the admission carries more than one.
 *  defaults        - default field values (object)
 *  renderFields    - ({ data, set, prefill }) => JSX
 *  buildPrintHtml  - (data, prefill, hospital) => htmlString
 *  onClose         - close handler
 */
const SurgeryFormFrame = ({
  admissionId,
  surgeryId,
  formType,
  title,
  code,
  defaults = {},
  renderFields,
  buildPrintHtml,
  onClose,
  readOnly = false,
}) => {
  const { success, error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const [data, setData] = useState(defaults);
  const [prefill, setPrefill] = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false); // true when current values are persisted
  // A signed form is immutable: the backend supersedes it and appends a new version
  // rather than overwriting, so the UI locks it and says why.
  const [signedAt, setSignedAt] = useState(null);
  const [version, setVersion] = useState(1);
  // Amending a signed form is deliberate, not accidental: saving then appends a new
  // version and leaves the signed one intact.
  const [amending, setAmending] = useState(false);

  // Separate Nurse Login OFF ("Shared Login") -> a required "Performed By
  // Nurse" dropdown is shown and its selection is sent with the save payload.
  const [separateLogin, setSeparateLogin] = useState(true);
  const [nurses, setNurses] = useState([]);
  const [performedByNurseId, setPerformedByNurseId] = useState('');

  useEffect(() => {
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
  }, []);

  useEffect(() => {
    if (separateLogin === false && prefill.wardId) {
      nurseService
        .getWardStaffNurses(prefill.wardId)
        .then((list) => setNurses(Array.isArray(list) ? list : []))
        .catch(() => setNurses([]));
    }
  }, [separateLogin, prefill.wardId]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    const loadForm = surgeryId
      ? otService.getSurgeryFormBySurgery(surgeryId, formType)
      : otService.getSurgeryForm(admissionId, formType);
    // A day-care procedure has no admission record to pre-fill from.
    const loadPrefill = admissionId
      ? nurseService.getAdmissionForm(admissionId).catch(() => ({}))
      : Promise.resolve({});

    Promise.all([loadForm.catch(() => null), loadPrefill])
      .then(([savedForm, adm]) => {
        if (!active) return;
        setPrefill(adm || {});
        setSignedAt(savedForm?.signedAt || null);
        setVersion(savedForm?.version || 1);
        if (savedForm && savedForm.data && Object.keys(savedForm.data).length) {
          setData({ ...defaults, ...savedForm.data });
          setSaved(true);
        } else {
          setData(defaults);
          setSaved(false);
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admissionId, surgeryId, formType]);

  const set = useCallback((key, value) => {
    setData((d) => ({ ...d, [key]: value }));
    setSaved(false); // editing invalidates the printable saved state
  }, []);

  const handleSave = async () => {
    if (separateLogin === false && !performedByNurseId) {
      toastError('Select the nurse who performed this');
      return;
    }
    setSaving(true);
    const nurseId = separateLogin === false ? Number(performedByNurseId) : undefined;
    try {
      const res = surgeryId
        ? await otService.saveSurgeryFormBySurgery(surgeryId, formType, data, nurseId)
        : await otService.saveSurgeryForm(admissionId, formType, data, nurseId);
      setSaved(true);
      setSignedAt(res?.signedAt || null);
      setVersion(res?.version || 1);
      success('Form saved');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save form');
    } finally {
      setSaving(false);
    }
  };

  /** Signing freezes the record. Any later edit is stored as a new version. */
  const handleSign = async () => {
    if (!surgeryId) {
      toastError('Cannot sign this form without a linked surgery');
      return;
    }
    if (!saved) {
      toastError('Save the form before signing');
      return;
    }
    setSaving(true);
    try {
      const res = await otService.signSurgeryForm(surgeryId, formType);
      setSignedAt(res?.signedAt || null);
      success('Form signed');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to sign form');
    } finally {
      setSaving(false);
    }
  };

  // Fields are frozen when the role has read-only access, or when the record is
  // signed and the user has not explicitly chosen to amend it.
  const locked = readOnly || (!!signedAt && !amending);

  const hospital = () => ({
    name: prefill.hospitalName || user?.hospitalName,
    address: prefill.hospitalAddress || user?.hospitalAddress,
    logo: prefill.hospitalLogoUrl || user?.logoUrl,
    customId: prefill.hospitalCustomId,
    nurse: user?.name,
  });

  const handlePrint = () => {
    if (!saved) {
      toastError('Save the form before printing');
      return;
    }
    printHtml(buildPrintHtml(data, prefill, hospital()));
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-3xl my-6">
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-100 sticky top-0 bg-white rounded-t-2xl">
          <div>
            <h2 className="text-lg font-bold text-gray-900">{title}</h2>
            {code && <p className="text-xs text-gray-400 mt-0.5">{code}</p>}
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <fieldset disabled={locked} style={{ display: 'contents' }}>
          {readOnly && (
            <div className="mx-6 mt-4 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
              Read-only — editing this form is disabled for your role (Files &amp; Access).
            </div>
          )}
          {signedAt && !readOnly && (
            <div className="mx-6 mt-4 flex items-center justify-between gap-3 text-xs font-semibold text-emerald-800 bg-emerald-50 border border-emerald-100 rounded-lg px-3 py-2">
              <span>
                Signed on {new Date(signedAt).toLocaleString()} — version {version}. A signed form
                cannot be changed.
              </span>
              {!amending && (
                <button
                  type="button"
                  onClick={() => setAmending(true)}
                  className="shrink-0 px-2 py-1 rounded-md border border-emerald-300 text-emerald-800 hover:bg-emerald-100"
                >
                  Amend
                </button>
              )}
            </div>
          )}
          {amending && (
            <div className="mx-6 mt-2 text-xs font-semibold text-indigo-700 bg-indigo-50 border border-indigo-100 rounded-lg px-3 py-2">
              Amending — saving keeps the signed version {version} and records your changes as
              version {version + 1}.
            </div>
          )}
          <div className="px-6 py-5">
            {loading ? (
              <div className="text-center text-gray-400 py-12">Loading…</div>
            ) : (
              renderFields({ data, set, prefill })
            )}
          </div>

          {separateLogin === false && (
            <div className="px-6 pb-4">
              <label htmlFor="fld-205" className="block text-xs font-medium text-gray-600 mb-1">
                Performed By Nurse <span className="text-red-600">*</span>
              </label>
              <select
                id="fld-205"
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
        </fieldset>
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-gray-100 sticky bottom-0 bg-white rounded-b-2xl">
          {!saved && !locked && (
            <span className="mr-auto text-xs text-amber-600">
              Unsaved — save to enable printing
            </span>
          )}
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg font-semibold text-gray-600 hover:bg-gray-100"
          >
            Close
          </button>
          {!locked && (
            <button
              onClick={handleSave}
              disabled={saving || loading}
              className={`px-4 py-2 rounded-lg font-semibold text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
            >
              {saving ? 'Saving…' : amending ? `Save as version ${version + 1}` : 'Save'}
            </button>
          )}
          {surgeryId && !readOnly && !signedAt && (
            <button
              onClick={handleSign}
              disabled={saving || loading || !saved}
              className={`px-4 py-2 rounded-lg font-semibold ${saved ? 'bg-emerald-600 text-white hover:bg-emerald-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}
            >
              Sign
            </button>
          )}
          <button
            onClick={handlePrint}
            disabled={!saved || loading}
            className={`px-4 py-2 rounded-lg font-semibold ${saved ? 'bg-indigo-600 text-white hover:bg-indigo-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}
          >
            Print
          </button>
        </div>
      </div>
    </div>
  );
};

export default SurgeryFormFrame;
