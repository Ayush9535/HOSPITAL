import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/patientDocumentService', () => ({
  default: {
    listForPatient: vi.fn(),
    upload: vi.fn(),
    getContentBlob: vi.fn(),
    archive: vi.fn(),
  },
}));

vi.mock('../services/authService', () => ({
  default: { getCurrentUser: vi.fn() },
}));

import authService from '../services/authService';
import patientDocumentService from '../services/patientDocumentService';
import PatientDocumentsPanel from './PatientDocumentsPanel';

const asRole = (role) => authService.getCurrentUser.mockReturnValue({ role });

const aDocument = (overrides = {}) => ({
  publicId: 'doc-1',
  documentType: 'PATHOLOGY_REPORT',
  title: 'CBC report',
  reportDate: '2026-08-01',
  source: 'City Lab',
  notes: 'Brought in by the patient',
  originalFileName: 'cbc.pdf',
  mimeType: 'application/pdf',
  fileSizeBytes: 20480,
  uploadedBy: 'Dr. Rao',
  createdAt: '2026-08-02T10:15:00',
  ...overrides,
});

const pdf = (name = 'report.pdf', size = 1024) => {
  const file = new File(['x'], name, { type: 'application/pdf' });
  Object.defineProperty(file, 'size', { value: size });
  return file;
};

describe('PatientDocumentsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asRole('DOCTOR');
    patientDocumentService.listForPatient.mockResolvedValue([]);
  });

  // -- list ------------------------------------------------------------------

  it('lists what is on file, and nothing about where it is stored', async () => {
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    const { container } = render(<PatientDocumentsPanel patientId={7} />);

    expect(await screen.findByText('CBC report')).toBeInTheDocument();
    expect(screen.getByText(/Pathology Report/)).toBeInTheDocument();
    expect(screen.getByText(/City Lab/)).toBeInTheDocument();
    expect(screen.getByText(/Dr\. Rao/)).toBeInTheDocument();
    expect(screen.getByText('Brought in by the patient')).toBeInTheDocument();
    expect(container.innerHTML).not.toMatch(
      /storageKey|\/var\/lib|patient-documents\/doc-1\/content/
    );
  });

  it('shows a loading state before anything has arrived', async () => {
    let resolve;
    patientDocumentService.listForPatient.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      })
    );
    render(<PatientDocumentsPanel patientId={7} />);

    expect(screen.getByRole('status')).toHaveTextContent('Loading documents');
    resolve([]);
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
  });

  it('says the file is empty only when the server actually said so', async () => {
    render(<PatientDocumentsPanel patientId={7} />);
    expect(await screen.findByText(/No documents on file/)).toBeInTheDocument();
  });

  it('does not turn a failed load into an empty file', async () => {
    patientDocumentService.listForPatient.mockRejectedValue({
      response: { data: { error: 'Document service unavailable' } },
    });
    render(<PatientDocumentsPanel patientId={7} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load documents");
    expect(alert).toHaveTextContent('Document service unavailable');
    expect(screen.queryByText(/No documents on file/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('never shows a raw server exception in the banner', async () => {
    patientDocumentService.listForPatient.mockRejectedValue(
      new Error('java.lang.NullPointerException at com.hms.service.PatientDocumentService')
    );
    render(<PatientDocumentsPanel patientId={7} />);

    const alert = await screen.findByRole('alert');
    expect(alert).not.toHaveTextContent('NullPointerException');
  });

  it('keeps the rows it already had when a refresh fails, minus the one just archived', async () => {
    const user = userEvent.setup();
    patientDocumentService.listForPatient
      .mockResolvedValueOnce([aDocument(), aDocument({ publicId: 'doc-2', title: 'Chest X-ray' })])
      .mockRejectedValueOnce({ response: { data: { error: 'Upstream timeout' } } });
    patientDocumentService.archive.mockResolvedValue({});
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    await user.click(screen.getAllByRole('button', { name: 'Archive' })[0]);
    await user.type(await screen.findByLabelText(/Reason/), 'Filed twice');
    await user.click(await screen.findByRole('button', { name: 'Confirm action' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/showing what was last loaded/);
    expect(screen.getByText('Chest X-ray')).toBeInTheDocument();
    // The server archived it. A failed reload is no reason to keep offering it.
    await waitFor(() => expect(screen.queryByText('CBC report')).not.toBeInTheDocument());
    expect(screen.getAllByRole('button', { name: 'Archive' })).toHaveLength(1);
  });

  // -- upload ----------------------------------------------------------------

  it('uploads a chosen file with its metadata and refreshes the list', async () => {
    const user = userEvent.setup();
    patientDocumentService.upload.mockResolvedValue({});
    render(<PatientDocumentsPanel patientId={7} opdId={31} />);
    await screen.findByText(/No documents on file/);

    await user.click(screen.getByRole('button', { name: 'Add document' }));
    await user.upload(screen.getByLabelText('Choose a PDF or image file'), pdf());
    await user.clear(screen.getByLabelText('Title'));
    await user.type(screen.getByLabelText('Title'), 'Blood work');
    await user.selectOptions(screen.getByLabelText('Type'), 'RADIOLOGY_REPORT');
    await user.type(screen.getByLabelText('Source'), 'City Lab');
    await user.click(screen.getByRole('button', { name: 'Upload' }));

    await waitFor(() => expect(patientDocumentService.upload).toHaveBeenCalledTimes(1));
    const [patientId, payload] = patientDocumentService.upload.mock.calls[0];
    expect(patientId).toBe(7);
    expect(payload).toMatchObject({
      documentType: 'RADIOLOGY_REPORT',
      title: 'Blood work',
      source: 'City Lab',
      opdId: 31,
    });
    expect(patientDocumentService.listForPatient).toHaveBeenCalledTimes(2);
  });

  it('refuses a file over 5 MB without asking the server', async () => {
    const user = userEvent.setup();
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText(/No documents on file/);

    await user.click(screen.getByRole('button', { name: 'Add document' }));
    await user.upload(
      screen.getByLabelText('Choose a PDF or image file'),
      pdf('big.pdf', 6 * 1024 * 1024)
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('The limit is 5 MB');
    expect(patientDocumentService.upload).not.toHaveBeenCalled();
  });

  it('refuses a file type the server would not accept', async () => {
    const user = userEvent.setup();
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText(/No documents on file/);

    await user.click(screen.getByRole('button', { name: 'Add document' }));
    // Fired directly rather than through userEvent: `accept` is a hint a file manager may
    // ignore, so the check that matters is the one this component makes.
    fireEvent.change(screen.getByLabelText('Choose a PDF or image file'), {
      target: { files: [new File(['x'], 'notes.txt', { type: 'text/plain' })] },
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('Only PDF, JPEG, PNG or WebP');
    expect(patientDocumentService.upload).not.toHaveBeenCalled();
  });

  it('offers the phone camera as its own input', async () => {
    const user = userEvent.setup();
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText(/No documents on file/);
    await user.click(screen.getByRole('button', { name: 'Add document' }));

    const camera = screen.getByLabelText('Take a photo of the document');
    expect(camera).toHaveAttribute('accept', 'image/*');
    expect(camera).toHaveAttribute('capture', 'environment');

    const chooser = screen.getByLabelText('Choose a PDF or image file');
    expect(chooser).toHaveAttribute('accept', 'application/pdf,image/jpeg,image/png,image/webp');
    expect(chooser).not.toHaveAttribute('capture');
  });

  it('shows an upload failure instead of pretending it worked', async () => {
    const user = userEvent.setup();
    patientDocumentService.upload.mockRejectedValue({
      response: { data: { error: 'That file is not a PDF' } },
    });
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText(/No documents on file/);

    await user.click(screen.getByRole('button', { name: 'Add document' }));
    await user.upload(screen.getByLabelText('Choose a PDF or image file'), pdf());
    await user.click(screen.getByRole('button', { name: 'Upload' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That file is not a PDF');
    expect(patientDocumentService.listForPatient).toHaveBeenCalledTimes(1);
  });

  // -- roles -----------------------------------------------------------------

  it('gives a nurse the documents but no way to change them', async () => {
    asRole('NURSE');
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    render(<PatientDocumentsPanel patientId={7} readOnly />);
    await screen.findByText('CBC report');

    expect(screen.queryByRole('button', { name: 'Add document' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
  });

  it('lets reception file paperwork but not archive it', async () => {
    asRole('RECEPTIONIST');
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    expect(screen.getByRole('button', { name: 'Add document' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument();
  });

  it.each(['HOSPITAL_ADMIN', 'DOCTOR'])('lets a %s archive', async (role) => {
    asRole(role);
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    expect(screen.getByRole('button', { name: 'Archive' })).toBeInTheDocument();
  });

  it('renders nothing at all for a role with no business here', async () => {
    asRole('PHARMACIST');
    const { container } = render(<PatientDocumentsPanel patientId={7} />);

    expect(container).toBeEmptyDOMElement();
    expect(patientDocumentService.listForPatient).not.toHaveBeenCalled();
  });

  // -- archive ---------------------------------------------------------------

  it('will not archive without a reason', async () => {
    const user = userEvent.setup();
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    await user.click(screen.getByRole('button', { name: 'Archive' }));
    const confirm = await screen.findByRole('button', { name: 'Confirm action' });
    expect(confirm).toBeDisabled();

    await user.click(confirm);
    expect(patientDocumentService.archive).not.toHaveBeenCalled();
  });

  it('archives with the reason and refreshes so the document is gone', async () => {
    const user = userEvent.setup();
    patientDocumentService.listForPatient
      .mockResolvedValueOnce([aDocument()])
      .mockResolvedValueOnce([]);
    patientDocumentService.archive.mockResolvedValue({});
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    await user.click(screen.getByRole('button', { name: 'Archive' }));
    await user.type(await screen.findByLabelText(/Reason/), 'Wrong patient');
    await user.click(await screen.findByRole('button', { name: 'Confirm action' }));

    await waitFor(() =>
      expect(patientDocumentService.archive).toHaveBeenCalledWith('doc-1', 'Wrong patient')
    );
    expect(await screen.findByText(/No documents on file/)).toBeInTheDocument();
  });

  it('keeps the document when archiving fails', async () => {
    const user = userEvent.setup();
    patientDocumentService.listForPatient.mockResolvedValue([aDocument()]);
    patientDocumentService.archive.mockRejectedValue({
      response: { data: { error: 'Only a doctor may archive' } },
    });
    render(<PatientDocumentsPanel patientId={7} />);
    await screen.findByText('CBC report');

    await user.click(screen.getByRole('button', { name: 'Archive' }));
    await user.type(await screen.findByLabelText(/Reason/), 'Filed in error');
    await user.click(await screen.findByRole('button', { name: 'Confirm action' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Only a doctor may archive');
    expect(screen.getByText('CBC report')).toBeInTheDocument();
  });
});
