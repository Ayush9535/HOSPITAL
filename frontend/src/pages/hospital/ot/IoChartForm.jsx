import React, { useEffect, useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';
import nurseService from '../../../services/nurseService';
import { backdropProps } from '../../../utils/modalA11y';
import { printHtml } from '../../../utils/printHtml';
import { buildIoChartHtml } from '../nurse/VitalsPanel';

/**
 * IoChartForm - the Input & Output chart, relocated into the surgery Consent
 * Forms tab. It is derived from the patient's recorded vitals (no fill/save
 * step), so it simply prints. Reuses the existing buildIoChartHtml.
 */
const IoChartForm = ({ admissionId, onClose }) => {
  const { error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const [rows, setRows] = useState([]);
  const [ioEntries, setIoEntries] = useState([]);
  const [f, setF] = useState({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    Promise.all([
      nurseService.getVitals(admissionId).catch(() => []),
      nurseService.getAdmissionForm(admissionId).catch(() => ({})),
      // ICU-5: the I/O columns come from icu_io_entry, never from the vitals urine field (D-2).
      icuService.getIoEntries(admissionId).catch(() => []),
    ])
      .then(([v, adm, io]) => {
        if (!active) return;
        setRows(Array.isArray(v) ? v : []);
        setF(adm || {});
        setIoEntries(Array.isArray(io) ? io : []);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [admissionId]);

  const print = () => {
    printHtml(
      buildIoChartHtml(
        rows,
        f,
        {
          name: f.hospitalName || user?.hospitalName,
          address: f.hospitalAddress || user?.hospitalAddress,
          logo: f.hospitalLogoUrl || user?.logoUrl,
          customId: f.hospitalCustomId,
          nurse: user?.name,
        },
        ioEntries
      )
    );
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-lg p-6">
        <h2 className="text-lg font-bold text-gray-900 mb-1">Input &amp; Output Chart</h2>
        <p className="text-xs text-gray-500 mb-4">
          Prints the vital columns from recorded vitals; the input/output columns are left blank for
          bedside entry. Flows onto extra pages automatically.
        </p>
        <div className="flex items-center justify-end gap-3">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg font-semibold text-gray-600 hover:bg-gray-100"
          >
            Close
          </button>
          <button
            onClick={print}
            disabled={loading}
            className={`px-4 py-2 rounded-lg font-semibold text-white ${loading ? 'bg-gray-400' : 'bg-indigo-600 hover:bg-indigo-700'}`}
          >
            {loading ? 'Loading…' : 'Print I/O Chart'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default IoChartForm;
