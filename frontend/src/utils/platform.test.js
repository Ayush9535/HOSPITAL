import { describe, it, expect, afterEach, vi } from 'vitest';
import { isIOS } from './platform';

const setNav = (props) => vi.stubGlobal('navigator', props);

describe('isIOS', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('detects iPhone via user agent', () => {
    setNav({
      userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)',
      platform: 'iPhone',
      maxTouchPoints: 5,
    });
    expect(isIOS()).toBe(true);
  });

  it('detects classic iPad via user agent', () => {
    setNav({
      userAgent: 'Mozilla/5.0 (iPad; CPU OS 15_0 like Mac OS X)',
      platform: 'iPad',
      maxTouchPoints: 5,
    });
    expect(isIOS()).toBe(true);
  });

  it('detects iPadOS 13+ masquerading as Macintosh (desktop UA + touch)', () => {
    setNav({
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari',
      platform: 'MacIntel',
      maxTouchPoints: 5,
    });
    expect(isIOS()).toBe(true);
  });

  it('is false on a real Mac (MacIntel, no touch)', () => {
    setNav({
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari',
      platform: 'MacIntel',
      maxTouchPoints: 0,
    });
    expect(isIOS()).toBe(false);
  });

  it('is false on Windows', () => {
    setNav({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      platform: 'Win32',
      maxTouchPoints: 0,
    });
    expect(isIOS()).toBe(false);
  });
});
