import React, { useEffect, useState } from 'react';
import hospitalService from '../services/hospitalService';
import { printHtml } from '../utils/printHtml';

const PrescriptionViewModal = ({ isOpen, onClose, patient }) => {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (isOpen && patient) {
      fetchPrescription();
    } else {
      setData(null);
      setError(null);
    }
  }, [isOpen, patient]);

  const fetchPrescription = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await hospitalService.getLatestPrescription(patient.publicId || patient.id);
      setData(result);
    } catch (err) {
      console.error('Failed to fetch prescription', err);
      setError(err.response?.data || 'No prescription records found for this patient.');
    } finally {
      setLoading(false);
    }
  };

  const handlePrint = () => {
    if (!data) return;
    const { medicalRecord, prescriptions } = data;
    const rows =
      (prescriptions || [])
        .map(
          (med) =>
            `<tr>
                <td>${med.medicineName || ''}</td>
                <td>${med.dosage || ''}</td>
                <td>${med.frequency || ''}</td>
                <td>${med.duration || ''}</td>
                <td>${med.instructions || ''}</td>
            </tr>`
        )
        .join('') ||
      '<tr><td colspan="5" style="text-align:center;color:#888">No medicines prescribed.</td></tr>';

    const html = `<!DOCTYPE html><html><head><title>Prescription</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 24px; color: #333; }
            h2 { font-size: 18px; margin-bottom: 4px; }
            .sub { font-size: 13px; color: #666; margin-bottom: 16px; }
            .section { background: #f9f9f9; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px; margin-bottom: 16px; }
            .label { font-size: 11px; text-transform: uppercase; color: #6b7280; margin-bottom: 2px; }
            .value { font-size: 14px; font-weight: 600; }
            table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 8px; }
            th { background: #f3f4f6; padding: 8px; text-align: left; font-size: 11px; text-transform: uppercase; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
            td { padding: 8px; border-bottom: 1px solid #f3f4f6; }
            @media print { body { padding: 0; } }
        </style></head><body>
        <h2>Prescription</h2>
        <div class="sub">Patient: <strong>${patient?.name || ''}</strong> &bull; ID: ${patient?.customId || patient?.publicId || ''}</div>
        <div class="section">
            <div class="label">Diagnosis</div><div class="value">${medicalRecord?.diagnosis || '-'}</div>
            <div class="label" style="margin-top:8px">Symptoms</div><div class="value">${medicalRecord?.symptoms || '-'}</div>
            ${medicalRecord?.treatmentNotes ? `<div class="label" style="margin-top:8px">Treatment Notes</div><div class="value">${medicalRecord.treatmentNotes}</div>` : ''}
            ${medicalRecord?.followUpDate ? `<div class="label" style="margin-top:8px">Follow-up</div><div class="value">${medicalRecord.followUpDate}</div>` : ''}
        </div>
        <table><thead><tr>
            <th>Medicine</th><th>Dosage</th><th>Frequency</th><th>Duration</th><th>Instructions</th>
        </tr></thead><tbody>${rows}</tbody></table>
        <script>window.onload = function() { window.print(); };</script>
        </body></html>`;

    printHtml(html);
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-4xl max-h-[90vh] flex flex-col m-4 animate-fade-in-up">
        {/* Header */}
        <div className="p-6 border-b border-gray-200 flex justify-between items-center bg-white rounded-t-lg">
          <div>
            <h3 className="text-xl font-bold text-gray-800">Prescription Details</h3>
            <p className="text-sm text-gray-600 mt-1">
              <span className="font-semibold">{patient?.name}</span> • ID:{' '}
              {patient?.customId || patient?.publicId || patient?.id}
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {loading ? (
            <div className="flex justify-center items-center h-48">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-purple-600"></div>
            </div>
          ) : error ? (
            <div className="text-center py-12 text-gray-500 bg-gray-50 rounded-lg border border-dashed border-gray-300">
              <p>{error}</p>
            </div>
          ) : data ? (
            <div className="space-y-8">
              {/* Medical Record Info */}
              <div className="bg-gray-50 p-4 rounded-lg border border-gray-200">
                <h4 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                  Clinical Notes
                </h4>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  <div>
                    <p className="text-xs text-gray-500 uppercase">Diagnosis</p>
                    <p className="font-medium text-gray-800">
                      {data.medicalRecord.diagnosis || '-'}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-gray-500 uppercase">Symptoms</p>
                    <p className="font-medium text-gray-800">
                      {data.medicalRecord.symptoms || '-'}
                    </p>
                  </div>
                  <div className="md:col-span-2">
                    <p className="text-xs text-gray-500 uppercase">Treatment Notes</p>
                    <p className="font-medium text-gray-800">
                      {data.medicalRecord.treatmentNotes || '-'}
                    </p>
                  </div>
                  {data.medicalRecord.followUpDate && (
                    <div>
                      <p className="text-xs text-gray-500 uppercase">Follow-up Date</p>
                      <p className="font-medium text-blue-600">{data.medicalRecord.followUpDate}</p>
                    </div>
                  )}
                </div>
              </div>

              {/* Medicines Table */}
              <div>
                <h4 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3 flex items-center">
                  Prescribed Medicines
                </h4>
                <div className="border rounded-lg overflow-hidden">
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          Medicine
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          Dosage
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          Frequency
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          Duration
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          Instructions
                        </th>
                      </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                      {data.prescriptions && data.prescriptions.length > 0 ? (
                        data.prescriptions.map((med, idx) => (
                          <tr key={idx}>
                            <td className="px-4 py-3 text-sm font-medium text-gray-900">
                              {med.medicineName}
                            </td>
                            <td className="px-4 py-3 text-sm text-gray-600">{med.dosage}</td>
                            <td className="px-4 py-3 text-sm text-gray-600">{med.frequency}</td>
                            <td className="px-4 py-3 text-sm text-gray-600">{med.duration}</td>
                            <td className="px-4 py-3 text-sm text-gray-600">{med.instructions}</td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan="5" className="px-4 py-4 text-center text-sm text-gray-500">
                            No medicines prescribed.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          ) : null}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-100 flex justify-end bg-gray-50 rounded-b-xl">
          <button
            onClick={onClose}
            className="px-4 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 font-medium transition"
          >
            Close
          </button>
          {data && (
            <button
              onClick={handlePrint}
              className="ml-3 px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 font-medium flex items-center gap-2 transition"
            >
              <span>Print</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default PrescriptionViewModal;
