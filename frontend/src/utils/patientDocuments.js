/**
 * What the document API accepts, kept where both the form and its tests can see it.
 *
 * The server validates all of this again — it checks the actual file signature, not the name or
 * the type the browser guessed. These are here so somebody on a hospital's phone connection
 * finds out before uploading five megabytes, not after.
 */
export const DOCUMENT_TYPES = [
  { value: 'PATHOLOGY_REPORT', label: 'Pathology Report' },
  { value: 'RADIOLOGY_REPORT', label: 'Radiology Report' },
  { value: 'PRESCRIPTION', label: 'Prescription' },
  { value: 'DISCHARGE_SUMMARY', label: 'Discharge Summary' },
  { value: 'REFERRAL', label: 'Referral' },
  { value: 'INSURANCE_DOCUMENT', label: 'Insurance Document' },
  { value: 'OTHER', label: 'Other' },
];

export const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

const ACCEPTED_MIME_TYPES = ['application/pdf', 'image/jpeg', 'image/png', 'image/webp'];
const ACCEPTED_EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png', 'webp'];

export const documentTypeLabel = (value) =>
  DOCUMENT_TYPES.find((t) => t.value === value)?.label || value || '—';

export const isPreviewableImage = (mimeType) => (mimeType || '').startsWith('image/');

export const formatFileSize = (bytes) => {
  if (!bytes && bytes !== 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/**
 * @returns {string|null} why the file cannot be uploaded, or null if it can be
 */
export const validateDocumentFile = (file) => {
  if (!file) return 'Choose a file or take a photo first.';
  if (file.size > MAX_DOCUMENT_BYTES) {
    return `That file is ${formatFileSize(file.size)}. The limit is 5 MB.`;
  }
  if (file.size === 0) return 'That file is empty.';

  const type = (file.type || '').toLowerCase();
  if (type && ACCEPTED_MIME_TYPES.includes(type)) return null;

  // A camera roll or a file manager sometimes hands over a file with no type at all; fall back
  // to the name rather than refusing something the server would have accepted.
  const extension = (file.name || '').split('.').pop()?.toLowerCase();
  if (!type && extension && ACCEPTED_EXTENSIONS.includes(extension)) return null;

  return 'Only PDF, JPEG, PNG or WebP files can be uploaded.';
};
