import React, { useCallback, useEffect, useRef, useState } from 'react';
import ConfirmationModal from './ConfirmationModal';
import PatientDocumentPreviewModal from './PatientDocumentPreviewModal';
import authService from '../services/authService';
import patientDocumentService from '../services/patientDocumentService';
import { extractApiError, safeLoadMessage } from '../utils/apiError';
import { formatDate, formatDateTime } from '../utils/date';
import {
  DOCUMENT_TYPES,
  documentTypeLabel,
  formatFileSize,
  validateDocumentFile,
} from '../utils/patientDocuments';

/**
 * Everything on file for one patient: reports they brought in, scans, insurance paperwork.
 *
 * One component for every surface that shows a patient, because "what has this patient got on
 * file" is the same question at the desk, in the consulting room and at the bedside. Who may do
 * what comes from the role, and the server enforces the same rules again — reception files
 * paperwork but cannot archive it, nurses read but do not file, and a pharmacy session has no
 * patient record to attach anything to and never renders this at all.
 */
const UPLOAD_ROLES = ['HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST'];
const ARCHIVE_ROLES = ['HOSPITAL_ADMIN', 'DOCTOR'];
const VIEW_ROLES = [...UPLOAD_ROLES, 'NURSE', 'NURSE_INCHARGE'];

const PatientDocumentsPanel = ({ patientId, opdId, ipdAdmissionId, readOnly = false }) => {
  const role = authService.getCurrentUser()?.role;
  const canView = VIEW_ROLES.includes(role);
  const canUpload = !readOnly && UPLOAD_ROLES.includes(role);
  const canArchive = !readOnly && ARCHIVE_ROLES.includes(role);

  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [loaded, setLoaded] = useState(false);

  const [showForm, setShowForm] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const [file, setFile] = useState(null);
  const [form, setForm] = useState({
    documentType: 'PATHOLOGY_REPORT',
    title: '',
    reportDate: '',
    source: '',
    notes: '',
  });

  const [previewDoc, setPreviewDoc] = useState(null);
  const [archiveTarget, setArchiveTarget] = useState(null);

  const fileInputRef = useRef(null);
  const cameraInputRef = useRef(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await patientDocumentService.listForPatient(patientId);
      setDocuments(Array.isArray(data) ? data : []);
      setLoadError('');
      setLoaded(true);
    } catch (err) {
      // A failed read is not an empty file. Whatever was already on screen stays there, and the
      // banner says plainly that this may no longer be the whole picture.
      setLoadError(safeLoadMessage(err, "Couldn't load documents for this patient."));
    } finally {
      setLoading(false);
    }
  }, [patientId]);

  useEffect(() => {
    if (patientId && canView) load();
  }, [patientId, canView, load]);

  if (!canView) return null;

  const resetForm = () => {
    setFile(null);
    setForm({ documentType: 'PATHOLOGY_REPORT', title: '', reportDate: '', source: '', notes: '' });
    setUploadError('');
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (cameraInputRef.current) cameraInputRef.current.value = '';
  };

  const handleFileChosen = (event) => {
    const chosen = event.target.files?.[0] || null;
    const problem = chosen ? validateDocumentFile(chosen) : null;
    if (problem) {
      setFile(null);
      setUploadError(problem);
      event.target.value = '';
      return;
    }
    setUploadError('');
    setFile(chosen);
    if (chosen && !form.title) {
      setForm((f) => ({ ...f, title: chosen.name.replace(/\.[^.]+$/, '') }));
    }
  };

  const handleUpload = async (event) => {
    event.preventDefault();
    const problem = validateDocumentFile(file);
    if (problem) {
      setUploadError(problem);
      return;
    }
    if (!form.title.trim()) {
      setUploadError('Give the document a title.');
      return;
    }

    setUploading(true);
    setUploadError('');
    try {
      await patientDocumentService.upload(patientId, {
        file,
        documentType: form.documentType,
        title: form.title.trim(),
        reportDate: form.reportDate || undefined,
        source: form.source.trim() || undefined,
        notes: form.notes.trim() || undefined,
        opdId,
        ipdAdmissionId,
      });
      resetForm();
      setShowForm(false);
      await load();
    } catch (err) {
      setUploadError(extractApiError(err, "The document couldn't be uploaded. Please try again."));
    } finally {
      setUploading(false);
    }
  };

  const handleArchive = async (reason) => {
    const { publicId } = archiveTarget;
    await patientDocumentService.archive(publicId, reason);
    // Dropped locally before the refresh, not by it. If the reload then fails, the panel keeps
    // showing the rows it already had -- and this document must not be among them: the server
    // has archived it, and a row still offering View and Archive would be describing a state
    // that no longer exists.
    setDocuments((docs) => docs.filter((doc) => doc.publicId !== publicId));
    await load();
  };

  const showEmpty = loaded && !loading && !loadError && documents.length === 0;
  const stale = loadError && documents.length > 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="text-base font-bold text-gray-900">Documents</h3>
          <p className="text-xs text-gray-500">Reports and paperwork on file for this patient.</p>
        </div>
        {canUpload && (
          <button
            type="button"
            onClick={() => {
              setShowForm((open) => !open);
              setUploadError('');
            }}
            className="px-3 py-1.5 text-sm font-semibold text-white bg-gray-900 rounded-md hover:bg-gray-800"
          >
            {showForm ? 'Cancel' : 'Add document'}
          </button>
        )}
      </div>

      {canUpload && showForm && (
        <form onSubmit={handleUpload} className="border border-gray-200 rounded-lg p-4 space-y-3">
          <div className="flex flex-wrap gap-2">
            <label className="px-3 py-1.5 text-sm font-semibold text-gray-700 bg-gray-100 rounded-md cursor-pointer hover:bg-gray-200">
              Choose file
              <input
                ref={fileInputRef}
                type="file"
                accept="application/pdf,image/jpeg,image/png,image/webp"
                onChange={handleFileChosen}
                className="sr-only"
                aria-label="Choose a PDF or image file"
              />
            </label>
            <label className="px-3 py-1.5 text-sm font-semibold text-gray-700 bg-gray-100 rounded-md cursor-pointer hover:bg-gray-200">
              Take photo
              <input
                ref={cameraInputRef}
                type="file"
                accept="image/*"
                capture="environment"
                onChange={handleFileChosen}
                className="sr-only"
                aria-label="Take a photo of the document"
              />
            </label>
            {file && (
              <span className="text-sm text-gray-600 self-center">
                {file.name} ({formatFileSize(file.size)})
              </span>
            )}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="doc-type" className="block text-xs font-semibold text-gray-600 mb-1">
                Type
              </label>
              <select
                id="doc-type"
                value={form.documentType}
                onChange={(e) => setForm({ ...form, documentType: e.target.value })}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              >
                {DOCUMENT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="doc-title" className="block text-xs font-semibold text-gray-600 mb-1">
                Title
              </label>
              <input
                id="doc-title"
                type="text"
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="doc-date" className="block text-xs font-semibold text-gray-600 mb-1">
                Report date
              </label>
              <input
                id="doc-date"
                type="date"
                value={form.reportDate}
                onChange={(e) => setForm({ ...form, reportDate: e.target.value })}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label
                htmlFor="doc-source"
                className="block text-xs font-semibold text-gray-600 mb-1"
              >
                Source
              </label>
              <input
                id="doc-source"
                type="text"
                placeholder="Lab or hospital it came from"
                value={form.source}
                onChange={(e) => setForm({ ...form, source: e.target.value })}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              />
            </div>
          </div>

          <div>
            <label htmlFor="doc-notes" className="block text-xs font-semibold text-gray-600 mb-1">
              Notes
            </label>
            <textarea
              id="doc-notes"
              rows={2}
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
            />
          </div>

          {uploadError && (
            <p role="alert" className="text-sm text-red-700">
              {uploadError}
            </p>
          )}

          <div className="flex justify-end">
            <button
              type="submit"
              disabled={uploading}
              className="px-4 py-2 text-sm font-semibold text-white bg-gray-900 rounded-md hover:bg-gray-800 disabled:opacity-50"
            >
              {uploading ? 'Uploading…' : 'Upload'}
            </button>
          </div>
        </form>
      )}

      {loading && documents.length === 0 && (
        <p role="status" className="text-sm text-gray-500 py-6 text-center">
          Loading documents…
        </p>
      )}

      {loadError && (
        <div
          role="alert"
          className="border border-red-200 bg-red-50 rounded-lg p-3 text-sm text-red-800"
        >
          <p className="font-semibold">
            {stale
              ? "Couldn't refresh documents — showing what was last loaded."
              : "Couldn't load documents"}
          </p>
          <p className="mt-0.5">{loadError}</p>
          <button
            type="button"
            onClick={load}
            className="mt-2 px-3 py-1 text-xs font-semibold text-red-800 border border-red-300 rounded-md hover:bg-red-100"
          >
            Retry
          </button>
        </div>
      )}

      {showEmpty && (
        <p className="text-sm text-gray-500 py-6 text-center">
          No documents on file for this patient yet.
        </p>
      )}

      {documents.length > 0 && (
        <ul className="divide-y divide-gray-100 border border-gray-200 rounded-lg">
          {documents.map((doc) => (
            <li key={doc.publicId} className="p-3 flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm font-semibold text-gray-900 truncate">{doc.title}</p>
                <p className="text-xs text-gray-500 mt-0.5">
                  {documentTypeLabel(doc.documentType)}
                  {doc.reportDate ? ` · Report date ${formatDate(doc.reportDate)}` : ''}
                  {doc.source ? ` · ${doc.source}` : ''}
                </p>
                <p className="text-xs text-gray-400 mt-0.5">
                  Uploaded {formatDateTime(doc.createdAt)}
                  {doc.uploadedBy ? ` by ${doc.uploadedBy}` : ''}
                  {doc.fileSizeBytes ? ` · ${formatFileSize(doc.fileSizeBytes)}` : ''}
                </p>
                {doc.notes && <p className="text-xs text-gray-600 mt-1">{doc.notes}</p>}
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <button
                  type="button"
                  onClick={() => setPreviewDoc(doc)}
                  className="px-3 py-1.5 text-sm font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200"
                >
                  View
                </button>
                {canArchive && (
                  <button
                    type="button"
                    onClick={() => setArchiveTarget(doc)}
                    className="px-3 py-1.5 text-sm font-semibold text-red-700 bg-red-50 rounded-md hover:bg-red-100"
                  >
                    Archive
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {previewDoc && (
        <PatientDocumentPreviewModal document={previewDoc} onClose={() => setPreviewDoc(null)} />
      )}

      <ConfirmationModal
        isOpen={!!archiveTarget}
        title="Archive this document?"
        message={`"${archiveTarget?.title || ''}" will no longer appear on the patient's file. The record is kept.`}
        showReasonInput
        inputPlaceholder="Why is this being archived?"
        onConfirm={handleArchive}
        onCancel={() => setArchiveTarget(null)}
      />
    </div>
  );
};

export default PatientDocumentsPanel;
