import React, { useState, useEffect } from 'react';
// BUG-028: single source-of-truth for base URL
import authService from '../services/authService';
import hospitalService from '../services/hospitalService';
import PatientDocumentsPanel from './PatientDocumentsPanel';
import PdfViewerModal from './PdfViewerModal';

/**
 * PatientDetailsModal - Read-only view of patient information with tabs for additional data
 *
 * Features:
 * - Basic patient information (read-only)
 * - Tabbed interface for: Info, Case Papers (OPD History + Prescriptions), Bills
 */
const PatientDetailsModal = ({ patient, onClose, initialTab = 'info' }) => {
  const [activeTab, setActiveTab] = useState(initialTab);

  const [opdHistory, setOpdHistory] = useState([]);
  const [ipdHistory, setIpdHistory] = useState([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [historyFilter, setHistoryFilter] = useState('OPD');

  const [bills, setBills] = useState([]);
  const [loadingBills, setLoadingBills] = useState(false);
  const [printingBillId, setPrintingBillId] = useState(null);

  const [downloadingCasePaperId, setDownloadingCasePaperId] = useState(null);
  const [printingPrescriptionId, setPrintingPrescriptionId] = useState(null);
  const [printingMedicinesId, setPrintingMedicinesId] = useState(null);
  const [printingIpdPrescriptionId, setPrintingIpdPrescriptionId] = useState(null);
  const [printingIpdMedicinesId, setPrintingIpdMedicinesId] = useState(null);
  const [expandedAdmissionIds, setExpandedAdmissionIds] = useState({});
  // The document to show in the inline PDF viewer: { endpointPath, title } or null.
  const [viewerDoc, setViewerDoc] = useState(null);

  const user = authService.getCurrentUser();
  const inClinicEnabled = user?.inClinic !== false;

  // Open the document in the inline viewer so the user can read it first, then Print/Download
  // from the viewer's toolbar (instead of firing the print dialog straight away).
  const openDocument = (endpointPath, title) => {
    setViewerDoc({ endpointPath, title });
  };

  const handlePrintPrescription = (opdId) => {
    openDocument(`/hospital/doctors/prescription/opd/${opdId}/pdf`, 'Prescription');
  };

  const handlePrintOpdMedicines = (opdId) => {
    openDocument(`/hospital/patients/opd/${opdId}/medicines/pdf`, 'In-Clinic Medicines');
  };

  const handlePrintIpdPrescription = (ipdId) => {
    openDocument(`/hospital/patients/ipd/${ipdId}/prescription/pdf`, 'IPD Prescription');
  };

  const handlePrintIpdMedicines = (ipdId) => {
    openDocument(`/hospital/patients/ipd/${ipdId}/medicines/pdf`, 'IPD Medicines');
  };

  const handleDownloadCasePaper = (opdId) => {
    openDocument(`/hospital/opd/${opdId}/pdf`, 'Case Paper');
  };

  useEffect(() => {
    const fetchHistory = async () => {
      setLoadingHistory(true);
      try {
        const pid = patient.publicId || patient.id;
        const res = await hospitalService.getPatientConsultationDetails(pid);
        setOpdHistory(res.opdHistory || []);
        setIpdHistory(res.ipdHistory || []);
      } catch (err) {
        console.error('Failed to fetch medical history:', err);
      } finally {
        setLoadingHistory(false);
      }
    };

    const fetchBills = async () => {
      setLoadingBills(true);
      try {
        const pid = patient.publicId || patient.id;
        const res = await hospitalService.getPatientBills(pid);
        setBills(res || []);
      } catch (err) {
        console.error('Failed to fetch patient bills:', err);
      } finally {
        setLoadingBills(false);
      }
    };

    if (patient) {
      if (activeTab === 'medicalhistory') {
        fetchHistory();
      } else if (activeTab === 'bills') {
        fetchBills();
      }
    }
  }, [activeTab, patient?.id, patient?.publicId]);

  // BUG-039: Escape key dismissal and auto-focus for accessibility
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);

    // Auto-focus the close button or first tab on mount
    const closeBtn = document.getElementById('patient-modal-close-btn');
    closeBtn?.focus();

    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  if (!patient) return null;

  // A pharmacy session has no patient record to attach paperwork to, and the backend refuses
  // these routes for one outright; the tab would only ever produce an error.
  const canSeeDocuments = ['HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST'].includes(user?.role);

  const tabs = [
    { id: 'info', label: 'Patient Info' },
    { id: 'medicalhistory', label: 'Medical History' },
    ...(canSeeDocuments ? [{ id: 'documents', label: 'Documents' }] : []),
    { id: 'bills', label: 'Bills' },
  ];

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-2 sm:p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="patient-details-title"
        aria-describedby="patient-details-subtitle"
        className="bg-white rounded-xl shadow-xl w-full max-w-4xl max-h-[95vh] sm:max-h-[90vh] flex flex-col transform transition-all scale-100"
      >
        {/* Header */}
        <div className="flex justify-between items-center p-4 sm:p-6 border-b border-gray-200">
          <div>
            <h2 id="patient-details-title" className="text-xl sm:text-2xl font-bold text-gray-900">
              Patient Details
            </h2>
            <p id="patient-details-subtitle" className="text-xs sm:text-sm text-gray-600 mt-1">
              Patient ID: {patient.customId || patient.publicId || patient.id}
            </p>
          </div>
          <button
            id="patient-modal-close-btn"
            onClick={onClose}
            aria-label="Close details dialog"
            className="text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg focus:outline-none focus:ring-2 focus:ring-gray-900 focus:ring-offset-2"
          >
            <svg
              className="w-6 h-6"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              aria-hidden="true"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Tabs */}
        <div className="border-b border-gray-200 px-4 sm:px-6">
          <div
            className="flex space-x-2 sm:space-x-4"
            role="tablist"
            aria-label="Patient details tabs"
          >
            {tabs.map((tab) => (
              <button
                key={tab.id}
                id={`tab-${tab.id}`}
                role="tab"
                aria-selected={activeTab === tab.id}
                aria-controls={`panel-${tab.id}`}
                tabIndex={activeTab === tab.id ? 0 : -1}
                onClick={() => setActiveTab(tab.id)}
                className={`py-3 px-3 sm:px-4 text-xs sm:text-sm font-medium border-b-2 transition-colors focus:outline-none focus:text-gray-900 ${
                  activeTab === tab.id
                    ? 'border-gray-900 text-gray-900 font-semibold'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {/* Content */}
        <div
          id={`panel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`tab-${activeTab}`}
          className="flex-1 overflow-y-auto p-4 sm:p-6"
        >
          {activeTab === 'info' && (
            <div className="space-y-6">
              {/* Basic Information */}
              <div>
                <h3 className="text-lg font-semibold text-gray-900 mb-4">Basic Information</h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <InfoField label="Full Name" value={patient.name} />
                  <InfoField
                    label="Patient ID"
                    value={patient.customId || patient.publicId || patient.id}
                  />
                  <InfoField label="Age" value={patient.age} />
                  <InfoField label="Gender" value={patient.gender} />
                  <InfoField label="Phone Number" value={patient.phone} />
                  <InfoField label="Email" value={patient.email || 'N/A'} />
                  <InfoField label="Blood Group" value={patient.bloodGroup || 'N/A'} />
                  <InfoField
                    label="Registration Date"
                    value={
                      patient.createdAt ? new Date(patient.createdAt).toLocaleDateString() : 'N/A'
                    }
                  />
                </div>
              </div>

              {/* Address */}
              {patient.address && (
                <div>
                  <h3 className="text-lg font-semibold text-gray-900 mb-4">Address</h3>
                  <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                    <p className="text-gray-700">{patient.address}</p>
                  </div>
                </div>
              )}

              {/* Medical Information */}
              <div>
                <h3 className="text-lg font-semibold text-gray-900 mb-4">Medical Information</h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <InfoField label="Allergies" value={patient.allergies || 'None reported'} />
                  <InfoField
                    label="Chronic Conditions"
                    value={patient.chronicConditions || 'None reported'}
                  />
                  <InfoField
                    label="Current Medications"
                    value={patient.currentMedications || 'None'}
                  />
                  <InfoField label="Emergency Contact" value={patient.emergencyContact || 'N/A'} />
                </div>
              </div>
            </div>
          )}

          {activeTab === 'medicalhistory' && (
            <div className="space-y-4">
              {/* Toggle OPD / IPD Filter at top right */}
              <div className="flex justify-between items-center border-b border-gray-150 pb-3">
                <span className="text-lg font-bold text-gray-800">Clinical History Feed</span>
                <div className="inline-flex rounded-lg border border-gray-300 p-0.5 bg-gray-50">
                  <button
                    onClick={() => setHistoryFilter('OPD')}
                    className={`px-4 py-1.5 text-xs font-semibold rounded-md transition-all ${
                      historyFilter === 'OPD'
                        ? 'bg-white text-gray-950 shadow-sm border border-gray-200'
                        : 'text-gray-500 hover:text-gray-800'
                    }`}
                  >
                    OPD History
                  </button>
                  <button
                    onClick={() => setHistoryFilter('IPD')}
                    className={`px-4 py-1.5 text-xs font-semibold rounded-md transition-all ${
                      historyFilter === 'IPD'
                        ? 'bg-white text-gray-950 shadow-sm border border-gray-200'
                        : 'text-gray-500 hover:text-gray-800'
                    }`}
                  >
                    IPD History
                  </button>
                </div>
              </div>

              {loadingHistory ? (
                <div className="text-center py-12 text-gray-500">
                  <div className="animate-spin h-6 w-6 border-b-2 border-gray-900 mx-auto mb-2 rounded-full"></div>
                  Loading medical history...
                </div>
              ) : historyFilter === 'OPD' ? (
                opdHistory.length > 0 ? (
                  opdHistory.map((record) => (
                    <div
                      key={record.id}
                      className="bg-white rounded-lg p-5 border border-gray-200 shadow-sm space-y-4"
                    >
                      {/* Header */}
                      <div className="flex justify-between items-start border-b border-gray-100 pb-2">
                        <div>
                          <span className="text-sm font-semibold text-gray-800">
                            {record.doctorName || 'Doctor'}
                          </span>
                          <span className="mx-2 text-gray-300">•</span>
                          <span className="text-xs text-gray-500">
                            {record.date
                              ? new Date(record.date).toLocaleDateString('en-US', {
                                  year: 'numeric',
                                  month: 'short',
                                  day: 'numeric',
                                })
                              : 'N/A'}
                          </span>
                        </div>
                        {record.followUpDate && (
                          <span className="text-xs bg-blue-50 text-blue-700 px-2 py-0.5 rounded-full font-medium">
                            Follow-up: {new Date(record.followUpDate).toLocaleDateString()}
                          </span>
                        )}
                      </div>

                      {/* Clinical Details */}
                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        {record.symptoms && (
                          <div>
                            <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                              Symptoms
                            </span>
                            <p className="text-sm text-gray-700 mt-0.5">{record.symptoms}</p>
                          </div>
                        )}
                        {record.diagnosis && (
                          <div>
                            <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                              Diagnosis
                            </span>
                            <p className="text-sm font-semibold text-gray-800 mt-0.5">
                              {record.diagnosis}
                            </p>
                          </div>
                        )}
                        {record.treatment && (
                          <div>
                            <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                              Treatment & Notes
                            </span>
                            <p className="text-sm text-gray-700 mt-0.5">{record.treatment}</p>
                          </div>
                        )}
                      </div>

                      {/* Prescription Details */}
                      {record.prescriptions && record.prescriptions.length > 0 && (
                        <div className="pt-3 border-t border-gray-100">
                          <span className="text-xs text-teal-600 block font-bold mb-2 uppercase tracking-wider">
                            Prescribed Medicines
                          </span>
                          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                            {record.prescriptions.map((med) => (
                              <div
                                key={med.id}
                                className="bg-slate-50 p-2.5 rounded-lg border border-gray-200 text-xs"
                              >
                                <span className="font-bold text-gray-800 block">
                                  {med.medicineName}
                                </span>
                                <span className="text-gray-500 block mt-0.5">
                                  {med.dosage} • {med.frequency} • {med.duration}
                                </span>
                                {med.instructions && (
                                  <span className="text-gray-400 block mt-0.5 italic">
                                    {med.instructions}
                                  </span>
                                )}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Actions */}
                      {record.opdId && (
                        <div className="flex justify-end gap-2 pt-2 border-t border-gray-100">
                          <button
                            onClick={() => handlePrintPrescription(record.opdId)}
                            disabled={printingPrescriptionId === record.opdId}
                            className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-indigo-700 hover:text-indigo-900 bg-indigo-50 hover:bg-indigo-100 rounded-md transition disabled:opacity-50"
                          >
                            <svg
                              className="w-3.5 h-3.5"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2.5}
                                d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                              />
                            </svg>
                            {printingPrescriptionId === record.opdId
                              ? 'Printing...'
                              : 'Prescription View'}
                          </button>
                          <button
                            onClick={() => handlePrintOpdMedicines(record.opdId)}
                            disabled={printingMedicinesId === record.opdId}
                            className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-emerald-700 hover:text-emerald-900 bg-emerald-50 hover:bg-emerald-100 rounded-md transition disabled:opacity-50"
                          >
                            <svg
                              className="w-3.5 h-3.5"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2.5}
                                d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
                              />
                            </svg>
                            {printingMedicinesId === record.opdId
                              ? 'Printing...'
                              : 'Medicines View'}
                          </button>
                          <button
                            onClick={() => handleDownloadCasePaper(record.opdId)}
                            disabled={downloadingCasePaperId === record.opdId}
                            className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-teal-700 hover:text-teal-900 bg-teal-50 hover:bg-teal-100 rounded-md transition disabled:opacity-50"
                          >
                            <svg
                              className="w-3.5 h-3.5"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2.5}
                                d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                              />
                            </svg>
                            {downloadingCasePaperId === record.opdId
                              ? 'Downloading...'
                              : 'Case Paper View'}
                          </button>
                        </div>
                      )}
                    </div>
                  ))
                ) : (
                  <div className="text-center py-12 text-gray-400 text-sm">
                    <p>No previous OPD case papers found.</p>
                  </div>
                )
              ) : ipdHistory.length > 0 ? (
                ipdHistory.map((admission) => {
                  // Compile all administered medicines/items for the card
                  const compiledAdministered = [];
                  const administeredMap = {};
                  if (admission.doctorEntries) {
                    admission.doctorEntries.forEach((entry) => {
                      if (entry.administeredItems) {
                        entry.administeredItems.forEach((med) => {
                          const name = med.medicineName;
                          const qty = Number(med.quantity || 0);
                          if (name) {
                            administeredMap[name] = (administeredMap[name] || 0) + qty;
                          }
                        });
                      }
                    });
                  }
                  Object.entries(administeredMap).forEach(([name, qty]) => {
                    compiledAdministered.push({ medicineName: name, quantity: qty });
                  });

                  const isExpanded = !!expandedAdmissionIds[admission.id];

                  return (
                    <div
                      key={admission.id}
                      className="bg-white rounded-lg p-5 border border-gray-200 shadow-sm space-y-4"
                    >
                      {/* IPD Header */}
                      <div className="flex justify-between items-start border-b border-gray-100 pb-2">
                        <div>
                          <span className="text-sm font-bold text-indigo-700 uppercase tracking-wide">
                            IPD Number: {admission.ipdNumber}
                          </span>
                          <span className="mx-2 text-gray-300">•</span>
                          <span className="text-xs text-gray-500">
                            Admitted:{' '}
                            {new Date(admission.admissionDatetime).toLocaleString('en-US', {
                              year: 'numeric',
                              month: 'short',
                              day: 'numeric',
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </span>
                          {admission.dischargeDatetime && (
                            <>
                              <span className="mx-2 text-gray-300">•</span>
                              <span className="text-xs text-gray-500">
                                Discharged:{' '}
                                {new Date(admission.dischargeDatetime).toLocaleString('en-US', {
                                  year: 'numeric',
                                  month: 'short',
                                  day: 'numeric',
                                  hour: '2-digit',
                                  minute: '2-digit',
                                })}
                              </span>
                            </>
                          )}
                        </div>
                        <span
                          className={`px-2 py-0.5 text-xs font-semibold rounded-full ${
                            admission.status === 'DISCHARGED'
                              ? 'bg-slate-100 text-slate-700'
                              : 'bg-emerald-50 text-emerald-700 animate-pulse'
                          }`}
                        >
                          {admission.status}
                        </span>
                      </div>

                      {/* Details Summary - Date & Primary Diagnosis always shown */}
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                          <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                            Primary Diagnosis
                          </span>
                          <p className="text-sm font-semibold text-gray-800 mt-0.5">
                            {admission.primaryDiagnosis || 'N/A'}
                          </p>
                        </div>
                        <div>
                          <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                            Date of Admission
                          </span>
                          <p className="text-sm text-gray-800 mt-0.5">
                            {new Date(admission.admissionDatetime).toLocaleDateString('en-US', {
                              year: 'numeric',
                              month: 'long',
                              day: 'numeric',
                            })}
                          </p>
                        </div>
                      </div>

                      {/* Combined list of in-clinic medicines given to the patient */}
                      <div>
                        <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider font-semibold text-indigo-700">
                          Medicines & In-Clinic Items Administered
                        </span>
                        {compiledAdministered.length > 0 ? (
                          <div className="flex flex-wrap gap-1.5 mt-1">
                            {compiledAdministered.map((med, idx) => (
                              <span
                                key={`comp-med-${idx}`}
                                className="inline-flex px-2.5 py-1 text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100 rounded-md"
                              >
                                {med.medicineName} x{med.quantity}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <p className="text-xs text-gray-500 italic mt-0.5">
                            No in-clinic items/medicines administered.
                          </p>
                        )}
                      </div>

                      {/* Collapsible details section */}
                      {isExpanded && (
                        <div className="mt-4 pt-4 border-t border-gray-200 space-y-4">
                          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div>
                              <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                                Admission Location
                              </span>
                              <p className="text-sm text-gray-700 mt-0.5">
                                Ward: <span className="font-semibold">{admission.currentWard}</span>{' '}
                                • Bed:{' '}
                                <span className="font-semibold text-teal-600">
                                  {admission.currentBed}
                                </span>
                              </p>
                            </div>
                            {admission.notes && (
                              <div>
                                <span className="text-xs text-gray-400 block font-medium uppercase tracking-wider">
                                  Admission Notes
                                </span>
                                <p className="text-sm text-gray-700 mt-0.5">{admission.notes}</p>
                              </div>
                            )}
                          </div>

                          {/* Bed history timeline */}
                          {admission.bedHistory && admission.bedHistory.length > 0 && (
                            <div className="pt-3 border-t border-gray-100">
                              <span className="text-xs text-amber-600 block font-bold mb-2 uppercase tracking-wider">
                                Bed Assignment History
                              </span>
                              <div className="bg-amber-50/40 p-3 rounded-lg border border-amber-100 space-y-2 text-xs text-gray-700">
                                {admission.bedHistory.map((historyItem, idx) => (
                                  <div
                                    key={`bed-hist-${idx}`}
                                    className="flex items-start gap-1.5 pl-3 border-l border-amber-200"
                                  >
                                    <div className="w-1.5 h-1.5 rounded-full bg-amber-400 mt-1"></div>
                                    <div>
                                      <span className="font-semibold text-gray-800">
                                        {historyItem.details}
                                      </span>
                                      <span className="text-[10px] text-gray-400 block mt-0.5">
                                        {new Date(historyItem.timestamp).toLocaleString()}
                                      </span>
                                    </div>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}

                          {/* Nested Doctor Follow-up entries */}
                          <div className="pt-3 border-t border-gray-100">
                            <span className="text-xs text-teal-600 block font-bold mb-2 uppercase tracking-wider">
                              Doctor Visit & Follow-up Logs
                            </span>
                            {admission.doctorEntries && admission.doctorEntries.length > 0 ? (
                              <div className="space-y-3 pl-3 border-l-2 border-slate-100">
                                {admission.doctorEntries.map((entry) => (
                                  <div
                                    key={entry.id}
                                    className="bg-slate-50/50 p-3.5 rounded-lg border border-slate-200 text-sm space-y-2.5"
                                  >
                                    <div className="flex justify-between items-center text-xs text-gray-500 border-b border-gray-200/50 pb-1">
                                      <span className="font-semibold text-gray-800">
                                        {entry.doctorName}
                                      </span>
                                      <span>{new Date(entry.date).toLocaleString()}</span>
                                    </div>
                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
                                      {entry.diagnosis && (
                                        <div>
                                          <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                            Visit Diagnosis
                                          </span>
                                          <span className="text-xs font-semibold text-gray-800">
                                            {entry.diagnosis}
                                          </span>
                                        </div>
                                      )}
                                      {entry.treatmentNotes && (
                                        <div>
                                          <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                            Notes / Observations
                                          </span>
                                          <span className="text-xs text-gray-700">
                                            {entry.treatmentNotes}
                                          </span>
                                        </div>
                                      )}
                                    </div>
                                    {entry.administeredItems &&
                                      entry.administeredItems.length > 0 && (
                                        <div className="pt-1.5 border-t border-gray-100/50 flex flex-wrap gap-1.5 items-center">
                                          <span className="text-[10px] text-indigo-500 font-bold uppercase tracking-wide mr-1">
                                            Administered:
                                          </span>
                                          {entry.administeredItems.map((med, idx) => (
                                            <span
                                              key={`admin-med-${idx}`}
                                              className="inline-flex px-2 py-0.5 text-[10px] font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100 rounded-md"
                                            >
                                              {med.medicineName} x{med.quantity}
                                              {med.dosage &&
                                                ` (${med.dosage}${med.frequency ? ` • ${med.frequency}` : ''}${med.duration ? ` • ${med.duration}` : ''})`}
                                            </span>
                                          ))}
                                        </div>
                                      )}
                                  </div>
                                ))}
                              </div>
                            ) : (
                              <p className="text-xs text-gray-400 italic pl-1">
                                No doctor visit entries logged during this admission.
                              </p>
                            )}
                          </div>

                          {/* Discharge Summary (Final Diagnosis) */}
                          {admission.dischargeSummary && (
                            <div className="pt-3 border-t border-gray-100">
                              <span className="text-xs text-purple-600 block font-bold mb-2 uppercase tracking-wider">
                                Final Diagnosis & Discharge Summary
                              </span>
                              <div className="bg-purple-50/40 p-3 rounded-lg border border-purple-100 space-y-2 text-xs">
                                {admission.dischargeSummary.finalDiagnosis && (
                                  <div>
                                    <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                      Final Diagnosis
                                    </span>
                                    <span className="text-sm font-semibold text-gray-900">
                                      {admission.dischargeSummary.finalDiagnosis}
                                    </span>
                                  </div>
                                )}
                                {admission.dischargeSummary.treatmentGiven && (
                                  <div>
                                    <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                      Treatment Given
                                    </span>
                                    <span className="text-gray-700">
                                      {admission.dischargeSummary.treatmentGiven}
                                    </span>
                                  </div>
                                )}
                                {admission.dischargeSummary.dischargeNotes && (
                                  <div>
                                    <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                      Discharge Notes
                                    </span>
                                    <span className="text-gray-700 italic">
                                      {admission.dischargeSummary.dischargeNotes}
                                    </span>
                                  </div>
                                )}
                                {admission.dischargeSummary.followUpDate && (
                                  <div>
                                    <span className="text-[10px] text-gray-400 uppercase tracking-wide block">
                                      Follow-up Date
                                    </span>
                                    <span className="font-semibold text-blue-700">
                                      {new Date(
                                        admission.dischargeSummary.followUpDate
                                      ).toLocaleDateString('en-US', {
                                        year: 'numeric',
                                        month: 'short',
                                        day: 'numeric',
                                      })}
                                    </span>
                                  </div>
                                )}
                              </div>
                            </div>
                          )}
                        </div>
                      )}

                      {/* Actions */}
                      <div className="flex justify-end gap-2 pt-2 border-t border-gray-100">
                        <button
                          onClick={() =>
                            setExpandedAdmissionIds((prev) => ({
                              ...prev,
                              [admission.id]: !prev[admission.id],
                            }))
                          }
                          className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-gray-700 hover:text-gray-905 bg-gray-100 hover:bg-gray-200 rounded-md transition"
                        >
                          {isExpanded ? 'Hide Details' : 'View Details'}
                        </button>
                        <button
                          onClick={() => handlePrintIpdPrescription(admission.id)}
                          disabled={printingIpdPrescriptionId === admission.id}
                          className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-indigo-700 hover:text-indigo-900 bg-indigo-50 hover:bg-indigo-100 rounded-md transition disabled:opacity-50"
                        >
                          <svg
                            className="w-3.5 h-3.5"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2.5}
                              d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                            />
                          </svg>
                          {printingIpdPrescriptionId === admission.id
                            ? 'Printing...'
                            : 'Prescription View'}
                        </button>
                        <button
                          onClick={() => handlePrintIpdMedicines(admission.id)}
                          disabled={printingIpdMedicinesId === admission.id}
                          className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-bold text-emerald-700 hover:text-emerald-900 bg-emerald-50 hover:bg-emerald-100 rounded-md transition disabled:opacity-50"
                        >
                          <svg
                            className="w-3.5 h-3.5"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2.5}
                              d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
                            />
                          </svg>
                          {printingIpdMedicinesId === admission.id
                            ? 'Printing...'
                            : 'Medicines View'}
                        </button>
                      </div>
                    </div>
                  );
                })
              ) : (
                <div className="text-center py-12 text-gray-400 text-sm">
                  <p>No previous IPD admission history found.</p>
                </div>
              )}
            </div>
          )}

          {activeTab === 'documents' && canSeeDocuments && (
            <PatientDocumentsPanel patientId={patient.id} />
          )}

          {activeTab === 'bills' && (
            <div className="space-y-4">
              {loadingBills ? (
                <div className="text-center py-12 text-gray-500">
                  <div className="animate-spin h-6 w-6 border-b-2 border-gray-900 mx-auto mb-2 rounded-full"></div>
                  Loading bills...
                </div>
              ) : bills.length > 0 ? (
                <div className="overflow-x-auto border border-gray-200 rounded-lg">
                  <table className="min-w-full text-sm text-left">
                    <thead className="bg-gray-50 text-gray-600 font-medium border-b border-gray-200">
                      <tr>
                        <th className="px-4 py-3">S.No.</th>
                        <th className="px-4 py-3">Bill No</th>
                        <th className="px-4 py-3">Date</th>
                        <th className="px-4 py-3">Status</th>
                        <th className="px-4 py-3">Payment</th>
                        <th className="px-4 py-3 text-right">Total</th>
                        <th className="px-4 py-3 text-right">Paid</th>
                        <th className="px-4 py-3 text-right">Balance</th>
                        <th className="px-4 py-3 text-center">Action</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 bg-white">
                      {bills.map((bill, index) => {
                        const total = bill.amount || 0;
                        const paid = bill.paidAmount || 0;
                        const bal = bill.balance ?? total - paid;
                        return (
                          <tr key={bill.id} className="hover:bg-gray-50">
                            <td className="px-4 py-3 text-gray-500">{index + 1}</td>
                            <td className="px-4 py-3 font-semibold text-gray-800">
                              {bill.customId || bill.id}
                            </td>
                            <td className="px-4 py-3 text-gray-500">
                              {new Date(bill.createdAt).toLocaleDateString()}
                            </td>
                            <td className="px-4 py-3">
                              <span
                                className={`px-2 py-0.5 text-xs font-semibold rounded-full ${
                                  bill.paymentStatus === 'PAID'
                                    ? 'bg-emerald-50 text-emerald-700'
                                    : bill.paymentStatus === 'PARTIAL'
                                      ? 'bg-amber-50 text-amber-700'
                                      : 'bg-red-50 text-red-700'
                                }`}
                              >
                                {bill.paymentStatus || 'PENDING'}
                              </span>
                            </td>
                            {/* How it was paid. The bill's own method wins; fall back to the
                                                            recorded payment's mode. Unpaid bills show a dash. */}
                            <td className="px-4 py-3">
                              {(() => {
                                const mode = bill.paymentMethod || bill.payments?.[0]?.mode;
                                if (!mode) return <span className="text-gray-400">—</span>;
                                const label =
                                  mode.toUpperCase() === 'UPI' ? 'Online / UPI' : 'Cash';
                                return (
                                  <span className="inline-flex flex-col">
                                    <span
                                      className={`px-2 py-0.5 text-xs font-semibold rounded-full w-fit ${
                                        mode.toUpperCase() === 'UPI'
                                          ? 'bg-sky-50 text-sky-700'
                                          : 'bg-gray-100 text-gray-700'
                                      }`}
                                    >
                                      {label}
                                    </span>
                                    {bill.paymentReference && (
                                      <span className="text-[10px] text-gray-400 mt-0.5">
                                        {bill.paymentReference}
                                      </span>
                                    )}
                                  </span>
                                );
                              })()}
                            </td>
                            <td className="px-4 py-3 text-right text-gray-800 font-medium">
                              ₹{total}
                            </td>
                            <td className="px-4 py-3 text-right text-emerald-600">₹{paid}</td>
                            <td
                              className={`px-4 py-3 text-right font-semibold ${bal > 0 ? 'text-red-600' : 'text-gray-500'}`}
                            >
                              ₹{bal}
                            </td>
                            <td className="px-4 py-3 text-center">
                              <button
                                onClick={() =>
                                  openDocument(
                                    `/hospital/billing/${bill.id}/pdf`,
                                    `Bill ${bill.customId || bill.id}`
                                  )
                                }
                                className="px-2.5 py-1 text-xs font-semibold text-indigo-600 hover:text-indigo-800 bg-indigo-50 hover:bg-indigo-100 rounded-md transition"
                              >
                                View
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="text-center py-12 text-gray-400 text-sm">
                  <p>No bills found for this patient.</p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 p-6 border-t border-gray-200 bg-gray-50">
          <button
            onClick={onClose}
            className="px-4 py-2 text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
          >
            Close
          </button>
        </div>
      </div>

      {viewerDoc && (
        <PdfViewerModal
          endpointPath={viewerDoc.endpointPath}
          title={viewerDoc.title}
          onClose={() => setViewerDoc(null)}
        />
      )}
    </div>
  );
};

// Helper component for displaying info fields
const InfoField = ({ label, value }) => (
  <div className="bg-white border border-gray-200 rounded-lg p-4">
    <span className="block text-xs font-medium text-gray-500 uppercase mb-1">{label}</span>
    <p className="text-sm font-medium text-gray-900">{value || 'N/A'}</p>
  </div>
);

export default PatientDetailsModal;
