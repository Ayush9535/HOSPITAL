import apiClient from '../services/apiService';
import { isIOS } from './platform';

/**
 * Fetch a PDF from the API and open the browser's print dialog for it **on the current page**,
 * via a hidden iframe — the same way consultation documents and the clinical forms print.
 *
 * Why not window.open(url): that leaves the user on a stray tab, is blocked by popup blockers,
 * and needed the JWT in the query string to authenticate. Here the blob is fetched through
 * apiClient (Authorization header), so no token ever lands in a URL.
 *
 * iOS/iPadOS caveat: WebKit cannot print the content of a hidden off-screen iframe — the call
 * silently prints the visible page instead (the "screenshot" bug). On iOS we therefore open the
 * PDF blob in a real foreground tab so the user can print it with Safari's Share → Print.
 *
 * @param {string} endpointPath API path, e.g. `/hospital/billing/12/pdf`
 * @returns {Promise<boolean>} true once the print dialog was invoked, false if the fetch failed
 */

/**
 * iOS path: show a PDF blob in a foreground window so Safari's native print acts on it.
 * `win` may be a window opened synchronously during the click gesture (to dodge popup blocking);
 * when absent we open one here as a best effort.
 * @param {Blob} blob a PDF blob
 * @param {Window|null} [win] a pre-opened window to navigate, if any
 * @returns {boolean} true if a window received the PDF
 */
const showBlobIOS = (blob, win) => {
  const blobUrl = URL.createObjectURL(blob);
  const target = win || window.open('', '_blank');
  if (!target) {
    URL.revokeObjectURL(blobUrl);
    return false;
  }
  target.location = blobUrl;
  // The blob stays alive as long as the tab references it; revoke well after it has loaded.
  setTimeout(() => {
    try {
      URL.revokeObjectURL(blobUrl);
    } catch {
      /* ignore */
    }
  }, 120000);
  return true;
};

/**
 * Print a PDF Blob you already have (e.g. from a service that returns responseType:'blob')
 * on the current page via a hidden iframe. Same in-tab behaviour as printPdf.
 * @param {Blob} blob a PDF blob
 * @returns {Promise<boolean>}
 */
export const printBlob = (blob) =>
  new Promise((resolve) => {
    try {
      if (isIOS()) {
        resolve(showBlobIOS(blob));
        return;
      }
      const blobUrl = URL.createObjectURL(blob);
      const iframe = document.createElement('iframe');
      iframe.style.position = 'fixed';
      iframe.style.left = '-10000px';
      iframe.style.width = '210mm';
      iframe.style.height = '297mm';
      iframe.style.border = '0';
      iframe.onload = () => {
        setTimeout(() => {
          try {
            iframe.contentWindow.focus();
            iframe.contentWindow.print();
          } catch {
            /* ignore */
          }
          resolve(true);
        }, 300);
        setTimeout(() => {
          URL.revokeObjectURL(blobUrl);
          iframe.remove();
        }, 120000);
      };
      iframe.src = blobUrl;
      document.body.appendChild(iframe);
    } catch {
      resolve(false);
    }
  });

export const printPdf = (endpointPath) => {
  // On iOS, open the foreground window *now*, synchronously within the click gesture, so Safari
  // does not treat it as a popup once the async fetch resolves. It is navigated to the blob (or
  // closed on failure) after the PDF arrives.
  const iosWin = isIOS() ? window.open('', '_blank') : null;
  return apiClient
    .get(endpointPath, { responseType: 'blob' })
    .then((resp) => {
      const blob = new Blob([resp.data], { type: 'application/pdf' });
      if (isIOS()) return showBlobIOS(blob, iosWin);
      return printBlob(blob);
    })
    .catch(() => {
      if (iosWin) {
        try {
          iosWin.close();
        } catch {
          /* ignore */
        }
      }
      return false;
    });
};

export default printPdf;
