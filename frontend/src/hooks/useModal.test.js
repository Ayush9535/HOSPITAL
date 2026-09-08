import { renderHook, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { useModal } from './useModal';

describe('useModal', () => {
  it('starts closed with default view mode', () => {
    const { result } = renderHook(() => useModal());
    expect(result.current.isOpen).toBe(false);
    expect(result.current.data).toBeNull();
    expect(result.current.mode).toBe('view');
  });

  it('opens with supplied data and mode', () => {
    const { result } = renderHook(() => useModal());
    act(() => result.current.open({ id: 1 }, 'edit'));
    expect(result.current.isOpen).toBe(true);
    expect(result.current.data).toEqual({ id: 1 });
    expect(result.current.mode).toBe('edit');
  });

  it('resets to defaults on close', () => {
    const { result } = renderHook(() => useModal());
    act(() => result.current.open({ id: 2 }, 'create'));
    act(() => result.current.close());
    expect(result.current.isOpen).toBe(false);
    expect(result.current.data).toBeNull();
    expect(result.current.mode).toBe('view');
  });
});
