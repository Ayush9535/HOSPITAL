import React, { useEffect, useState, useCallback } from 'react';
import otService from '../../../services/otService';
import SURGERY_FORMS from '../ot/surgeryFormsRegistry';

/**
 * ConsentFormsPanel - the mandatory OT/NABH surgery forms for a surgery patient.
 * Shows the full list; each form opens its own fill/save/print view. A "Saved"
 * badge marks forms already filled. Only forms whose Component is implemented
 * are openable (the rest are added from their reference images over time).
 */
const ConsentFormsPanel = ({ admissionId, formVerdicts = {} }) => {
  const [savedTypes, setSavedTypes] = useState([]);
  const [openForm, setOpenForm] = useState(null); // registry entry currently open

  const refreshSaved = useCallback(() => {
    otService
      .getSavedFormTypes(admissionId)
      .then((t) => setSavedTypes(Array.isArray(t) ? t : []))
      .catch(() => setSavedTypes([]));
  }, [admissionId]);

  useEffect(() => {
    refreshSaved();
  }, [refreshSaved]);

  const closeForm = () => {
    setOpenForm(null);
    refreshSaved();
  };

  return (
    <div>
      <div className="mb-4 rounded-xl border border-indigo-100 bg-indigo-50 px-4 py-3 text-sm text-indigo-800">
        Mandatory surgery forms for this patient. Open a form to fill the details and save it, then
        print it for signatures. Fields you leave blank print empty for offline entry.
      </div>

      <div className="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100">
        {SURGERY_FORMS.filter((form) => formVerdicts[form.type] !== 'HIDDEN').map((form, idx) => {
          const isSaved = savedTypes.includes(form.type);
          const built = !!form.Component;
          return (
            <div key={form.type} className="flex items-center gap-4 px-4 py-3">
              <div className="w-6 text-xs font-semibold text-gray-400">{idx + 1}</div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold text-gray-900">{form.title}</span>
                  {isSaved && (
                    <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                      SAVED
                    </span>
                  )}
                  {!built && (
                    <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-gray-100 text-gray-400">
                      COMING SOON
                    </span>
                  )}
                </div>
                {form.code && <div className="text-xs text-gray-400">{form.code}</div>}
              </div>
              <button
                onClick={() => built && setOpenForm(form)}
                disabled={!built}
                className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${built ? 'bg-gray-900 text-white hover:bg-gray-800' : 'bg-gray-100 text-gray-400 cursor-not-allowed'}`}
              >
                {form.type === 'IO_CHART' ? 'Open' : 'Fill Details'}
              </button>
            </div>
          );
        })}
      </div>

      {openForm && openForm.Component && (
        <openForm.Component
          admissionId={admissionId}
          onClose={closeForm}
          readOnly={formVerdicts[openForm.type] === 'READ_ONLY'}
        />
      )}
    </div>
  );
};

export default ConsentFormsPanel;
