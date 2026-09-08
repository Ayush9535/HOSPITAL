import { EyeIcon, EyeSlashIcon } from '@heroicons/react/24/outline';
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import authService from '../../services/authService';
import { validateForm } from '../../utils/validation';

const PORTAL_CONFIG = {
  HOSPITAL: {
    title: 'Hospital Staff Login',
    subtitle: 'Access your hospital portal',
    heroTitle: 'Hospital Management System',
    heroSubtitle: 'Comprehensive Patient Care & Management Platform',
    defaultRole: 'HOSPITAL_ADMIN',
    roles: [
      { value: 'HOSPITAL_ADMIN', label: 'Hospital Administration' },
      { value: 'DOCTOR', label: 'Doctor' },
      { value: 'RECEPTIONIST', label: 'Reception & Registration' },
      { value: 'PHARMACIST', label: 'Pharmacy Services' },
      { value: 'NURSE', label: 'Nurse' },
    ],
  },
  CLINIC: {
    title: 'Clinic Staff Login',
    subtitle: 'Access your clinic portal',
    heroTitle: 'Clinic Management System',
    heroSubtitle: 'Streamlined Outpatient & Clinical Care Platform',
    defaultRole: 'HOSPITAL_ADMIN',
    roles: [
      { value: 'HOSPITAL_ADMIN', label: 'Clinic Administration' },
      { value: 'DOCTOR', label: 'Doctor' },
      { value: 'RECEPTIONIST', label: 'Reception & Registration' },
      { value: 'PHARMACIST', label: 'Pharmacy Services' },
    ],
  },
  PHARMACY: {
    title: 'Pharmacy Login',
    subtitle: 'Access your pharmacy portal',
    heroTitle: 'Pharmacy Management System',
    heroSubtitle: 'Inventory, Billing & Dispensing Platform',
    defaultRole: 'HOSPITAL_ADMIN',
    roles: [
      { value: 'HOSPITAL_ADMIN', label: 'Pharmacy Administration' },
      { value: 'PHARMACIST', label: 'Pharmacist' },
    ],
  },
};

const HospitalLogin = ({ portalType = 'HOSPITAL' }) => {
  const config = PORTAL_CONFIG[portalType] || PORTAL_CONFIG.HOSPITAL;
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [selectedRole, setSelectedRole] = useState(config.defaultRole);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrors({});

    const validationErrors = validateForm(
      { email, password },
      {
        email: ['required', 'email'],
        password: ['required'],
      }
    );

    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    setLoading(true);

    try {
      const response = await authService.hospitalLogin(email, password, portalType);

      const isStandalonePharmacy =
        response.modules?.includes('PHARMACY') && !response.modules?.includes('OPD');
      const isValidRole =
        response.role === selectedRole ||
        (response.role === 'HOSPITAL_ADMIN' &&
          response.isSingleDoctor &&
          selectedRole === 'DOCTOR') ||
        (response.role === 'HOSPITAL_ADMIN' &&
          isStandalonePharmacy &&
          selectedRole === 'PHARMACIST') ||
        // The "Nurse" option covers both staff nurses and nurse incharges.
        (selectedRole === 'NURSE' && response.role === 'NURSE_INCHARGE');

      if (!isValidRole) {
        authService.logout();
        const roleLabel = config.roles.find((r) => r.value === selectedRole)?.label || selectedRole;
        setErrors({ submit: `Access Denied: You are not registered as ${roleLabel}.` });
        return;
      }

      if (response.role === 'HOSPITAL_ADMIN' && (response.isSingleDoctor || isStandalonePharmacy)) {
        sessionStorage.setItem(
          'activeDashboard',
          selectedRole === 'DOCTOR'
            ? 'doctor'
            : selectedRole === 'PHARMACIST'
              ? 'pharmacy'
              : 'admin'
        );
      }

      // Pharmacy tenants live under /pharmacy/*; hospital/clinic tenants keep /hospital/*.
      const isPharmacyTenant = response.hospitalType === 'PHARMACY';
      if (response.role === 'HOSPITAL_ADMIN') {
        if (response.isSingleDoctor && selectedRole === 'DOCTOR') {
          navigate('/hospital/doctor');
        } else if (isStandalonePharmacy && selectedRole === 'PHARMACIST') {
          navigate('/pharmacy/pharmacy');
        } else {
          navigate(isPharmacyTenant ? '/pharmacy/admin' : '/hospital/admin');
        }
      } else if (response.role === 'DOCTOR') {
        navigate('/hospital/doctor');
      } else if (response.role === 'RECEPTIONIST') {
        navigate('/hospital/receptionist');
      } else if (response.role === 'PHARMACIST') {
        navigate(isPharmacyTenant ? '/pharmacy/pharmacy' : '/hospital/pharmacy');
      } else if (response.role === 'NURSE') {
        navigate('/hospital/nurse');
      } else if (response.role === 'NURSE_INCHARGE') {
        navigate('/hospital/nurse-incharge');
      } else {
        setErrors({ submit: 'Invalid user role' });
      }
    } catch (err) {
      console.error('Login error:', err);
      let errorMessage = 'Login failed. Please check your credentials.';

      if (err.response?.data) {
        if (typeof err.response.data === 'string') {
          errorMessage = err.response.data;
        } else if (err.response.data.message) {
          errorMessage = err.response.data.message;
        } else if (err.response.data.error) {
          errorMessage = err.response.data.error;
        }
      } else if (err.message) {
        errorMessage = err.message;
      }

      setErrors({ submit: errorMessage });
    } finally {
      setLoading(false);
    }
  };

  const handleRoleChange = (e) => {
    setSelectedRole(e.target.value);
    setErrors({});
  };

  const handleEmailChange = (e) => {
    setEmail(e.target.value);
    if (errors.email) setErrors((prev) => ({ ...prev, email: null }));
  };

  const handlePasswordChange = (e) => {
    setPassword(e.target.value);
    if (errors.password) setErrors((prev) => ({ ...prev, password: null }));
  };

  return (
    <div className="min-h-screen bg-white flex">
      <div className="z-10 absolute top-0 left-5 space-y-10">
        <img src="/company-logo.png" alt="company-logo" className="z-20 w-24 h-12" />
      </div>

      {/* Left Side - Background Image */}
      <div className="hidden lg:flex lg:w-1/2 relative overflow-hidden">
        <div
          className="absolute inset-0 bg-cover bg-center bg-no-repeat"
          style={{ backgroundImage: 'url(/main-login-page.png)' }}
        ></div>
        <div className="absolute inset-0 bg-black bg-opacity-40 z-10"></div>
        <div className="relative z-10 flex flex-col justify-center items-center w-full px-16">
          <div className="mb-12">
            <div className="w-[60%] h-[20%] bg-gray-400 shadow-lg flex items-center justify-center mb-8 m-auto">
              <img src="/logo.png" alt="Logo" className="w-full h-full rounded-[30%]" />
            </div>
            <h1 className="text-4xl font-bold text-white text-center mb-4">{config.heroTitle}</h1>
            <p className="text-xl text-white text-center leading-relaxed opacity-90">
              {config.heroSubtitle}
            </p>
          </div>
        </div>
      </div>

      {/* Right Side - Login Form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-8 bg-gray-50">
        <div className="w-full max-w-md">
          {/* Mobile Logo */}
          <div className="lg:hidden text-center mb-8">
            <div className="inline-flex items-center justify-center w-16 h-16 bg-white border border-gray-200 mb-4">
              <svg
                className="w-8 h-8 text-gray-900"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
                />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-gray-900 mb-2">{config.title}</h1>
            <p className="text-gray-600 text-sm">{config.subtitle}</p>
          </div>

          {/* Login Card */}
          <div className="bg-white border border-gray-200 overflow-hidden">
            <div className="p-6 border-b border-gray-200 text-center">
              <h2 className="text-xl font-bold text-gray-900 mb-2">{config.title}</h2>
              <p className="text-gray-600">{config.subtitle}</p>
            </div>

            <div className="p-6">
              {errors.submit && (
                <div className="mb-6 bg-red-50 border border-red-300 p-4 rounded-lg">
                  <div className="flex items-start">
                    <span className="text-red-700 font-medium mr-3">Error:</span>
                    <p className="text-sm text-red-700">{errors.submit}</p>
                  </div>
                </div>
              )}

              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-2">
                  <label htmlFor="role" className="block text-sm font-medium text-gray-900">
                    Department Access
                  </label>
                  <select
                    id="role"
                    value={selectedRole}
                    onChange={handleRoleChange}
                    className="w-full h-11 px-4 py-2.5 text-sm bg-white border border-gray-300 rounded-lg text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  >
                    {config.roles.map((r) => (
                      <option key={r.value} value={r.value}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="space-y-2">
                  <label htmlFor="email" className="block text-sm font-medium text-gray-900">
                    Email Address
                  </label>
                  <input
                    type="email"
                    id="email"
                    value={email}
                    onChange={handleEmailChange}
                    placeholder="your.name@example.com"
                    disabled={loading}
                    className={`w-full h-11 px-4 py-2.5 text-sm bg-white border rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:opacity-60 disabled:cursor-not-allowed ${errors.email ? 'border-red-400 bg-red-50' : 'border-gray-300'}`}
                  />
                  {errors.email && <p className="text-red-600 text-sm mt-1">{errors.email}</p>}
                </div>

                <div className="space-y-2">
                  <label htmlFor="password" className="block text-sm font-medium text-gray-900">
                    Password
                  </label>
                  <div className="relative">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      id="password"
                      value={password}
                      onChange={handlePasswordChange}
                      placeholder="Enter your secure password"
                      disabled={loading}
                      className={`w-full h-11 px-4 py-2.5 pr-10 text-sm bg-white border rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:opacity-60 disabled:cursor-not-allowed ${errors.password ? 'border-red-400 bg-red-50' : 'border-gray-300'}`}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((prev) => !prev)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
                      tabIndex={-1}
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                    >
                      {showPassword ? (
                        <EyeSlashIcon className="w-4 h-4" />
                      ) : (
                        <EyeIcon className="w-4 h-4" />
                      )}
                    </button>
                  </div>
                  {errors.password && (
                    <p className="text-red-600 text-sm mt-1">{errors.password}</p>
                  )}
                </div>

                <div className="pt-4">
                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full h-11 bg-gray-900 hover:bg-gray-700 text-white text-sm font-semibold rounded-lg px-4 transition-colors duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
                  >
                    {loading ? (
                      <span className="flex items-center justify-center">
                        <div className="w-4 h-4 border-2 border-white border-t-transparent animate-spin mr-2"></div>
                        Authenticating...
                      </span>
                    ) : (
                      'Access Portal'
                    )}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default HospitalLogin;
