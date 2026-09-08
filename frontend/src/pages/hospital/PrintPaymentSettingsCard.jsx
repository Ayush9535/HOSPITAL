import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import hospitalService from '../../services/hospitalService';

/**
 * PrintPaymentSettingsCard — two related admin controls:
 *  1. Print Settings: which pages the consultation-complete combined print includes.
 *     An "off" page is left out of the merged PDF at consultation complete.
 *  2. Bill Payment timing: FIRST (charge consultation + case-paper at OPD entry, paid there)
 *     or LAST (current flow — payment at/after consultation).
 * Reads/writes via /hospital/settings/print-payment; optimistic, no page reload.
 */
const PRINT_TOGGLES = [
  { key: 'printCasePaper', label: 'Case Paper' },
  { key: 'printBill', label: 'Bill' },
  { key: 'printPrescription', label: 'Prescription' },
  { key: 'printInClinic', label: 'In-Clinic Medicines' },
];

const PrintPaymentSettingsCard = () => {
  const { success, error: toastError } = useToast();
  const [s, setS] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await hospitalService.getHospitalOperationsSettings();
      setS({
        printCasePaper: data.printCasePaper !== false,
        printBill: data.printBill !== false,
        printPrescription: data.printPrescription !== false,
        printInClinic: data.printInClinic !== false,
        billPaymentTiming: data.billPaymentTiming === 'FIRST' ? 'FIRST' : 'LAST',
      });
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load settings');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Optimistic single-field save, revert on error.
  const save = async (patch) => {
    const prev = s;
    const next = { ...s, ...patch };
    setS(next);
    try {
      await hospitalService.updatePrintPaymentSettings(patch);
      success('Settings updated');
    } catch (e) {
      setS(prev);
      toastError(e?.response?.data?.error || 'Failed to update');
    }
  };

  if (loading || !s) return <div className="p-6 text-gray-500 text-sm">Loading settings…</div>;

  return (
    <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6 mt-6 space-y-8">
      {/* Print Settings */}
      <div>
        <h3 className="text-lg font-bold text-gray-900 mb-1">Print Settings</h3>
        <p className="text-sm text-gray-500 mb-4">
          Which pages are included when you print at consultation complete. Turning a page off
          leaves it out of that combined print.
        </p>
        <div className="border border-gray-200 rounded-xl overflow-hidden">
          {PRINT_TOGGLES.map((t, i) => (
            <div
              key={t.key}
              className={`flex items-center justify-between px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}
            >
              <span
                className={`text-sm font-medium ${s[t.key] ? 'text-gray-900' : 'text-gray-400'}`}
              >
                {t.label}
              </span>
              <button
                type="button"
                onClick={() => save({ [t.key]: !s[t.key] })}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${s[t.key] ? 'bg-gray-900' : 'bg-gray-300'}`}
                aria-label={s[t.key] ? 'On' : 'Off'}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${s[t.key] ? 'translate-x-6' : 'translate-x-1'}`}
                />
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Bill Payment timing. Stored as FIRST / LAST; surfaced in the admin's language:
                when does this hospital collect — before the patient sees the doctor, or after. */}
      <div>
        <h3 className="text-lg font-bold text-gray-900 mb-1">Bill Payment</h3>
        <p className="text-sm text-gray-500 mb-4">
          When this hospital collects the consultation + case-paper fee. Anything the doctor adds
          during the consultation (procedures, in-clinic medicines, services) is added to the same
          bill either way, and any balance is collected at checkout.
        </p>
        <div className="space-y-2">
          {[
            {
              value: 'FIRST',
              title: 'Before OPD',
              desc: 'Collect the consultation + case-paper fee at OPD entry, before the patient sees the doctor. Extras added during the consultation leave a balance to settle at checkout.',
            },
            {
              value: 'LAST',
              title: 'After OPD',
              desc: 'Collect everything after the consultation — one bill covering the fee plus anything the doctor added.',
            },
          ].map((opt) => (
            <label
              key={opt.value}
              htmlFor={`billtiming-${opt.value}`}
              className={`flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition-colors ${s.billPaymentTiming === opt.value ? 'border-gray-900 bg-gray-50' : 'border-gray-200 hover:bg-gray-50'}`}
            >
              <input
                id={`billtiming-${opt.value}`}
                type="radio"
                aria-label={opt.title}
                name="billPaymentTiming"
                checked={s.billPaymentTiming === opt.value}
                onChange={() => save({ billPaymentTiming: opt.value })}
                className="mt-1"
              />
              <span>
                <span className="block text-sm font-semibold text-gray-900">{opt.title}</span>
                <span className="block text-xs text-gray-500">{opt.desc}</span>
              </span>
            </label>
          ))}
        </div>
      </div>
    </div>
  );
};

export default PrintPaymentSettingsCard;
