/**
 * useDebounce - Custom React hook for debouncing a value.
 *
 * Delays updating the returned value until `delay` milliseconds have passed
 * since the last change to `value`. Useful for preventing excessive API calls
 * on rapid user input (BUG-017: Missing Input Debouncing).
 *
 * @param {*}      value - The value to debounce
 * @param {number} delay - Delay in milliseconds (default: 500)
 * @returns {*}    The debounced value (updated after `delay` ms of silence)
 *
 * @example
 * const debouncedSearch = useDebounce(searchInput, 500);
 * useEffect(() => {
 *   fetchResults(debouncedSearch);
 * }, [debouncedSearch]);
 */
import { useState, useEffect } from 'react';

const useDebounce = (value, delay = 500) => {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    // Cleanup: cancel the previous timer on value change
    return () => clearTimeout(handler);
  }, [value, delay]);

  return debouncedValue;
};

export default useDebounce;
