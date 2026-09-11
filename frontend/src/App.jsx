import { lazy, Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import PageMeta from './components/PageMeta';
import { ToastProvider } from './context/ToastContext';
import authService from './services/authService';

// Code-split all large dashboard components to reduce initial bundle size (BUG-023)
const PlatformLogin = lazy(() => import('./pages/platform/PlatformLogin'));
const PlatformDashboard = lazy(() => import('./pages/platform/PlatformDashboard'));
const HospitalLogin = lazy(() => import('./pages/hospital/HospitalLogin'));
const HospitalAdminDashboard = lazy(() => import('./pages/hospital/HospitalAdminDashboard'));
const DoctorDashboard = lazy(() => import('./pages/hospital/DoctorDashboard'));
const ReceptionistDashboard = lazy(() => import('./pages/hospital/ReceptionistDashboard'));
const PharmacyDashboard = lazy(() => import('./pages/hospital/PharmacyDashboard'));
const NurseDashboard = lazy(() => import('./pages/hospital/NurseDashboard'));
const NurseInchargeDashboard = lazy(() => import('./pages/hospital/NurseInchargeDashboard'));
const OtInchargeDashboard = lazy(() => import('./pages/hospital/OtInchargeDashboard'));
const IpdDetails = lazy(() => import('./pages/hospital/IpdDetails'));

// Minimal loading fallback shown while a lazy chunk is fetching
const PageLoading = () => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: '100vh',
      background: '#f9fafb',
    }}
  >
    <div style={{ textAlign: 'center' }}>
      <div
        style={{
          width: 40,
          height: 40,
          border: '3px solid #e5e7eb',
          borderTop: '3px solid #111827',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
          margin: '0 auto 12px',
        }}
      />
      <p style={{ color: '#6b7280', fontSize: 14, fontFamily: 'Inter, system-ui, sans-serif' }}>
        Loading…
      </p>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  </div>
);

const ProtectedRoute = ({ children, allowedRoles }) => {
  const user = authService.getCurrentUser();

  if (!user || !authService.isAuthenticated()) {
    // getLoginUrl falls back to the last-used portal, so an expired
    // clinic/pharmacy session returns to its own login page.
    return <Navigate to={authService.getLoginUrl()} replace />;
  }

  // In Single Doctor Hospital mode, the Hospital Admin role can access BOTH doctor and admin routes
  // In Standalone Pharmacy Mode, the Hospital Admin role can access BOTH pharmacist and admin routes
  const isAllowed =
    !allowedRoles ||
    allowedRoles.some((role) => {
      if (role === user.role) return true;
      if (role === 'DOCTOR' && user.role === 'HOSPITAL_ADMIN' && user.isSingleDoctor) return true;
      // Only the Single Pharmacist Admin (solo) doubles as the pharmacist.
      const isSoloPharmacistAdmin = user.modules?.includes('SINGLE_PHARMACIST_ADMIN');
      if (role === 'PHARMACIST' && user.role === 'HOSPITAL_ADMIN' && isSoloPharmacistAdmin)
        return true;
      return false;
    });

  if (allowedRoles && !isAllowed) {
    return <Navigate to="/" replace />;
  }

  return children;
};

const LandingRedirect = () => {
  const user = authService.getCurrentUser();
  if (!user) {
    return <Navigate to={authService.getLoginUrl()} replace />;
  }
  switch (user.role) {
    case 'SUPER_ADMIN':
      return <Navigate to="/platform/dashboard" replace />;
    case 'HOSPITAL_ADMIN': {
      // Only Single Pharmacist Admin (solo) gets the dual pharmacist/admin landing.
      const isSoloPharmacistAdmin = user.modules?.includes('SINGLE_PHARMACIST_ADMIN');
      // Pharmacy tenants use /pharmacy/* URLs; hospital/clinic keep /hospital/*.
      const isPharmacyTenant = user.hospitalType === 'PHARMACY';
      if (user.isSingleDoctor) {
        const preference = sessionStorage.getItem('activeDashboard');
        if (preference === 'admin') {
          return <Navigate to="/hospital/admin" replace />;
        }
        return <Navigate to="/hospital/doctor" replace />;
      } else if (isSoloPharmacistAdmin) {
        const preference = sessionStorage.getItem('activeDashboard');
        if (preference === 'admin') {
          return <Navigate to="/pharmacy/admin" replace />;
        }
        return <Navigate to="/pharmacy/pharmacy" replace />;
      }
      return <Navigate to={isPharmacyTenant ? '/pharmacy/admin' : '/hospital/admin'} replace />;
    }
    case 'DOCTOR':
      return <Navigate to="/hospital/doctor" replace />;
    case 'RECEPTIONIST':
      return <Navigate to="/hospital/receptionist" replace />;
    case 'PHARMACIST':
      // A pharmacy-tenant pharmacist uses /pharmacy/*; a hospital pharmacy-dept
      // pharmacist stays on /hospital/pharmacy.
      return (
        <Navigate
          to={user.hospitalType === 'PHARMACY' ? '/pharmacy/pharmacy' : '/hospital/pharmacy'}
          replace
        />
      );
    case 'NURSE':
      return <Navigate to="/hospital/nurse" replace />;
    case 'NURSE_INCHARGE':
      return <Navigate to="/hospital/nurse-incharge" replace />;
    // OT_INCHARGE previously fell through to the default below and was sent back to the login
    // page: a hospital could create the role, the backend granted it the full clinical OT set,
    // and the user had nowhere to land.
    case 'OT_INCHARGE':
      return <Navigate to="/hospital/ot-incharge" replace />;
    default:
      return <Navigate to={authService.getLoginUrl()} replace />;
  }
};

function App() {
  return (
    <ToastProvider>
      <Router>
        <Suspense fallback={<PageLoading />}>
          <Routes>
            {/* Public / Login Pages */}
            <Route
              path="/platform/login"
              element={
                <PageMeta title="HMS - Super Admin Login">
                  <PlatformLogin />
                </PageMeta>
              }
            />

            <Route path="/login" element={<Navigate to="/login/hospital" replace />} />

            <Route
              path="/login/hospital"
              element={
                <PageMeta title="HMS - Hospital Login">
                  <HospitalLogin portalType="HOSPITAL" />
                </PageMeta>
              }
            />

            <Route
              path="/login/clinic"
              element={
                <PageMeta title="HMS - Clinic Login">
                  <HospitalLogin portalType="CLINIC" />
                </PageMeta>
              }
            />

            <Route
              path="/login/pharmacy"
              element={
                <PageMeta title="HMS - Pharmacy Login">
                  <HospitalLogin portalType="PHARMACY" />
                </PageMeta>
              }
            />

            {/* DASHBOARDS — PROTECTED */}
            <Route
              path="/platform/dashboard"
              element={
                <ProtectedRoute allowedRoles={['SUPER_ADMIN']}>
                  <PageMeta title="HMS - Platform Admin">
                    <PlatformDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/admin"
              element={
                <ProtectedRoute allowedRoles={['HOSPITAL_ADMIN']}>
                  <PageMeta title="HMS - Hospital Admin">
                    <HospitalAdminDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/doctor"
              element={
                <ProtectedRoute allowedRoles={['DOCTOR']}>
                  <PageMeta title="HMS - Doctor Portal">
                    <DoctorDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/receptionist"
              element={
                <ProtectedRoute allowedRoles={['RECEPTIONIST']}>
                  <PageMeta title="HMS - Reception">
                    <ReceptionistDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/pharmacy"
              element={
                <ProtectedRoute allowedRoles={['PHARMACIST', 'HOSPITAL_ADMIN']}>
                  <PageMeta title="HMS - Pharmacy">
                    <PharmacyDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            {/* PHARMACY TENANT — same dashboards under a /pharmacy/* namespace so the
                address bar reads "pharmacy" instead of "hospital". Hospital/clinic keep
                their /hospital/* routes above untouched. */}
            <Route
              path="/pharmacy/admin"
              element={
                <ProtectedRoute allowedRoles={['HOSPITAL_ADMIN']}>
                  <PageMeta title="HMS - Pharmacy Admin">
                    <HospitalAdminDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/pharmacy/pharmacy"
              element={
                <ProtectedRoute allowedRoles={['PHARMACIST', 'HOSPITAL_ADMIN']}>
                  <PageMeta title="HMS - Pharmacy">
                    <PharmacyDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/nurse"
              element={
                <ProtectedRoute allowedRoles={['NURSE']}>
                  <PageMeta title="HMS - Nurse">
                    <NurseDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/ot-incharge"
              element={
                <ProtectedRoute allowedRoles={['OT_INCHARGE']}>
                  <PageMeta title="HMS - Operation Theatre">
                    <OtInchargeDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/hospital/nurse-incharge"
              element={
                <ProtectedRoute allowedRoles={['NURSE_INCHARGE']}>
                  <PageMeta title="HMS - Nurse Incharge">
                    <NurseInchargeDashboard />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            <Route
              path="/ipd/:id"
              element={
                <ProtectedRoute allowedRoles={['RECEPTIONIST', 'DOCTOR', 'HOSPITAL_ADMIN']}>
                  <PageMeta title="HMS - IPD Details">
                    <IpdDetails />
                  </PageMeta>
                </ProtectedRoute>
              }
            />

            {/* Default landing */}
            <Route path="/" element={<LandingRedirect />} />

            {/* Catch-all */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </Router>
    </ToastProvider>
  );
}

export default App;
