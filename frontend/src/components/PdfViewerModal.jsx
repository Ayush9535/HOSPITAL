import React, { useState, useEffect } from 'react';
import apiClient from '../services/apiService';
import { backdropProps } from '../utils/modalA11y';
import { printBlob } from '../utils/printPdf';

/**
 * PdfViewerModal — fetches a server PDF (through apiClient, so the JWT rides the
 * Authorization header and never lands in a URL) and shows it inline so the user can
 * *read* the document first, then choose to Print or Download.
 *
 * Replaces the old "click = immediate print dialog" behaviour, where viewing a bill or
 * case paper printed it straight away with no chance to just look at it.
 *
 * @param {string} endpointPath API path returning a PDF, e.g. `/hospital/billing/12/pdf`
 * @param {string} title heading shown in the toolbar + used for the download filename
 * @param {() => void} onClose close handler
 */
const PdfViewerModal = ({ endpointPath, title = 'Document', onClose }) => {
  const [blobUrl, setBlobUrl] = useState(null);
  const [blob, setBlob] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let url = null;
    setLoading(true);
    setError(false);
    apiClient
      .get(endpointPath, { responseType: 'blob' })
      .then((resp) => {
        if (cancelled) return;
        const b = new Blob([resp.data], { type: 'application/pdf' });
        url = URL.createObjectURL(b);
        setBlob(b);
        setBlobUrl(url);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [endpointPath]);

  const handlePrint = () => {
    if (blob) printBlob(blob);
  };

  const handleDownload = () => {
    if (!blobUrl) return;
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = `${(title || 'document').replace(/\s+/g, '_')}.pdf`;
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 z-[60] flex items-center justify-center p-2 sm:p-4"
      {...backdropProps(onClose)}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="bg-white rounded-xl shadow-xl w-full max-w-4xl h-[92vh] flex flex-col overflow-hidden"
      >
        {/* Toolbar */}
        <div className="flex justify-between items-center gap-3 p-3 sm:p-4 border-b border-gray-200">
          <h3 className="text-base sm:text-lg font-bold text-gray-900 truncate">{title}</h3>
          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={handlePrint}
              disabled={!blob}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-semibold text-white bg-gray-900 rounded-md hover:bg-gray-800 disabled:opacity-50"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                />
              </svg>
              Print
            </button>
            <button
              type="button"
              onClick={handleDownload}
              disabled={!blobUrl}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200 disabled:opacity-50"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              Download
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close document viewer"
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

        {/* Document body */}
        <div className="flex-1 bg-gray-100 overflow-hidden">
          {loading && (
            <div className="h-full flex flex-col items-center justify-center text-gray-500">
              <div className="animate-spin h-6 w-6 border-b-2 border-gray-900 mb-2 rounded-full"></div>
              Loading document…
            </div>
          )}
          {error && (
            <div className="h-full flex items-center justify-center text-red-600 text-sm px-4 text-center">
              Failed to load the document. Please close and try again.
            </div>
          )}
          {blobUrl && !error && (
            <iframe title={title} src={blobUrl} className="w-full h-full border-0" />
          )}
        </div>
      </div>
    </div>
  );
};

export default PdfViewerModal;
