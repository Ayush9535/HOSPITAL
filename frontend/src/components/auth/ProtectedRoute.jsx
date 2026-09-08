import React from 'react';
import { Navigate } from 'react-router-dom';
import authService from '../../services/authService';

/**
 * ProtectedRoute - Route guard component
 *
 * This component protects routes by checking:
 * - If user is authenticated
 * - If user has the required role
 *
 * If not authenticated or doesn't have required role, redirects to appropriate login page.
 *
 * @param {Object} props - Component props
 * @param {React.Component} props.children - Child components to render if authorized
 * @param {Array<string>} props.allowedRoles - Array of allowed roles (optional)
 *
 * @author HMS Team
 * @version Phase-1
 */
const ProtectedRoute = ({ children, allowedRoles }) => {
  // Check if user is authenticated
  if (!authService.isAuthenticated()) {
    // Redirect to hospital login by default
    return <Navigate to="/login" replace />;
  }

  // If allowedRoles is specified, check if user has required role
  if (allowedRoles && allowedRoles.length > 0) {
    const user = authService.getCurrentUser();

    if (!user || !allowedRoles.includes(user.role)) {
      // User doesn't have required role
      // Redirect to appropriate page based on their actual role
      if (user.role === 'SUPER_ADMIN') {
        return <Navigate to="/platform/dashboard" replace />;
      } else if (user.role === 'HOSPITAL_ADMIN') {
        return <Navigate to="/hospital/admin" replace />;
      } else if (user.role === 'DOCTOR') {
        return <Navigate to="/hospital/doctor" replace />;
      } else {
        return <Navigate to="/login" replace />;
      }
    }
  }

  // User is authenticated and has required role
  return children;
};

export default ProtectedRoute;
