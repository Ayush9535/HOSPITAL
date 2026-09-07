import React, { useState, useRef, useEffect } from 'react';
import { titleCase } from '../utils/text';

const UserMenu = ({ user, onLogout, onProfile, onSupport }) => {
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef(null);

  // Close menu when clicking outside
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const toggleMenu = () => {
    setIsOpen(!isOpen);
  };

  // Get user initials
  const getInitials = (name) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  // Get role display name
  const getRoleDisplay = (role) => {
    if (!role) return 'User';
    return role
      .replace('HOSPITAL_', '')
      .replace('_', ' ')
      .toLowerCase()
      .split(' ')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  };

  return (
    <div className="relative" ref={menuRef}>
      {/* Trigger Button - Enhanced Avatar */}
      <button
        type="button"
        onClick={toggleMenu}
        className="group relative h-11 w-11 rounded-lg bg-gray-900 flex items-center justify-center text-white font-semibold text-sm border-2 border-white hover:bg-gray-800 transition-all duration-300 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2"
        aria-label="User menu"
      >
        {getInitials(user?.name)}

        {/* Online indicator */}
        <div className="absolute -bottom-0.5 -right-0.5 w-3.5 h-3.5 bg-success-500 border-2 border-white rounded-full animate-pulse"></div>

        {/* Hover effect overlay */}
        <div className="absolute inset-0 rounded-2xl bg-white/20 opacity-0 group-hover:opacity-100 transition-opacity duration-300"></div>
      </button>

      {/* Dropdown Menu */}
      {isOpen && (
        <div className="absolute right-0 mt-3 w-64 bg-white rounded-lg border border-gray-200 py-2 z-50 overflow-hidden">
          {/* Header Section with User Info */}
          <div className="px-5 py-4 border-b border-gray-200 bg-white">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-lg bg-gray-900 flex items-center justify-center text-white font-bold text-sm">
                {getInitials(user?.name)}
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-neutral-800 truncate">{user?.name}</p>
                <p className="text-xs text-neutral-500 truncate">{getRoleDisplay(user?.role)}</p>
                {user?.hospitalName && (
                  <p className="text-xs text-primary-600 truncate font-medium mt-0.5">
                    {titleCase(user.hospitalName)}
                  </p>
                )}
              </div>
            </div>
          </div>

          {/* Menu Items */}
          <div className="py-2">
            <button
              type="button"
              onClick={() => {
                setIsOpen(false);
                if (onProfile) onProfile();
              }}
              className="w-full text-left px-5 py-3 text-sm text-neutral-700 hover:bg-neutral-50 flex items-center gap-3 transition-all duration-200 group"
            >
              <div className="w-8 h-8 rounded-lg bg-neutral-100 flex items-center justify-center group-hover:bg-gray-200 transition-colors duration-200">
                <svg
                  className="w-5 h-5 text-neutral-600 group-hover:text-gray-900"
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
              </div>
              <div>
                <p className="font-medium">Profile Settings</p>
                <p className="text-xs text-neutral-500">Manage your account</p>
              </div>
            </button>

            {user?.role === 'HOSPITAL_ADMIN' && (
              <button
                type="button"
                onClick={() => {
                  setIsOpen(false);
                  if (onSupport) onSupport();
                }}
                className="w-full text-left px-5 py-3 text-sm text-neutral-700 hover:bg-neutral-50 flex items-center gap-3 transition-all duration-200 group mt-1"
              >
                <div className="w-8 h-8 rounded-lg bg-neutral-100 flex items-center justify-center group-hover:bg-gray-200 transition-colors duration-200">
                  <svg
                    className="w-5 h-5 text-neutral-600 group-hover:text-gray-900"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M18.364 5.636l-3.536 3.536m0 5.656l3.536 3.536M9.172 9.172L5.636 5.636m3.536 9.192l-3.536 3.536M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-5 0a4 4 0 11-8 0 4 4 0 018 0z"
                    />
                  </svg>
                </div>
                <div>
                  <p className="font-medium">Help & Support</p>
                  <p className="text-xs text-neutral-500">FAQ & support tickets</p>
                </div>
              </button>
            )}
          </div>

          {/* Logout Section */}
          <div className="border-t border-neutral-50 py-2">
            <button
              type="button"
              onClick={() => {
                setIsOpen(false);
                onLogout();
              }}
              className="w-full text-left px-5 py-3 text-sm text-gray-900 hover:bg-gray-50 flex items-center gap-3 transition-all duration-200 font-medium group"
            >
              <div className="w-8 h-8 rounded-lg bg-gray-100 flex items-center justify-center group-hover:bg-gray-200 transition-colors duration-200">
                <svg
                  className="w-5 h-5 text-gray-900"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
                  />
                </svg>
              </div>
              <div>
                <p className="font-medium">Sign Out</p>
                <p className="text-xs text-error-500">End your session</p>
              </div>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserMenu;
