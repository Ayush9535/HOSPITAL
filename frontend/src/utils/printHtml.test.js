import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { isIOS } from './platform';
import printHtml from './printHtml';

vi.mock('./platform', () => ({ isIOS: vi.fn(() => false) }));

const iframeDoc = () => {
  const iframe = document.querySelector('iframe');
  return iframe && (iframe.contentDocument || iframe.contentWindow.document);
};

describe('printHtml (desktop / iframe path)', () => {
  beforeEach(() => {
    isIOS.mockReturnValue(false);
    vi.useFakeTimers();
    document.body.innerHTML = '';
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('renders the document content into a hidden off-screen iframe', () => {
    printHtml('<html><body><h1 id="t">Case Paper</h1></body></html>');
    const iframe = document.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe.style.left).toBe('-10000px');
    expect(iframeDoc().querySelector('#t')?.textContent).toBe('Case Paper');
  });

  it('strips <script> elements, including the builders auto-print script', () => {
    printHtml('<html><body><p>ok</p><script>window.print()</script></body></html>');
    const doc = iframeDoc();
    expect(doc.querySelectorAll('script').length).toBe(0);
    expect(doc.querySelector('p')?.textContent).toBe('ok');
  });

  it('removes scripts written with awkward end tags a regex would miss', () => {
    // "</script bar>" and "</script >" both close a script per the HTML parser, so the parser-based
    // removal drops these (and their code) where a "</script\\s*>" regex would have leaked them.
    printHtml(
      '<html><body><p>keep</p><script>evil()</script bar><script>more()</script ></body></html>'
    );
    const doc = iframeDoc();
    expect(doc.querySelectorAll('script').length).toBe(0);
    expect(doc.documentElement.innerHTML).not.toContain('evil()');
    expect(doc.documentElement.innerHTML).not.toContain('more()');
    expect(doc.querySelector('p')?.textContent).toBe('keep');
  });

  it('keeps the document in standards mode (doctype present)', () => {
    printHtml('<html><body>x</body></html>');
    expect(iframeDoc().doctype).not.toBeNull();
  });

  it('handles null/undefined/empty input without throwing', () => {
    expect(() => printHtml(null)).not.toThrow();
    expect(() => printHtml(undefined)).not.toThrow();
    expect(() => printHtml('')).not.toThrow();
  });

  it('triggers print() on the iframe after the render delay', () => {
    printHtml('<html><body>x</body></html>');
    const win = document.querySelector('iframe').contentWindow;
    const printSpy = vi.spyOn(win, 'print').mockImplementation(() => {});
    vi.advanceTimersByTime(400);
    expect(printSpy).toHaveBeenCalledTimes(1);
  });

  it('removes the iframe after the cleanup delay', () => {
    printHtml('<html><body>x</body></html>');
    const win = document.querySelector('iframe').contentWindow;
    vi.spyOn(win, 'print').mockImplementation(() => {});
    expect(document.querySelector('iframe')).not.toBeNull();
    vi.advanceTimersByTime(120001);
    expect(document.querySelector('iframe')).toBeNull();
  });
});

describe('printHtml (iOS path)', () => {
  let fakeWin;
  beforeEach(() => {
    isIOS.mockReturnValue(true);
    document.body.innerHTML = '';
    fakeWin = {
      document: document.implementation.createHTMLDocument(''),
      focus: vi.fn(),
      print: vi.fn(),
    };
    vi.spyOn(window, 'open').mockReturnValue(fakeWin);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    isIOS.mockReturnValue(false);
    document.body.innerHTML = '';
  });

  it('opens a foreground window instead of a hidden iframe', () => {
    printHtml('<html><body><h1 id="t">Rx</h1></body></html>');
    expect(window.open).toHaveBeenCalledWith('', '_blank');
    expect(document.querySelector('iframe')).toBeNull();
    expect(fakeWin.document.querySelector('#t')?.textContent).toBe('Rx');
  });

  it('injects exactly one auto-print trigger script into the new window', () => {
    printHtml('<html><body><p>ok</p><script>window.print()</script></body></html>');
    const scripts = fakeWin.document.querySelectorAll('script');
    expect(scripts.length).toBe(1); // source script stripped, one controlled trigger added
    expect(scripts[0].textContent).toContain('window.print()');
  });

  it('falls back to the hidden iframe when the popup is blocked', () => {
    window.open.mockReturnValue(null);
    printHtml('<html><body><h1 id="t">Rx</h1></body></html>');
    expect(document.querySelector('iframe')).not.toBeNull();
  });
});
