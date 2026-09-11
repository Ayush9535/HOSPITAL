import { beforeEach, describe, expect, it, vi } from 'vitest';
import statsCache from './platformStatsCache';

const STATS = { hospitals: { total: 1, active: 1, inactive: 0 } };

describe('platformStatsCache', () => {
  beforeEach(() => {
    sessionStorage.clear();
    statsCache.clear();
    vi.useRealTimers();
  });

  it('serves a fresh entry without a refetch', () => {
    sessionStorage.setItem('token', 'admin-one');
    statsCache.set(STATS);
    expect(statsCache.isValid()).toBe(true);
    expect(statsCache.data).toEqual(STATS);
  });

  it('is a miss once cleared', () => {
    sessionStorage.setItem('token', 'admin-one');
    statsCache.set(STATS);
    statsCache.clear();
    expect(statsCache.isValid()).toBe(false);
  });

  it('expires after the TTL', () => {
    sessionStorage.setItem('token', 'admin-one');
    vi.useFakeTimers();
    statsCache.set(STATS);
    vi.advanceTimersByTime(statsCache.TTL_MS + 1);
    expect(statsCache.isValid()).toBe(false);
  });

  // The cache is a module singleton: it survives logout, so a second admin signing in
  // to the same tab would otherwise read the first admin's counts.
  it('does not serve one admin cached counts to the next admin', () => {
    sessionStorage.setItem('token', 'admin-one');
    statsCache.set(STATS);
    expect(statsCache.isValid()).toBe(true);

    sessionStorage.setItem('token', 'admin-two');
    expect(statsCache.isValid()).toBe(false);
  });

  it('is a miss when there is no session at all', () => {
    sessionStorage.setItem('token', 'admin-one');
    statsCache.set(STATS);
    sessionStorage.removeItem('token');
    expect(statsCache.isValid()).toBe(false);
  });
});
