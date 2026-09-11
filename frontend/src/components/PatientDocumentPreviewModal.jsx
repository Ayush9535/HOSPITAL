import React, { useEffect, useState } from 'react';
import patientDocumentService from '../services/patientDocumentService';
import { isPreviewableImage } from '../utils/patientDocuments';
import { safeLoadMessage } from '../utils/apiError';

/**
 * Shows one document, fetched the only way it can be fetched: through the authenticated API.
 *
 * There is no URL for a patient's report. The bytes arrive as a blob and become a temporary
 * object URL that lives exactly as long as this modal does — revoked on close, on a document
 * change, and on unmount, so a closed report is not still addressable in the tab.
 */
const PatientDocumentPreviewModal = ({ document: doc, onClose }) => {
  const [objectUrl, setObjectUrl] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    let url = null;
    setLoading(true);
    setError('');
    setObjectUrl(null);

    patientDocumentService
      .getContentBlob(doc.publicId)
      .then((blob) => {
        if (cancelled) return;
        url = URL.createObjectURL(new Blob([blob], { type: doc.mimeType || blob.type }));
        setObjectUrl(url);
      })
      .catch((err) => {
        if (!cancelled) setError(safeLoadMessage(err, "This document couldn't be opened."));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [doc.publicId, doc.mimeType]);

  const handleDownload = () => {
    if (!objectUrl) return;
    const link = window.document.createElement('a');
    link.href = objectUrl;
    link.download = doc.originalFileName || `${(doc.title || 'document').replace(/\s+/g, '_')}`;
    window.document.body.appendChild(link);
    link.click();
    link.remove();
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 z-[60] flex items-center justify-center p-2 sm:p-4"
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
        aria-label={doc.title || 'Document'}
        className="bg-white rounded-xl shadow-xl w-full max-w-4xl h-[92vh] flex flex-col overflow-hidden"
      >
        <div className="flex justify-between items-center gap-3 p-3 sm:p-4 border-b border-gray-200">
          <h3 className="text-base sm:text-lg font-bold text-gray-900 truncate">
            {doc.title || 'Document'}
          </h3>
          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={handleDownload}
              disabled={!objectUrl}
              className="px-3 py-1.5 text-sm font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200 disabled:opacity-50"
            >
              Download
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close document preview"
              className="text-gray-400 hover:text-gray-600 p-1.5 rounded-lg focus:outline-none focus:ring-2 focus:ring-gray-900"
            >
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
        </div>

        <div className="flex-1 bg-gray-100 overflow-auto flex items-center justify-center">
          {loading && (
            <div className="flex flex-col items-center text-gray-500" role="status">
              <div className="animate-spin h-6 w-6 border-b-2 border-gray-900 mb-2 rounded-full"></div>
              Loading document…
            </div>
          )}
          {!loading && error && (
            <p role="alert" className="text-sm text-red-700 px-4 text-center">
              {error}
            </p>
          )}
          {!loading &&
            !error &&
            objectUrl &&
            (isPreviewableImage(doc.mimeType) ? (
              <img
                src={objectUrl}
                alt={doc.title || 'Document'}
                className="max-w-full max-h-full object-contain"
              />
            ) : (
              <iframe
                title={doc.title || 'Document'}
                src={objectUrl}
                className="w-full h-full border-0"
              />
            ))}
        </div>
      </div>
    </div>
  );
};

export default PatientDocumentPreviewModal;
