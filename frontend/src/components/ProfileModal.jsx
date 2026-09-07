import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import authService from '../services/authService';
import { extractApiError } from '../utils/apiError';
import { backdropProps } from '../utils/modalA11y';

/**
 * ProfileModal - A stunning reusable component for editing actor profile settings
 *
 * Supports HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST, PHARMACIST, and SUPER_ADMIN
 *
 * @param {boolean} isOpen - Whether modal is visible
 * @param {function} onClose - Modal close handler
 */
const ProfileModal = ({ isOpen, onClose }) => {
  const { success, error: toastError } = useToast();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

  // Core Profile Fields
  const [profile, setProfile] = useState({
    name: '',
    email: '',
    role: '',
    phone: '',
    age: '',
    gender: '',
    specialization: '',
    hospitalName: '',
    hospitalAddress: '',
    hospitalPhone: '',
    parentOrganization: '',
    logoUrl: '',
  });

  // Password fields
  const [changePassword, setChangePassword] = useState(false);
  const [passwords, setPasswords] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  });

  const [formErrors, setFormErrors] = useState({});
  const [uploadingLogo, setUploadingLogo] = useState(false);

  // Fetch the latest profile data when the modal is opened
  useEffect(() => {
    if (isOpen) {
      fetchLatestProfile();
      setIsEditing(false);
      setChangePassword(false);
      setPasswords({
        currentPassword: '',
        newPassword: '',
        confirmPassword: '',
      });
      setFormErrors({});
    }
  }, [isOpen]);

  const fetchLatestProfile = async () => {
    setLoading(true);
    try {
      const data = await authService.getProfile();
      setProfile({
        name: data.name || '',
        email: data.email || '',
        role: data.role || '',
        phone: data.phone || '',
        age: data.age !== null ? data.age : '',
        gender: data.gender || '',
        specialization: data.specialization || '',
        hospitalName: data.hospitalName || '',
        hospitalAddress: data.hospitalAddress || '',
        hospitalPhone: data.hospitalPhone || '',
        parentOrganization: data.parentOrganization || '',
        logoUrl: data.logoUrl || '',
      });
    } catch (err) {
      console.error('Error fetching latest profile details:', err);
      toastError('Failed to load profile details.');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  // Get initials for profile picture
  const getInitials = (name) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  // Human readable role tags
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

  const handleInputChange = (field, value) => {
    setProfile((prev) => ({
      ...prev,
      [field]: value,
    }));
    if (formErrors[field]) {
      setFormErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
  };

  // Persists just the logo to the backend immediately after a successful
  // Cloudinary upload, instead of waiting for the full "Save Settings"
  // submit. The full-form save is gated behind validate() requiring a
  // phone number — on accounts where that's still blank (common on older
  // records), clicking Save silently no-ops and the logo never reaches the
  // backend even though Cloudinary itself succeeded. This resends the
  // *current* values of every other field so nothing gets overwritten —
  // the backend's updateProfile applies phone/age/gender unconditionally,
  // so an empty payload would null them out.
  const persistLogoUrl = async (logoUrl) => {
    try {
      const updatePayload = {
        name: profile.name,
        phone: profile.role !== 'SUPER_ADMIN' ? profile.phone : null,
        age:
          profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR' && profile.age !== ''
            ? parseInt(profile.age, 10)
            : null,
        gender: profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR' ? profile.gender : null,
        specialization:
          profile.role === 'DOCTOR' ||
          (profile.role === 'HOSPITAL_ADMIN' && authService.getCurrentUser()?.isSingleDoctor)
            ? profile.specialization
            : null,
        hospitalName: profile.hospitalName,
        hospitalAddress: profile.hospitalAddress,
        hospitalPhone: profile.hospitalPhone,
        parentOrganization: profile.parentOrganization,
        logoUrl,
      };
      await authService.updateProfile(updatePayload);
      return true;
    } catch (err) {
      console.error('Failed to save logo to profile:', err);
      toastError('Logo uploaded but could not be saved. Please try again.');
      return false;
    }
  };

  const handleLogoUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    // Only allow real raster image types (no SVG — it can carry script) and cap the size.
    const ALLOWED_LOGO_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];
    if (!ALLOWED_LOGO_TYPES.includes(file.type)) {
      toastError('Logo must be a PNG, JPG, WEBP or GIF image.');
      return;
    }
    if (file.size > 1048576) {
      toastError('Logo image size must be under 1MB.');
      return;
    }

    const cloudName = import.meta.env.VITE_CLOUDINARY_CLOUD_NAME;
    const uploadPreset = import.meta.env.VITE_CLOUDINARY_UPLOAD_PRESET;

    if (!cloudName || !uploadPreset) {
      toastError(
        'Cloudinary is not configured. Please define VITE_CLOUDINARY_CLOUD_NAME and VITE_CLOUDINARY_UPLOAD_PRESET in your environment.'
      );
      return;
    }

    setUploadingLogo(true);
    const formData = new FormData();
    formData.append('file', file);
    formData.append('upload_preset', uploadPreset);

    try {
      const axios = (await import('axios')).default;
      const res = await axios.post(
        `https://api.cloudinary.com/v1_1/${cloudName}/image/upload`,
        formData
      );
      if (res.data && res.data.secure_url) {
        setProfile((prev) => ({
          ...prev,
          logoUrl: res.data.secure_url,
        }));
        const saved = await persistLogoUrl(res.data.secure_url);
        if (saved) {
          success('Logo uploaded and saved successfully!');
        }
      } else {
        toastError('Failed to upload logo.');
      }
    } catch (err) {
      console.error('Cloudinary upload error:', err);
      toastError(err.response?.data?.error?.message || 'Failed to upload logo to Cloudinary.');
    } finally {
      setUploadingLogo(false);
    }
  };

  const handlePasswordChange = (field, value) => {
    setPasswords((prev) => ({
      ...prev,
      [field]: value,
    }));
    if (formErrors[field]) {
      setFormErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
  };

  // Validation
  const validate = () => {
    const errors = {};

    if (!profile.name.trim()) {
      errors.name = 'Full name is required';
    }

    // Only validate detailed fields for non-Super Admin
    if (profile.role !== 'SUPER_ADMIN') {
      if (!profile.phone.trim()) {
        errors.phone = 'Phone number is required';
      } else if (!/^\+?[0-9\s-]{8,15}$/.test(profile.phone)) {
        errors.phone = 'Invalid phone number format';
      }

      if (profile.role !== 'DOCTOR') {
        if (profile.age !== '') {
          const parsedAge = parseInt(profile.age, 10);
          if (Number.isNaN(parsedAge) || parsedAge < 0 || parsedAge > 125) {
            errors.age = 'Please specify a realistic age (0-125)';
          }
        }
      }

      if (
        profile.role === 'DOCTOR' ||
        (profile.role === 'HOSPITAL_ADMIN' && authService.getCurrentUser()?.isSingleDoctor)
      ) {
        if (!profile.specialization.trim()) {
          errors.specialization = 'Doctor specialization is required';
        }
      }
    }

    // Change password validation
    if (changePassword) {
      if (!passwords.currentPassword) {
        errors.currentPassword = 'Current password is required to verify';
      }
      if (!passwords.newPassword) {
        errors.newPassword = 'New password is required';
      } else if (passwords.newPassword.length < 6) {
        errors.newPassword = 'Password must be at least 6 characters long';
      }
      if (passwords.newPassword !== passwords.confirmPassword) {
        errors.confirmPassword = 'Passwords do not match';
      }
    }

    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSave = async (e) => {
    e.preventDefault();
    if (saving || loading) return;

    if (!validate()) {
      toastError('Please fix the validation errors in the form.');
      return;
    }

    setSaving(true);
    try {
      const updatePayload = {
        name: profile.name,
        phone: profile.role !== 'SUPER_ADMIN' ? profile.phone : null,
        age:
          profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR' && profile.age !== ''
            ? parseInt(profile.age, 10)
            : null,
        gender: profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR' ? profile.gender : null,
        specialization:
          profile.role === 'DOCTOR' ||
          (profile.role === 'HOSPITAL_ADMIN' && authService.getCurrentUser()?.isSingleDoctor)
            ? profile.specialization
            : null,
        currentPassword: changePassword ? passwords.currentPassword : null,
        newPassword: changePassword ? passwords.newPassword : null,
      };

      if (profile.role === 'HOSPITAL_ADMIN') {
        updatePayload.hospitalName = profile.hospitalName;
        updatePayload.hospitalAddress = profile.hospitalAddress;
        updatePayload.hospitalPhone = profile.hospitalPhone;
        updatePayload.parentOrganization = profile.parentOrganization;
        updatePayload.logoUrl = profile.logoUrl;
      }

      await authService.updateProfile(updatePayload);
      success('Profile settings updated successfully.');
      setIsEditing(false);
      fetchLatestProfile();
    } catch (err) {
      console.error('Error updating profile settings:', err);
      toastError(extractApiError(err, 'Failed to update profile settings.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60] p-4 animate-fadeIn"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden transform transition-all duration-300 scale-100 flex flex-col max-h-[90vh]">
        {/* Modal Header */}
        <div className="relative px-6 py-5 border-b border-gray-100 bg-neutral-50 flex items-center justify-between">
          <div>
            <h3 className="text-lg font-bold text-gray-900">Profile Settings</h3>
            <p className="text-xs text-neutral-500 mt-0.5">
              {isEditing
                ? 'Modify your personal information and credentials'
                : 'View your account details'}
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-neutral-400 hover:text-gray-900 hover:bg-neutral-100 transition-colors"
            aria-label="Close modal"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2.5}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {loading ? (
          <div className="flex-1 py-12 flex flex-col items-center justify-center">
            <svg
              className="animate-spin h-8 w-8 text-neutral-800"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              ></circle>
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              ></path>
            </svg>
            <span className="text-sm font-medium text-neutral-500 mt-3">
              Fetching fresh profile details...
            </span>
          </div>
        ) : (
          <div className="flex-1 flex flex-col overflow-hidden">
            {/* Scrollable Content */}
            <div className="p-6 overflow-y-auto space-y-6 flex-1 max-h-[calc(90vh-130px)]">
              {/* Actor Premium Banner Card */}
              <div className="bg-gradient-to-br from-neutral-900 to-gray-800 rounded-xl p-5 text-white flex items-center gap-4 relative overflow-hidden shadow-md">
                <div className="absolute inset-0 bg-white/5 pointer-events-none"></div>
                <div className="w-16 h-16 rounded-xl bg-white/10 backdrop-blur-md flex items-center justify-center text-white font-bold text-xl border border-white/20 shadow-inner">
                  {getInitials(profile.name)}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h4 className="text-base font-bold truncate">
                      {profile.name || 'Default User'}
                    </h4>
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold bg-white/20 text-white border border-white/10 uppercase tracking-wide">
                      {getRoleDisplay(profile.role)}
                    </span>
                  </div>
                  <p className="text-xs text-white/70 mt-0.5 truncate">{profile.email}</p>
                  {profile.hospitalName && (
                    <p className="text-xs text-primary-400 font-semibold mt-1 flex items-center gap-1">
                      <svg
                        className="w-3.5 h-3.5"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
                        />
                      </svg>
                      {profile.hospitalName}
                    </p>
                  )}
                </div>
              </div>

              {/* View Mode (Read-only Info) */}
              {!isEditing ? (
                (() => {
                  const infoFields = [
                    { label: 'Full Name', value: profile.name || '—' },
                    { label: 'Email Address', value: profile.email || '—' },
                    ...(profile.role !== 'SUPER_ADMIN'
                      ? [{ label: 'Phone Number', value: profile.phone || '—' }]
                      : []),
                    ...(profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR'
                      ? [
                          { label: 'Age', value: profile.age !== '' ? profile.age : '—' },
                          {
                            label: 'Gender',
                            value: profile.gender
                              ? profile.gender.charAt(0) + profile.gender.slice(1).toLowerCase()
                              : '—',
                          },
                        ]
                      : []),
                    ...(profile.role === 'DOCTOR' ||
                    (profile.role === 'HOSPITAL_ADMIN' &&
                      authService.getCurrentUser()?.isSingleDoctor)
                      ? [{ label: 'Specialization', value: profile.specialization || '—' }]
                      : []),
                  ];

                  if (profile.role === 'HOSPITAL_ADMIN') {
                    infoFields.push(
                      { label: 'Hospital Name', value: profile.hospitalName || '—' },
                      { label: 'Hospital Phone', value: profile.hospitalPhone || '—' },
                      { label: 'Hospital Address', value: profile.hospitalAddress || '—' },
                      { label: 'Parent Organization', value: profile.parentOrganization || '—' }
                    );
                  }

                  return (
                    <div className="space-y-6">
                      <div className="flex items-center justify-between border-b border-gray-100 pb-2">
                        <h5 className="text-xs font-bold uppercase tracking-wider text-neutral-400">
                          Profile Information
                        </h5>
                      </div>

                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 bg-neutral-50/50 rounded-xl p-5 border border-gray-100">
                        {infoFields.map((field, idx) => (
                          <div key={idx} className="space-y-1">
                            <span className="text-[10px] font-bold text-neutral-500 uppercase tracking-wider block leading-relaxed pt-1 pb-0.5">
                              {field.label}
                            </span>
                            <p className="text-sm font-semibold text-neutral-800 leading-normal pt-0.5">
                              {field.value}
                            </p>
                          </div>
                        ))}
                      </div>

                      {profile.role === 'HOSPITAL_ADMIN' && profile.logoUrl && (
                        <div className="flex flex-col gap-1.5 bg-neutral-50/50 rounded-xl p-5 border border-gray-100">
                          <span className="text-[10px] font-bold text-neutral-500 uppercase tracking-wider block">
                            Hospital Logo
                          </span>
                          <div className="w-24 h-24 border rounded-lg bg-white flex items-center justify-center p-2 overflow-hidden shadow-inner mt-1">
                            <img
                              src={profile.logoUrl}
                              alt="Logo"
                              className="max-w-full max-h-full object-contain"
                            />
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })()
              ) : (
                /* Edit Mode (Forms) */
                <form onSubmit={handleSave} className="space-y-6">
                  <div className="space-y-4">
                    <h5 className="text-xs font-bold uppercase tracking-wider text-neutral-400 border-b border-gray-100 pb-2">
                      Profile Information
                    </h5>

                    {/* Row 1: Name & Phone */}
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div>
                        <label
                          htmlFor="fld-10019"
                          className="block text-xs font-bold text-gray-700 mb-1.5"
                        >
                          Full Name <span className="text-red-500">*</span>
                        </label>
                        <input
                          id="fld-10019"
                          type="text"
                          value={profile.name}
                          onChange={(e) => handleInputChange('name', e.target.value)}
                          className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm ${formErrors.name ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                          placeholder="Your full name"
                          required
                        />
                        {formErrors.name && (
                          <p className="text-xs text-red-500 mt-1">{formErrors.name}</p>
                        )}
                      </div>

                      {profile.role !== 'SUPER_ADMIN' ? (
                        <div>
                          <label
                            htmlFor="fld-10018"
                            className="block text-xs font-bold text-gray-700 mb-1.5"
                          >
                            Phone Number <span className="text-red-500">*</span>
                          </label>
                          <input
                            id="fld-10018"
                            type="tel"
                            value={profile.phone}
                            onChange={(e) => handleInputChange('phone', e.target.value)}
                            className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm ${formErrors.phone ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                            placeholder="e.g. +1 555-0199"
                            required
                          />
                          {formErrors.phone && (
                            <p className="text-xs text-red-500 mt-1">{formErrors.phone}</p>
                          )}
                        </div>
                      ) : (
                        <div>
                          <label
                            htmlFor="fld-32"
                            className="block text-xs font-bold text-gray-400 mb-1.5"
                          >
                            Phone Number
                          </label>
                          <input
                            id="fld-32"
                            type="text"
                            value="Not Required"
                            disabled
                            className="w-full px-3 py-2 border border-gray-200 rounded-lg bg-gray-50 text-gray-400 text-sm cursor-not-allowed"
                          />
                        </div>
                      )}
                    </div>

                    {/* Row 2: Detailed Attributes per Role */}
                    {profile.role !== 'SUPER_ADMIN' && profile.role !== 'DOCTOR' && (
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                          <label
                            htmlFor="fld-31"
                            className="block text-xs font-bold text-gray-700 mb-1.5"
                          >
                            Age
                          </label>
                          <input
                            id="fld-31"
                            type="number"
                            value={profile.age}
                            onChange={(e) => handleInputChange('age', e.target.value)}
                            className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm ${formErrors.age ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                            placeholder="e.g. 35"
                          />
                          {formErrors.age && (
                            <p className="text-xs text-red-500 mt-1">{formErrors.age}</p>
                          )}
                        </div>

                        <div>
                          <label
                            htmlFor="fld-30"
                            className="block text-xs font-bold text-gray-700 mb-1.5"
                          >
                            Gender
                          </label>
                          <select
                            id="fld-30"
                            value={profile.gender}
                            onChange={(e) => handleInputChange('gender', e.target.value)}
                            className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm"
                          >
                            <option value="">Select Gender</option>
                            <option value="MALE">Male</option>
                            <option value="FEMALE">Female</option>
                            <option value="OTHER">Other</option>
                          </select>
                        </div>
                      </div>
                    )}

                    {/* Row 3: Specialization for Doctor ONLY */}
                    {(profile.role === 'DOCTOR' ||
                      (profile.role === 'HOSPITAL_ADMIN' &&
                        authService.getCurrentUser()?.isSingleDoctor)) && (
                      <div>
                        <label
                          htmlFor="fld-10017"
                          className="block text-xs font-bold text-gray-700 mb-1.5"
                        >
                          Specialization <span className="text-red-500">*</span>
                        </label>
                        <input
                          id="fld-10017"
                          type="text"
                          value={profile.specialization}
                          onChange={(e) => handleInputChange('specialization', e.target.value)}
                          className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm ${formErrors.specialization ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                          placeholder="Cardiologist, General Physician, etc."
                          required
                        />
                        {formErrors.specialization && (
                          <p className="text-xs text-red-500 mt-1">{formErrors.specialization}</p>
                        )}
                      </div>
                    )}

                    {/* Hospital details section for HOSPITAL_ADMIN ONLY */}
                    {profile.role === 'HOSPITAL_ADMIN' && (
                      <div className="space-y-4 pt-4 border-t border-gray-100">
                        <h5 className="text-xs font-bold uppercase tracking-wider text-neutral-400 border-b border-gray-100 pb-2">
                          Hospital Details & Branding
                        </h5>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          <div>
                            <label
                              htmlFor="fld-10016"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              Hospital Name
                            </label>
                            <input
                              id="fld-10016"
                              type="text"
                              value={profile.hospitalName}
                              onChange={(e) => handleInputChange('hospitalName', e.target.value)}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white"
                              placeholder="Hospital Name"
                            />
                          </div>
                          <div>
                            <label
                              htmlFor="fld-10015"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              Hospital Phone
                            </label>
                            <input
                              id="fld-10015"
                              type="text"
                              value={profile.hospitalPhone}
                              onChange={(e) => handleInputChange('hospitalPhone', e.target.value)}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white"
                              placeholder="Hospital contact number"
                            />
                          </div>
                        </div>

                        <div>
                          <label
                            htmlFor="fld-10014"
                            className="block text-xs font-bold text-gray-700 mb-1.5"
                          >
                            Hospital Address
                          </label>
                          <input
                            id="fld-10014"
                            type="text"
                            value={profile.hospitalAddress}
                            onChange={(e) => handleInputChange('hospitalAddress', e.target.value)}
                            className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white"
                            placeholder="Hospital full address"
                          />
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          <div>
                            <label
                              htmlFor="fld-10013"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              Parent Organization / Trust
                            </label>
                            <input
                              id="fld-10013"
                              type="text"
                              value={profile.parentOrganization}
                              onChange={(e) =>
                                handleInputChange('parentOrganization', e.target.value)
                              }
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white"
                              placeholder="e.g. Geeta Medical Foundation's"
                            />
                          </div>
                          <div>
                            <label
                              htmlFor="fld-10012"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              Hospital Logo (via Cloudinary)
                            </label>
                            <div className="flex items-center gap-2">
                              <input
                                id="fld-10012"
                                type="file"
                                accept="image/*"
                                onChange={handleLogoUpload}
                                disabled={uploadingLogo}
                                className="w-full text-xs text-gray-500 file:mr-4 file:py-1.5 file:px-3 file:rounded-md file:border-0 file:text-xs file:font-semibold file:bg-neutral-900 file:text-white hover:file:bg-neutral-800 cursor-pointer disabled:opacity-50"
                              />
                              {uploadingLogo && (
                                <svg
                                  className="animate-spin h-4 w-4 text-neutral-800 shrink-0"
                                  xmlns="http://www.w3.org/2000/svg"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                >
                                  <circle
                                    className="opacity-25"
                                    cx="12"
                                    cy="12"
                                    r="10"
                                    stroke="currentColor"
                                    strokeWidth="4"
                                  ></circle>
                                  <path
                                    className="opacity-75"
                                    fill="currentColor"
                                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                                  ></path>
                                </svg>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Logo preview */}
                        {profile.logoUrl && (
                          <div className="mt-2 flex items-center gap-3">
                            <div className="w-16 h-16 border rounded-lg overflow-hidden flex items-center justify-center bg-gray-50">
                              <img
                                src={profile.logoUrl}
                                alt="Hospital Logo"
                                className="max-w-full max-h-full object-contain"
                              />
                            </div>
                            <button
                              type="button"
                              onClick={() => handleInputChange('logoUrl', '')}
                              className="text-xs text-red-500 font-bold hover:text-red-700"
                            >
                              Remove Logo
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>

                  {/* Credentials Section / Password Toggle */}
                  <div className="space-y-4 pt-2 border-t border-gray-100">
                    <div className="flex items-center justify-between">
                      <h5 className="text-xs font-bold uppercase tracking-wider text-neutral-400">
                        Security Credentials
                      </h5>
                      <button
                        type="button"
                        onClick={() => setChangePassword(!changePassword)}
                        className="text-xs font-bold text-neutral-800 hover:text-gray-900 flex items-center gap-1 transition-colors"
                      >
                        {changePassword ? 'Cancel Change' : 'Change Password'}
                        <svg
                          className={`w-3.5 h-3.5 transform transition-transform duration-200 ${changePassword ? 'rotate-180' : ''}`}
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2.5}
                            d="M19 9l-7 7-7-7"
                          />
                        </svg>
                      </button>
                    </div>

                    {changePassword && (
                      <div className="bg-neutral-50 border border-gray-100 rounded-xl p-4 space-y-4 animate-slideDown">
                        <div>
                          <label
                            htmlFor="fld-10011"
                            className="block text-xs font-bold text-gray-700 mb-1.5"
                          >
                            Current Password <span className="text-red-500">*</span>
                          </label>
                          <input
                            id="fld-10011"
                            type="password"
                            value={passwords.currentPassword}
                            onChange={(e) =>
                              handlePasswordChange('currentPassword', e.target.value)
                            }
                            className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white ${formErrors.currentPassword ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                            placeholder="Verify current password"
                            required={changePassword}
                          />
                          {formErrors.currentPassword && (
                            <p className="text-xs text-red-500 mt-1">
                              {formErrors.currentPassword}
                            </p>
                          )}
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          <div>
                            <label
                              htmlFor="fld-10010"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              New Password <span className="text-red-500">*</span>
                            </label>
                            <input
                              id="fld-10010"
                              type="password"
                              value={passwords.newPassword}
                              onChange={(e) => handlePasswordChange('newPassword', e.target.value)}
                              className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white ${formErrors.newPassword ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                              placeholder="Minimum 6 characters"
                              required={changePassword}
                            />
                            {formErrors.newPassword && (
                              <p className="text-xs text-red-500 mt-1">{formErrors.newPassword}</p>
                            )}
                          </div>

                          <div>
                            <label
                              htmlFor="fld-10009"
                              className="block text-xs font-bold text-gray-700 mb-1.5"
                            >
                              Confirm Password <span className="text-red-500">*</span>
                            </label>
                            <input
                              id="fld-10009"
                              type="password"
                              value={passwords.confirmPassword}
                              onChange={(e) =>
                                handlePasswordChange('confirmPassword', e.target.value)
                              }
                              className={`w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-neutral-800 text-sm bg-white ${formErrors.confirmPassword ? 'border-red-500 ring-2 ring-red-100' : 'border-gray-300'}`}
                              placeholder="Repeat new password"
                              required={changePassword}
                            />
                            {formErrors.confirmPassword && (
                              <p className="text-xs text-red-500 mt-1">
                                {formErrors.confirmPassword}
                              </p>
                            )}
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </form>
              )}
            </div>

            {/* Modal Footer Actions */}
            <div className="px-6 py-4 border-t border-gray-100 bg-neutral-50 flex items-center justify-end gap-3 shrink-0">
              {!isEditing ? (
                <>
                  <button
                    type="button"
                    onClick={onClose}
                    className="px-4 py-2 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 hover:bg-neutral-100 transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-neutral-800"
                  >
                    Close
                  </button>
                  <button
                    type="button"
                    onClick={() => setIsEditing(true)}
                    className="px-5 py-2 bg-neutral-900 text-white rounded-lg text-sm font-semibold hover:bg-neutral-800 transition-colors shadow-soft focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-neutral-900 flex items-center gap-2"
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                      />
                    </svg>
                    Edit Profile
                  </button>
                </>
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      setIsEditing(false);
                      setChangePassword(false);
                      setFormErrors({});
                      fetchLatestProfile();
                    }}
                    disabled={saving}
                    className="px-4 py-2 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 hover:bg-neutral-100 transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-neutral-800"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={handleSave}
                    disabled={saving}
                    className="px-5 py-2 bg-neutral-900 text-white rounded-lg text-sm font-semibold hover:bg-neutral-800 transition-colors shadow-soft focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-neutral-900 flex items-center gap-2"
                  >
                    {saving && (
                      <svg
                        className="animate-spin h-4 w-4 text-white"
                        xmlns="http://www.w3.org/2000/svg"
                        fill="none"
                        viewBox="0 0 24 24"
                      >
                        <circle
                          className="opacity-25"
                          cx="12"
                          cy="12"
                          r="10"
                          stroke="currentColor"
                          strokeWidth="4"
                        ></circle>
                        <path
                          className="opacity-75"
                          fill="currentColor"
                          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                        ></path>
                      </svg>
                    )}
                    {saving ? 'Saving...' : 'Save Settings'}
                  </button>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ProfileModal;
