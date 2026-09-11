import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import useDebounce from './useDebounce';

describe('useDebounce', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('returns the initial value immediately', () => {
    const { result } = renderHook(() => useDebounce('hello', 500));
    expect(result.current).toBe('hello');
  });

  it('updates only after the delay elapses', () => {
    const { result, rerender } = renderHook(({ v, d }) => useDebounce(v, d), {
      initialProps: { v: 'a', d: 500 },
    });
    rerender({ v: 'b', d: 500 });
    expect(result.current).toBe('a'); // still old before the timer fires
    act(() => vi.advanceTimersByTime(500));
    expect(result.current).toBe('b');
  });

  it('uses the default 500ms delay when none is given', () => {
    const { result, rerender } = renderHook(({ v }) => useDebounce(v), {
      initialProps: { v: 'x' },
    });
    rerender({ v: 'y' });
    act(() => vi.advanceTimersByTime(499));
    expect(result.current).toBe('x');
    act(() => vi.advanceTimersByTime(1));
    expect(result.current).toBe('y');
  });
});
