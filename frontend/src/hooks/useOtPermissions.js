import { useEffect, useState } from 'react';
import authService from '../services/authService';
import otService from '../services/otService';

/**
 * useOtPermissions - the caller's effective OT permissions.
 *
 * OT components must render by capability ("can this user schedule?"), never by role
 * ("is this user a receptionist?"): a hospital can grant OT_SCHEDULE to its surgeons,
 * and no component should have to know that.
 *
 * Returns { can, permissions, loaded }. While loading, `can` reports false, so an
 * action never flashes into view before the server has said it is allowed.
 */
export default function useOtPermissions() {
  const [permissions, setPermissions] = useState([]);
  const [loaded, setLoaded] = useState(false);

  const hasOt = (authService.getCurrentUser()?.modules || []).includes('OT');

  useEffect(() => {
    if (!hasOt) {
      setLoaded(true);
      return;
    }
    let active = true;
    otService
      .getMyOtPermissions()
      .then((list) => {
        if (active) setPermissions(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (active) setPermissions([]);
      })
      .finally(() => {
        if (active) setLoaded(true);
      });
    return () => {
      active = false;
    };
  }, [hasOt]);

  return {
    permissions,
    loaded,
    can: (code) => permissions.includes(code),
  };
}
