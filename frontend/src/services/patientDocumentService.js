import apiClient from './apiService';

/**
 * Patient Documents — reports and paperwork brought in for a patient.
 *
 * Paths are written in the `/hospital/**` namespace and rewritten to the session's own tenant
 * namespace by the apiClient interceptor, exactly like every other shared service here. Nothing
 * in this file builds a URL to a stored file: content is fetched through the authenticated API
 * as a blob, because the server is what decides whether this session may read a document.
 */
const patientDocumentService = {
  listForPatient: async (patientId) => {
    const response = await apiClient.get(`/hospital/patients/${patientId}/documents`);
    return response.data;
  },

  upload: async (
    patientId,
    { file, documentType, title, reportDate, source, notes, opdId, ipdAdmissionId }
  ) => {
    const form = new FormData();
    form.append('file', file);
    form.append('documentType', documentType);
    form.append('title', title);
    if (reportDate) form.append('reportDate', reportDate);
    if (source) form.append('source', source);
    if (notes) form.append('notes', notes);
    if (opdId) form.append('opdId', opdId);
    if (ipdAdmissionId) form.append('ipdAdmissionId', ipdAdmissionId);

    const response = await apiClient.post(`/hospital/patients/${patientId}/documents`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    });
    return response.data;
  },

  /** The bytes, through the same authenticated client the rest of the app uses. */
  getContentBlob: async (documentPublicId) => {
    const response = await apiClient.get(
      `/hospital/patient-documents/${documentPublicId}/content`,
      { responseType: 'blob', timeout: 120000 }
    );
    return response.data;
  },

  archive: async (documentPublicId, reason) => {
    const response = await apiClient.post(
      `/hospital/patient-documents/${documentPublicId}/archive`,
      { reason }
    );
    return response.data;
  },
};

export default patientDocumentService;
