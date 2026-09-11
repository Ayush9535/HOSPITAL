import { describe, expect, it } from 'vitest';
import { formatFileSize, validateDocumentFile, MAX_DOCUMENT_BYTES } from './patientDocuments';

const fileOf = (name, type, size) => {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
};

describe('validateDocumentFile', () => {
  it('accepts what the server accepts', () => {
    for (const type of ['application/pdf', 'image/jpeg', 'image/png', 'image/webp']) {
      expect(validateDocumentFile(fileOf('report', type, 1024))).toBeNull();
    }
  });

  it('refuses anything else', () => {
    expect(validateDocumentFile(fileOf('notes.txt', 'text/plain', 10))).toMatch(
      /PDF, JPEG, PNG or WebP/
    );
  });

  it('refuses a file over the 5 MB limit', () => {
    expect(
      validateDocumentFile(fileOf('scan.pdf', 'application/pdf', MAX_DOCUMENT_BYTES + 1))
    ).toMatch(/limit is 5 MB/);
  });

  it('refuses an empty file', () => {
    expect(validateDocumentFile(fileOf('scan.pdf', 'application/pdf', 0))).toMatch(/empty/);
  });

  it('falls back to the extension when a camera hands over no type', () => {
    expect(validateDocumentFile(fileOf('IMG_0042.jpg', '', 2048))).toBeNull();
    expect(validateDocumentFile(fileOf('archive.zip', '', 2048))).toMatch(/PDF, JPEG/);
  });
});

describe('formatFileSize', () => {
  it('reads the way a person would say it', () => {
    expect(formatFileSize(512)).toBe('512 B');
    expect(formatFileSize(2048)).toBe('2 KB');
    expect(formatFileSize(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
