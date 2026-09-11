/**
 * Platform detection helpers.
 *
 * We only need this to branch printing behaviour: iOS/iPadOS Safari (WebKit) cannot print the
 * content of a hidden off-screen iframe — calling `iframe.contentWindow.print()` there falls back
 * to rasterising the visible top-level page (so the user gets a "screenshot" of the dashboard
 * instead of the real PDF/form). On iOS we therefore surface the document in a real foreground
 * window so Safari's native Share → Print acts on the correct content.
 */

/**
 * True on iPhone / iPod / iPad — including iPadOS 13+, which masquerades as desktop "Macintosh"
 * but is distinguishable by having touch points.
 * @returns {boolean}
 */
export const isIOS = () => {
  if (typeof navigator === 'undefined') return false;
  const ua = navigator.userAgent || '';
  const classicIOS = /iPad|iPhone|iPod/.test(ua);
  // iPadOS 13+ reports platform "MacIntel" with a desktop UA; touch points give it away.
  const iPadOS = navigator.platform === 'MacIntel' && (navigator.maxTouchPoints || 0) > 1;
  return classicIOS || iPadOS;
};

export default isIOS;
