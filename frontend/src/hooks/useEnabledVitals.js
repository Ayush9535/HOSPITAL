import { useState, useEffect } from 'react';
import vitalsService from '../services/vitalsService';

/**
 * useEnabledVitals - the vitals this hospital has switched ON for OPD entry.
 * Returns `isOn(key)` for the six built-ins and `customs` (hospital-defined
 * vitals, free text, no validation). While loading, every built-in is treated as
 * on so the OPD form never flickers fields away.
 */
export default function useEnabledVitals() {
  const [vitals, setVitals] = useState(null); // null = not loaded yet

  useEffect(() => {
    let active = true;
    vitalsService
      .enabled()
      .then((list) => {
        if (active) setVitals(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (active) setVitals([]);
      });
    return () => {
      active = false;
    };
  }, []);

  const loaded = vitals !== null;
  const isOn = (key) => (!loaded ? true : vitals.some((v) => v.key === key && !v.isCustom));
  const customs = loaded ? vitals.filter((v) => v.isCustom) : [];

  return { isOn, customs, loaded };
}
