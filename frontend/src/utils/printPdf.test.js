import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import apiClient from '../services/apiService';
import { isIOS } from './platform';
import { printBlob, printPdf } from './printPdf';

vi.mock('./platform', () => ({ isIOS: vi.fn(() => false) }));
vi.mock('../services/apiService', () => ({ default: { get: vi.fn() } }));

describe('printBlob', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    isIOS.mockReturnValue(false);
    if (!URL.createObjectURL) URL.createObjectURL = () => 'blob:x';
    if (!URL.revokeObjectURL) URL.revokeObjectURL = () => {};
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('desktop: mounts a hidden off-screen iframe with the blob url', async () => {
    printBlob(new Blob(['x'], { type: 'application/pdf' }));
    const iframe = document.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe.style.left).toBe('-10000px');
    expect(iframe.getAttribute('src')).toBe('blob:fake');
  });

  it('iOS: opens the blob in a foreground window, no iframe', async () => {
    isIOS.mockReturnValue(true);
    const fakeWin = {};
    vi.spyOn(window, 'open').mockReturnValue(fakeWin);
    const ok = await printBlob(new Blob(['x'], { type: 'application/pdf' }));
    expect(ok).toBe(true);
    expect(document.querySelector('iframe')).toBeNull();
    expect(fakeWin.location).toBe('blob:fake');
  });

  it('iOS: returns false when the popup is blocked', async () => {
    isIOS.mockReturnValue(true);
    vi.spyOn(window, 'open').mockReturnValue(null);
    const ok = await printBlob(new Blob(['x'], { type: 'application/pdf' }));
    expect(ok).toBe(false);
  });
});

describe('printPdf', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    isIOS.mockReturnValue(false);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('iOS: opens the window synchronously (within the gesture) before fetching', async () => {
    isIOS.mockReturnValue(true);
    const fakeWin = {};
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(fakeWin);
    let resolveGet;
    apiClient.get.mockReturnValue(
      new Promise((r) => {
        resolveGet = r;
      })
    );

    const p = printPdf('/hospital/billing/1/pdf');
    // The window must already be open before the fetch resolves.
    expect(openSpy).toHaveBeenCalledWith('', '_blank');

    resolveGet({ data: new ArrayBuffer(8) });
    const ok = await p;
    expect(ok).toBe(true);
    expect(fakeWin.location).toBe('blob:fake');
  });

  it('iOS: closes the pre-opened window if the fetch fails', async () => {
    isIOS.mockReturnValue(true);
    const close = vi.fn();
    vi.spyOn(window, 'open').mockReturnValue({ close });
    apiClient.get.mockRejectedValue(new Error('network'));
    const ok = await printPdf('/x');
    expect(ok).toBe(false);
    expect(close).toHaveBeenCalled();
  });

  it('desktop: does not open a window, uses the iframe path', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue({});
    apiClient.get.mockResolvedValue({ data: new ArrayBuffer(8) });
    // Not awaited: the desktop printBlob promise only resolves on the iframe's onload, which jsdom
    // does not fire for a blob: src. We just need the fetch .then to run and mount the iframe.
    printPdf('/x');
    await new Promise((r) => setTimeout(r, 0));
    expect(openSpy).not.toHaveBeenCalled();
    expect(document.querySelector('iframe')).not.toBeNull();
  });
});
