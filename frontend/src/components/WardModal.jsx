import React, { useEffect, useState } from 'react';
import { useToast } from '../context/ToastContext';
import authService from '../services/authService';
import icuService from '../services/icuService';
import WardService from '../services/wardService';
import Button from './Button';

const WardModal = ({ open, initial, onClose, onSaved }) => {
  const { error: toastError } = useToast();
  const [wardName, setWardName] = useState('');
  const [bedPrice, setBedPrice] = useState('');
  const [totalBeds, setTotalBeds] = useState('');
  const [floorNumber, setFloorNumber] = useState('');
  const [unitType, setUnitType] = useState('GENERAL');
  const [unitTypes, setUnitTypes] = useState([]);
  const [unitTypesError, setUnitTypesError] = useState('');
  const [saving, setSaving] = useState(false);

  // Classification is only meaningful for a tenant on the ICU plan; without it the ward keeps
  // its GENERAL default and the field is simply absent.
  const hasIcu = (authService.getCurrentUser()?.modules || []).includes('ICU');

  useEffect(() => {
    if (!open || !hasIcu || unitTypes.length > 0) return;
    setUnitTypesError('');
    icuService
      .getUnitTypes()
      .then((types) => {
        setUnitTypes(Array.isArray(types) ? types : []);
        if (!Array.isArray(types) || types.length === 0) {
          setUnitTypesError('No unit types were returned.');
        }
      })
      .catch((e) => {
        // G3: never silently drop the control. A failure here used to make the whole field
        // vanish, which reads as "this hospital has no ICU classification" rather than
        // "the list could not be loaded" — two very different things for an administrator.
        setUnitTypes([]);
        setUnitTypesError(
          e?.response?.data?.error || 'Could not load the ICU unit types. Please try again.'
        );
      });
  }, [open, hasIcu, unitTypes.length]);

  useEffect(() => {
    if (initial) {
      setWardName(initial.wardName || '');
      setBedPrice(initial.bedPrice ?? '');
      setTotalBeds(initial.totalBeds ?? '');
      setFloorNumber(initial.floorNumber ?? '');
      setUnitType(initial.unitType || 'GENERAL');
    } else {
      setWardName('');
      setBedPrice('');
      setTotalBeds('');
      setFloorNumber('');
      setUnitType('GENERAL');
    }
  }, [initial, open]);

  if (!open) return null;

  const onSubmit = async (e) => {
    e.preventDefault();
    // basic client-side validation
    if (!wardName || wardName.trim() === '') {
      toastError('Please enter ward name');
      return;
    }
    if (!bedPrice || Number.isNaN(Number(bedPrice))) {
      toastError('Enter valid bed price');
      return;
    }
    if (totalBeds !== '' && (Number.isNaN(Number(totalBeds)) || Number(totalBeds) < 0)) {
      toastError('Total beds must be 0 or more');
      return;
    }

    setSaving(true);
    try {
      if (initial && initial.wardId) {
        const payload = {
          wardName,
          bedPrice: Number(bedPrice),
          floorNumber: floorNumber ? Number(floorNumber) : null,
          // Bed count is editable on edit too — the backend adds/removes beds to match.
          totalBeds: totalBeds === '' ? null : Number(totalBeds),
          // Only send a classification we actually loaded. Sending one from a failed fetch
          // would overwrite the ward's real type with a guess; omitting it leaves it untouched.
          ...(hasIcu && unitTypes.length > 0 ? { unitType } : {}),
        };
        await WardService.updateWard(initial.wardId, payload);
      } else {
        const payload = {
          wardName,
          bedPrice: Number(bedPrice),
          totalBeds: totalBeds ? Number(totalBeds) : 0,
          floorNumber: floorNumber ? Number(floorNumber) : null,
          ...(hasIcu && unitTypes.length > 0 ? { unitType } : {}),
        };
        await WardService.createWard(payload);
      }
      onSaved && onSaved();
      onClose && onClose();
    } catch (err) {
      console.error(err);
      toastError(err.response?.data?.error || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex">
      <button type="button" aria-label="Close" className="flex-1 bg-black/40" onClick={onClose} />
      <div className="w-96 max-w-full bg-white p-4 shadow-xl h-full overflow-y-auto">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold">{initial ? 'Edit Ward' : 'Create Ward'}</h3>
          <button onClick={onClose} className="text-slate-500">
            Close
          </button>
        </div>

        <form onSubmit={onSubmit} className="space-y-3">
          <div>
            <label htmlFor="fld-38" className="block text-sm text-slate-600">
              Ward Name
            </label>
            <input
              id="fld-38"
              value={wardName}
              onChange={(e) => setWardName(e.target.value)}
              className="mt-1 w-full p-2 border rounded"
            />
          </div>

          <div>
            <label htmlFor="fld-37" className="block text-sm text-slate-600">
              Bed Price
            </label>
            <input
              id="fld-37"
              value={bedPrice}
              onChange={(e) => setBedPrice(e.target.value)}
              type="number"
              step="0.01"
              className="mt-1 w-full p-2 border rounded"
            />
          </div>

          <div>
            <label htmlFor="fld-36" className="block text-sm text-slate-600">
              Total Beds
            </label>
            <input
              id="fld-36"
              value={totalBeds}
              onChange={(e) => setTotalBeds(e.target.value)}
              type="number"
              min="0"
              className="mt-1 w-full p-2 border rounded"
            />
            {initial && (
              <p className="mt-1 text-xs text-slate-500">
                Increasing adds new beds. Decreasing removes free beds only — occupied beds are
                never deleted.
              </p>
            )}
          </div>

          <div>
            <label htmlFor="fld-35" className="block text-sm text-slate-600">
              Floor Number
            </label>
            <input
              id="fld-35"
              value={floorNumber}
              onChange={(e) => setFloorNumber(e.target.value)}
              type="number"
              className="mt-1 w-full p-2 border rounded"
            />
          </div>

          {hasIcu && (
            <div>
              <label htmlFor="fld-ward-unit-type" className="block text-sm text-slate-600">
                Unit Type
              </label>
              <select
                id="fld-ward-unit-type"
                value={unitType}
                onChange={(e) => setUnitType(e.target.value)}
                disabled={unitTypes.length === 0}
                aria-describedby="fld-ward-unit-type-help"
                className={`mt-1 w-full p-2 border rounded ${
                  unitTypesError
                    ? 'border-red-300 bg-red-50 text-red-700'
                    : 'disabled:bg-slate-100 disabled:text-slate-400'
                }`}
              >
                {unitTypes.length === 0 ? (
                  <option value="">Unavailable</option>
                ) : (
                  unitTypes.map((t) => (
                    <option key={t.key} value={t.key}>
                      {t.label}
                    </option>
                  ))
                )}
              </select>
              {unitTypesError ? (
                <p id="fld-ward-unit-type-help" className="mt-1 text-xs text-red-600">
                  {unitTypesError} The ward will keep its current classification.
                </p>
              ) : (
                <p id="fld-ward-unit-type-help" className="mt-1 text-xs text-slate-500">
                  Critical care units appear on the ICU dashboard and bed board. A ward with an
                  occupied bed cannot be reclassified — move or discharge its patients first.
                </p>
              )}
            </div>
          )}

          <div className="flex justify-end gap-2 mt-4">
            <Button variant="outline" onClick={onClose} type="button">
              Cancel
            </Button>
            <Button type="submit" disabled={saving}>
              {saving ? 'Saving...' : 'Save'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default WardModal;
