import React from 'react';

/**
 * StaffDetailsModal - Unified read-only detailed view for Doctors, Receptionists, and Pharmacists.
 */
const StaffDetailsModal = ({ staff, role, onClose }) => {
  if (!staff) return null;

  const formattedRole = role ? role.toUpperCase() : staff.role || 'DOCTOR';

  // Role-specific badges and descriptions
  const roleConfig = {
    DOCTOR: {
      title: 'Doctor Profile',
      badgeBg: 'bg-emerald-50 text-emerald-700 border-emerald-200',
      icon: (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-8 w-8 text-emerald-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01"
          />
        </svg>
      ),
    },
    RECEPTIONIST: {
      title: 'Receptionist Profile',
      badgeBg: 'bg-indigo-50 text-indigo-700 border-indigo-200',
      icon: (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-8 w-8 text-indigo-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 100-6 3 3 0 000 6z"
          />
        </svg>
      ),
    },
    PHARMACIST: {
      title: 'Pharmacist Profile',
      badgeBg: 'bg-pink-50 text-pink-700 border-pink-200',
      icon: (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-8 w-8 text-pink-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z"
          />
        </svg>
      ),
    },
    OT_INCHARGE: {
      title: 'OT Incharge Profile',
      badgeBg: 'bg-teal-50 text-teal-700 border-teal-200',
      icon: (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-8 w-8 text-teal-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
          />
        </svg>
      ),
    },
    'OT-INCHARGE': {
      title: 'OT Incharge Profile',
      badgeBg: 'bg-teal-50 text-teal-700 border-teal-200',
      icon: (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-8 w-8 text-teal-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
          />
        </svg>
      ),
    },
  };

  const config = roleConfig[formattedRole] || roleConfig.DOCTOR;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in">
      <div className="bg-white w-full max-w-xl rounded-2xl shadow-2xl border border-neutral-100 overflow-hidden transform scale-100 transition-all duration-300">
        {/* Header Section */}
        <div className="relative p-6 border-b border-neutral-100 bg-neutral-50 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-white rounded-xl shadow-sm border border-neutral-200/50">
              {config.icon}
            </div>
            <div>
              <span
                className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold border ${config.badgeBg} mb-1`}
              >
                {formattedRole}
              </span>
              <h2 className="text-xl font-bold text-slate-800">{config.title}</h2>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-slate-400 hover:text-slate-600 hover:bg-neutral-200/50 p-2 rounded-lg transition-colors"
            title="Close Modal"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="h-6 w-6"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Details Grid */}
        <div className="p-8 space-y-6">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-6">
            <div>
              <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                Staff ID
              </span>
              <p className="text-sm font-semibold text-slate-700 bg-neutral-50 px-3 py-2 rounded-lg border border-neutral-100">
                {staff.customId || staff.publicId || staff.id}
              </p>
            </div>

            <div>
              <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                Status
              </span>
              <div className="pt-1.5">
                <span
                  className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold ${
                    staff.isActive !== false
                      ? 'bg-emerald-500/10 text-emerald-700'
                      : 'bg-rose-500/10 text-rose-700'
                  }`}
                >
                  <span
                    className={`h-2 w-2 rounded-full ${
                      staff.isActive !== false ? 'bg-emerald-500' : 'bg-rose-500'
                    }`}
                  ></span>
                  {staff.isActive !== false ? 'Active' : 'Inactive'}
                </span>
              </div>
            </div>

            <div className="sm:col-span-2">
              <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                Full Name
              </span>
              <p className="text-base font-semibold text-slate-800 bg-neutral-50 px-3 py-2 rounded-lg border border-neutral-100">
                {staff.name}
              </p>
            </div>

            {formattedRole === 'DOCTOR' && (
              <div className="sm:col-span-2">
                <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                  Specialization
                </span>
                <p className="text-sm font-semibold text-slate-700 bg-neutral-50 px-3 py-2 rounded-lg border border-neutral-100">
                  {staff.specialization || 'Not Specified'}
                </p>
              </div>
            )}

            <div>
              <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                Email (Login ID)
              </span>
              <p className="text-sm font-medium text-slate-700 bg-neutral-50 px-3 py-2 rounded-lg border border-neutral-100 break-all">
                {staff.email}
              </p>
            </div>

            {formattedRole === 'DOCTOR' && (
              <div>
                <span className="block text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
                  Phone Number
                </span>
                <p className="text-sm font-medium text-slate-700 bg-neutral-50 px-3 py-2 rounded-lg border border-neutral-100">
                  {staff.phone || 'Not Provided'}
                </p>
              </div>
            )}
          </div>
        </div>

        {/* Footer Section */}
        <div className="p-6 border-t border-neutral-100 bg-neutral-50/50 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2.5 bg-slate-800 hover:bg-slate-900 text-white rounded-xl text-sm font-semibold shadow-md transition-all active:scale-95"
          >
            Close Details
          </button>
        </div>
      </div>
    </div>
  );
};

export default StaffDetailsModal;
