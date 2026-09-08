import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/patientDocumentService', () => ({
  default: { getContentBlob: vi.fn() },
}));

import patientDocumentService from '../services/patientDocumentService';
import PatientDocumentPreviewModal from './PatientDocumentPreviewModal';

/**
 * A patient's report has no address. It is fetched through the authenticated client and shown
 * from a temporary object URL that must not outlive the modal — a URL still alive after close is
 * a clinical document left addressable in the tab.
 */
describe('PatientDocumentPreviewModal', () => {
  const createObjectURL = vi.fn(() => 'blob:doc-url');
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const imageDoc = { publicId: 'doc-9', title: 'X-ray', mimeType: 'image/jpeg' };
  const pdfDoc = { publicId: 'doc-8', title: 'Discharge summary', mimeType: 'application/pdf' };

  it('fetches the bytes through the authenticated API, never a URL of its own', async () => {
    patientDocumentService.getContentBlob.mockResolvedValue(new Blob(['bytes']));
    render(<PatientDocumentPreviewModal document={imageDoc} onClose={vi.fn()} />);

    const image = await screen.findByAltText('X-ray');
    expect(patientDocumentService.getContentBlob).toHaveBeenCalledWith('doc-9');
    expect(image).toHaveAttribute('src', 'blob:doc-url');
  });

  it('shows a PDF in a frame rather than as an image', async () => {
    patientDocumentService.getContentBlob.mockResolvedValue(new Blob(['bytes']));
    render(<PatientDocumentPreviewModal document={pdfDoc} onClose={vi.fn()} />);

    const frame = await screen.findByTitle('Discharge summary');
    expect(frame.tagName).toBe('IFRAME');
    expect(frame).toHaveAttribute('src', 'blob:doc-url');
  });

  it('revokes the object URL when it closes', async () => {
    patientDocumentService.getContentBlob.mockResolvedValue(new Blob(['bytes']));
    const { unmount } = render(
      <PatientDocumentPreviewModal document={imageDoc} onClose={vi.fn()} />
    );
    await screen.findByAltText('X-ray');

    unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:doc-url');
  });

  it('reports a preview failure instead of showing an empty frame', async () => {
    patientDocumentService.getContentBlob.mockRejectedValue({
      response: { data: { error: 'Document not found' } },
    });
    render(<PatientDocumentPreviewModal document={imageDoc} onClose={vi.fn()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Document not found');
    expect(screen.queryByAltText('X-ray')).not.toBeInTheDocument();
  });

  it('closes on the close button', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    patientDocumentService.getContentBlob.mockResolvedValue(new Blob(['bytes']));
    render(<PatientDocumentPreviewModal document={imageDoc} onClose={onClose} />);
    await screen.findByAltText('X-ray');

    await user.click(screen.getByRole('button', { name: 'Close document preview' }));
    expect(onClose).toHaveBeenCalled();
  });
});
