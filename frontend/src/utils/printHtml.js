import { isIOS } from './platform';

/**
 * Print a client-built HTML document **on the current page** via a hidden iframe — the same
 * in-tab behaviour as the PDF prints (bills, case paper). Replaces the old pattern of opening a
 * blank new tab (window.open('', '_blank')) and writing the HTML into it, which left a stray tab
 * and was popup-blocked.
 *
 * Many of our HTML builders embed their own `<script>…window.print()…</script>` to auto-print in
 * the new tab. Inside the iframe that would fire in addition to this util's own print() call and
 * pop the dialog twice, so we strip every script and make this util the single print trigger.
 *
 * iOS/iPadOS caveat: WebKit cannot print the content of a hidden off-screen iframe — the call
 * prints the visible page instead (the "screenshot" bug). On iOS we open the document in a real
 * foreground window and let the top-level window.print() (which iOS does honour) drive the print.
 *
 * @param {string} rawHtml full HTML document string
 */
export const printHtml = (rawHtml) => {
  // Remove every <script> before the document reaches the sink, using the browser's own HTML
  // parser rather than a regex. DOMParser.parseFromString does NOT execute scripts, so parsing is
  // safe, and querySelectorAll('script') finds every script element the parser recognises —
  // including the awkward forms a regex misses ("</script bar>", overlapping "<scr<script>…"),
  // with none of the reappearance foot-guns of string replacement (this is the robust fix for
  // CodeQL js/incomplete-multi-character-sanitization + js/bad-tag-filter). Interpolated data is
  // also HTML-escaped by the callers (utils/escapeHtml).
  const parsed = new DOMParser().parseFromString(String(rawHtml || ''), 'text/html');
  parsed.querySelectorAll('script').forEach((script) => script.remove());

  if (isIOS()) {
    printHtmlIOS(parsed);
    return;
  }
  printHtmlViaIframe(parsed);
};

/**
 * iOS path: render the (already script-stripped) document into a real foreground window and let
 * the top-level window.print() run there — the only place iOS reliably prints. Opened via
 * window.open, which must happen inside the click gesture; callers invoke printHtml directly from
 * their handlers, so we are still within the gesture here.
 * @param {Document} parsed a parsed, script-free HTML document
 */
const printHtmlIOS = (parsed) => {
  const win = window.open('', '_blank');
  // Popup blocked (rare inside a gesture): fall back to the iframe path — not ideal on iOS, but
  // better than doing nothing.
  if (!win) {
    printHtmlViaIframe(parsed);
    return;
  }
  const doc = win.document;
  doc.open();
  doc.write('<!DOCTYPE html>');
  doc.close();
  doc.replaceChild(doc.importNode(parsed.documentElement, true), doc.documentElement);
  // Re-add the single auto-print trigger this util strips from the source. On iOS a top-level
  // window.print() opens the native print/share sheet on the correct content.
  const trigger = doc.createElement('script');
  trigger.textContent =
    'setTimeout(function(){try{window.focus();window.print();}catch(e){}},500);';
  doc.body.appendChild(trigger);
};

/**
 * The hidden off-screen iframe render + print, extracted so the iOS popup-blocked fallback can
 * reuse it. `parsed` is an already script-stripped HTML document.
 * @param {Document} parsed a parsed, script-free HTML document
 */
const printHtmlViaIframe = (parsed) => {
  const iframe = document.createElement('iframe');
  iframe.style.position = 'fixed';
  iframe.style.left = '-10000px';
  iframe.style.width = '210mm';
  iframe.style.height = '297mm';
  iframe.style.border = '0';
  document.body.appendChild(iframe);
  const doc = iframe.contentDocument || iframe.contentWindow.document;
  // Force standards mode (a bare iframe is quirks mode, which changes print layout): write only a
  // constant doctype, then swap the iframe's <html> for the parsed, script-free one. The doctype
  // node survives replaceChild, so standards mode sticks. No HTML string is written to the sink.
  doc.open();
  doc.write('<!DOCTYPE html>');
  doc.close();
  doc.replaceChild(doc.importNode(parsed.documentElement, true), doc.documentElement);
  // Content is in place synchronously; give images/styles a moment, then print.
  setTimeout(() => {
    try {
      iframe.contentWindow.focus();
      iframe.contentWindow.print();
    } catch {
      /* ignore */
    }
  }, 400);
  setTimeout(() => {
    try {
      iframe.remove();
    } catch {
      /* ignore */
    }
  }, 120000);
};

export default printHtml;
