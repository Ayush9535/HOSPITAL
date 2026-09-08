import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import UserMenu from './UserMenu';

const Navbar = ({ title, user, onLogout, onProfile, onSupport, actions, onToggleSidebar }) => {
  const location = useLocation();
  const navigate = useNavigate();

  const isSingleDoctor = user?.role === 'HOSPITAL_ADMIN' && user?.isSingleDoctor;
  const isDoctorDashboard = location.pathname.includes('/hospital/doctor');

  // Only the Single Pharmacist Admin (solo) acts as both admin and pharmacist and
  // gets the dual-dashboard switch. SINGLE_PHARMACY has separate pharmacist logins;
  // MULTI_PHARMACY manages branches instead.
  const isStandalonePharmacy =
    user?.role === 'HOSPITAL_ADMIN' && user?.modules?.includes('SINGLE_PHARMACIST_ADMIN');
  const isPharmacyDashboard =
    location.pathname.includes('/pharmacy/pharmacy') ||
    location.pathname.includes('/hospital/pharmacy');

  return (
    <header className="bg-white border-b border-gray-200 z-10 w-full sticky top-0">
      <div className="flex justify-between items-center px-6 py-3">
        {/* Left side - Sidebar toggle button */}
        <button
          onClick={onToggleSidebar}
          className="p-2 text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-lg transition-colors"
          title="Toggle sidebar"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 6h16M4 12h16M4 18h16"
            />
          </svg>
        </button>

        {/* Right side - User menu and single doctor toggle */}
        <div className="flex items-center gap-4">
          {/* Quick actions if provided */}
          {actions && <div className="flex items-center gap-3">{actions}</div>}

          {/* Single Doctor Toggle Switch */}
          {isSingleDoctor && (
            <div className="flex items-center bg-gray-100 p-1 rounded-xl border border-gray-200 shadow-sm transition-all duration-300">
              <button
                onClick={() => {
                  sessionStorage.setItem('activeDashboard', 'doctor');
                  navigate('/hospital/doctor');
                }}
                className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold tracking-wide transition-all duration-300 flex items-center gap-1.5 ${
                  isDoctorDashboard
                    ? 'bg-white text-gray-900 shadow-md transform scale-105'
                    : 'text-gray-500 hover:text-gray-900 hover:bg-white/40'
                }`}
              >
                <svg
                  className={`w-3.5 h-3.5 ${isDoctorDashboard ? 'text-blue-600' : 'text-gray-400'}`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
                  />
                </svg>
                Doctor
              </button>
              <button
                onClick={() => {
                  sessionStorage.setItem('activeDashboard', 'admin');
                  navigate('/hospital/admin');
                }}
                className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold tracking-wide transition-all duration-300 flex items-center gap-1.5 ${
                  !isDoctorDashboard
                    ? 'bg-white text-gray-900 shadow-md transform scale-105'
                    : 'text-gray-500 hover:text-gray-900 hover:bg-white/40'
                }`}
              >
                <svg
                  className={`w-3.5 h-3.5 ${!isDoctorDashboard ? 'text-indigo-600' : 'text-gray-400'}`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                  />
                </svg>
                Admin
              </button>
            </div>
          )}

          {/* Standalone Pharmacy Toggle Switch */}
          {isStandalonePharmacy && (
            <div className="flex items-center bg-gray-100 p-1 rounded-xl border border-gray-200 shadow-sm transition-all duration-300">
              <button
                onClick={() => {
                  sessionStorage.setItem('activeDashboard', 'pharmacy');
                  navigate('/pharmacy/pharmacy');
                }}
                className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold tracking-wide transition-all duration-300 flex items-center gap-1.5 ${
                  isPharmacyDashboard
                    ? 'bg-white text-gray-900 shadow-md transform scale-105'
                    : 'text-gray-500 hover:text-gray-900 hover:bg-white/40'
                }`}
              >
                <svg
                  className={`w-3.5 h-3.5 ${isPharmacyDashboard ? 'text-green-600' : 'text-gray-400'}`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z"
                  />
                </svg>
                Pharmacy
              </button>
              <button
                onClick={() => {
                  sessionStorage.setItem('activeDashboard', 'admin');
                  navigate('/pharmacy/admin');
                }}
                className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold tracking-wide transition-all duration-300 flex items-center gap-1.5 ${
                  !isPharmacyDashboard
                    ? 'bg-white text-gray-900 shadow-md transform scale-105'
                    : 'text-gray-500 hover:text-gray-900 hover:bg-white/40'
                }`}
              >
                <svg
                  className={`w-3.5 h-3.5 ${!isPharmacyDashboard ? 'text-indigo-600' : 'text-gray-400'}`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                  />
                </svg>
                Admin
              </button>
            </div>
          )}

          {/* User menu */}
          <UserMenu user={user} onLogout={onLogout} onProfile={onProfile} onSupport={onSupport} />
        </div>
      </div>
    </header>
  );
};

export default Navbar;
