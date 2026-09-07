import { useEffect, useState } from 'react';
import icuService from '../services/icuService';

/**
 * useEnabledScoreTypes - the severity scores this hospital uses, with their components and ranges
 * (ICU Phase 8, D-2).
 *
 * The component list travels with the type, so no screen holds one of its own. Unlike ICU-7's
 * ventilator catalogue the components are fixed — SOFA's six organ systems are standardised, and
 * a renamed one would no longer be comparable to anyone else's SOFA. What varies per hospital is
 * only which scores are switched on.
 *
 * `refreshKey` rises on a realtime REFRESH_DATA, so an administrator switching a score off while
 * a chart is open removes it from the entry form rather than leaving an input the server will
 * refuse.
 */
export default function useEnabledScoreTypes(refreshKey = 0) {
  const [types, setTypes] = useState(null); // null = not loaded yet

  useEffect(() => {
    let active = true;
    icuService
      .getEnabledScoreTypes()
      .then((t) => {
        if (active) setTypes(Array.isArray(t) ? t : []);
      })
      .catch(() => {
        if (active) setTypes([]);
      });
    return () => {
      active = false;
    };
  }, [refreshKey]);

  return { types: types || [], loaded: types !== null };
}
