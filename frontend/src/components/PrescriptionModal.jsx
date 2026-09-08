import React from 'react';

const PrescriptionModal = ({ isOpen, onClose, data, onPrint }) => {
  if (!isOpen || !data) return null;

  const { medicalRecord, prescriptions } = data;

  return (
    <div
      className="fixed inset-0 z-50 overflow-y-auto"
      aria-labelledby="modal-title"
      role="dialog"
      aria-modal="true"
    >
      <div className="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
        <div
          className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"
          aria-hidden="true"
          onClick={onClose}
        ></div>

        <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">
          &#8203;
        </span>

        <div className="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-3xl sm:w-full">
          {/* Header */}
          <div className="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4 border-b border-gray-100">
            <div className="sm:flex sm:items-start justify-between">
              <h3 className="text-xl leading-6 font-semibold text-gray-900" id="modal-title">
                Prescription Details
              </h3>
              <button onClick={onClose} className="text-gray-400 hover:text-gray-500">
                <span className="sr-only">Close</span>
                <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth="2"
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="px-4 py-5 sm:p-6">
            {/* Clinical Notes */}
            <div className="mb-6 bg-gray-50 p-4 rounded-lg border border-gray-200">
              <h4 className="text-sm font-bold text-gray-900 uppercase tracking-wide mb-3">
                Clinical Notes
              </h4>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-blue-600 font-semibold">Diagnosis</p>
                  <p className="text-gray-800">{medicalRecord.diagnosis || 'Not recorded'}</p>
                </div>
                <div>
                  <p className="text-xs text-blue-600 font-semibold">Symptoms</p>
                  <p className="text-gray-800">{medicalRecord.symptoms || 'Not recorded'}</p>
                </div>
                <div className="col-span-2">
                  <p className="text-xs text-blue-600 font-semibold">Treatment Notes</p>
                  <p className="text-gray-800">{medicalRecord.treatmentNotes || 'None'}</p>
                </div>
              </div>
            </div>

            {/* Medicines Table */}
            <div className="mb-6">
              <h4 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                Prescribed Medicines
              </h4>
              <div className="overflow-hidden shadow ring-1 ring-black ring-opacity-5 md:rounded-lg">
                <table className="min-w-full divide-y divide-gray-300">
                  <thead className="bg-gray-50">
                    <tr>
                      <th
                        scope="col"
                        className="py-3 pl-4 pr-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500 sm:pl-6"
                      >
                        Medicine
                      </th>
                      <th
                        scope="col"
                        className="px-3 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                      >
                        Dosage
                      </th>
                      <th
                        scope="col"
                        className="px-3 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                      >
                        Frequency
                      </th>
                      <th
                        scope="col"
                        className="px-3 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                      >
                        Duration
                      </th>
                      <th
                        scope="col"
                        className="px-3 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                      >
                        Instructions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {prescriptions && prescriptions.length > 0 ? (
                      prescriptions.map((med, idx) => (
                        <tr key={idx}>
                          <td className="whitespace-nowrap py-3 pl-4 pr-3 text-sm font-medium text-gray-900 sm:pl-6">
                            {med.medicineName}
                          </td>
                          <td className="whitespace-nowrap px-3 py-3 text-sm text-gray-500">
                            {med.dosage}
                          </td>
                          <td className="whitespace-nowrap px-3 py-3 text-sm text-gray-500">
                            {med.frequency}
                          </td>
                          <td className="whitespace-nowrap px-3 py-3 text-sm text-gray-500">
                            {med.duration}
                          </td>
                          <td className="whitespace-nowrap px-3 py-3 text-sm text-gray-500">
                            {med.instructions}
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan="5" className="px-3 py-4 text-sm text-gray-500 text-center">
                          No medicines prescribed.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            {medicalRecord.followUpDate && (
              <div className="text-sm text-gray-600 bg-gray-50 p-2 rounded border border-gray-200 inline-block">
                <span className="font-semibold text-gray-900">Follow-up Required:</span>{' '}
                {new Date(medicalRecord.followUpDate).toLocaleDateString()}
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse">
            <button
              type="button"
              className="w-full inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-gray-900 text-base font-medium text-white hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500 sm:ml-3 sm:w-auto sm:text-sm"
              onClick={onPrint}
            >
              Print PDF
            </button>
            <button
              type="button"
              className="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
              onClick={onClose}
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default PrescriptionModal;
