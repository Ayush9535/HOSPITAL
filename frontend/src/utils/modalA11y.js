/**
 * Shared accessibility props for a modal/overlay backdrop element.
 *
 * Returns the role / tabIndex / aria-label plus the click-outside and
 * Escape-to-close handlers, so every modal backdrop stays keyboard-accessible
 * without copying the same block into each component. Spread onto the backdrop:
 *
 *   <div className="fixed inset-0 ..." {...backdropProps(onClose)}>
 *     ...dialog content...
 *   </div>
 *
 * Clicking the backdrop itself (not its children) or pressing Escape calls
 * `onClose`.
 *
 * @param {() => void} onClose invoked on outside-click or Escape.
 * @returns {object} props to spread onto the backdrop element.
 */
export const backdropProps = (onClose) => ({
  role: 'button',
  tabIndex: -1,
  'aria-label': 'Close dialog',
  onClick: (e) => {
    if (e.target === e.currentTarget) onClose();
  },
  onKeyDown: (e) => {
    if (e.key === 'Escape') onClose();
  },
});
