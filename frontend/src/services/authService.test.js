import { describe, it, expect, vi, beforeEach } from 'vitest';

// authService imports the axios client as its default export; stub it.
vi.mock('./apiService', () => ({
  default: { post: vi.fn(), get: vi.fn(), put: vi.fn() },
}));

import apiClient from './apiService';
import authService from './authService';

const setUser = (u) => sessionStorage.setItem('user', JSON.stringify(u));

describe('authService', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  it('platformLogin stores token + user on success', async () => {
    apiClient.post.mockResolvedValue({ data: { token: 't1', role: 'SUPER_ADMIN' } });
    const data = await authService.platformLogin('a@b.com', 'pw');
    expect(data.token).toBe('t1');
    expect(sessionStorage.getItem('token')).toBe('t1');
    expect(JSON.parse(sessionStorage.getItem('user')).role).toBe('SUPER_ADMIN');
  });

  it('hospitalLogin posts entityType and persists the session', async () => {
    apiClient.post.mockResolvedValue({ data: { token: 't2', role: 'DOCTOR' } });
    await authService.hospitalLogin('d@b.com', 'pw', 'CLINIC');
    expect(apiClient.post).toHaveBeenCalledWith('/login', {
      email: 'd@b.com',
      password: 'pw',
      entityType: 'CLINIC',
    });
    expect(sessionStorage.getItem('token')).toBe('t2');
  });

  it('getLoginUrl reflects the tenant type (and is resilient to bad JSON)', () => {
    setUser({ hospitalType: 'CLINIC' });
    expect(authService.getLoginUrl()).toBe('/login/clinic');
    setUser({ hospitalType: 'PHARMACY' });
    expect(authService.getLoginUrl()).toBe('/login/pharmacy');
    setUser({ hospitalType: 'HOSPITAL' });
    expect(authService.getLoginUrl()).toBe('/login/hospital');
    sessionStorage.setItem('user', '{not json');
    expect(authService.getLoginUrl()).toBe('/login/hospital');
  });

  it('logout clears the session', () => {
    sessionStorage.setItem('token', 'x');
    setUser({ role: 'DOCTOR' });
    authService.logout();
    expect(sessionStorage.getItem('token')).toBeNull();
    expect(sessionStorage.getItem('user')).toBeNull();
  });

  it('getCurrentUser / isAuthenticated reflect stored state', () => {
    expect(authService.getCurrentUser()).toBeNull();
    expect(authService.isAuthenticated()).toBe(false);
    sessionStorage.setItem('token', 'x');
    setUser({ role: 'DOCTOR' });
    expect(authService.isAuthenticated()).toBe(true);
    expect(authService.getCurrentUser().role).toBe('DOCTOR');
  });

  it('role helpers classify the current user', () => {
    setUser({ role: 'SUPER_ADMIN' });
    expect(authService.isSuperAdmin()).toBe(true);
    setUser({ role: 'HOSPITAL_ADMIN' });
    expect(authService.isHospitalAdmin()).toBe(true);
    setUser({ role: 'DOCTOR' });
    expect(authService.isDoctor()).toBe(true);
    setUser({ role: 'HOSPITAL_ADMIN', isSingleDoctor: true });
    expect(authService.isDoctor()).toBe(true); // single-doctor admin acts as doctor
    setUser({ role: 'RECEPTIONIST' });
    expect(authService.isReceptionist()).toBe(true);
    setUser({ role: 'PHARMACIST' });
    expect(authService.isPharmacist()).toBe(true);
  });

  it('getProfile fetches /auth/me and updateCurrentUser merges while preserving the token', async () => {
    apiClient.get.mockResolvedValue({ data: { role: 'DOCTOR', inClinic: false } });
    const profile = await authService.getProfile();
    expect(profile.inClinic).toBe(false);

    setUser({ role: 'DOCTOR', token: 'keep' });
    const merged = authService.updateCurrentUser({ inClinic: false });
    expect(merged.token).toBe('keep');
    expect(merged.inClinic).toBe(false);
    expect(JSON.parse(sessionStorage.getItem('user')).inClinic).toBe(false);
  });
});
