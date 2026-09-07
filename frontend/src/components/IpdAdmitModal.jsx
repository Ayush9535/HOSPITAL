import React, { useEffect, useState } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import wardService from '../services/wardService';

const IpdAdmitModal = ({ isOpen, onClose, opd, onSuccess, initialDiagnosis }) => {
  const [wards, setWards] = useState([]);
  const [selectedWard, setSelectedWard] = useState(null);
  const [beds, setBeds] = useState([]);
  const [selectedBed, setSelectedBed] = useState(null);
  const [admissionType, setAdmissionType] = useState('ELECTIVE');
  const [primaryDiagnosis, setPrimaryDiagnosis] = useState(initialDiagnosis || opd?.problem || '');
  const [loading, setLoading] = useState(false);
  const { success, error: toastError } = useToast();

  useEffect(() => {
    if (!isOpen) return;
    const load = async () => {
      try {
        const w = await wardService.getWardsForAdmission();
        setWards(w || []);
      } catch (err) {
        console.error('Failed to load wards', err);
      }
    };
    load();
    setPrimaryDiagnosis(initialDiagnosis || opd?.problem || '');
  }, [isOpen, opd?.id, initialDiagnosis]);

  useEffect(() => {
    const loadBeds = async () => {
      if (!selectedWard) return;
      try {
        const b = await wardService.getAvailableBeds(selectedWard);
        setBeds(b || []);
      } catch (err) {
        console.error('Failed to load beds', err);
      }
    };
    loadBeds();
  }, [selectedWard]);

  // Auto-select ward if only one is available
  useEffect(() => {
    if (wards && wards.length === 1) {
      setSelectedWard(Number(wards[0].wardId));
    }
  }, [wards]);

  // Auto-select bed if only one is available
  useEffect(() => {
    if (beds && beds.length === 1) {
      setSelectedBed(Number(beds[0].bedId));
    }
  }, [beds]);

  if (!isOpen) return null;

  const handleSubmit = async () => {
    if (!opd || !selectedWard || !selectedBed) {
      toastError('Please select ward and bed');
      return;
    }
    setLoading(true);
    try {
      const payload = {
        opdId: opd.id,
        wardId: selectedWard,
        bedId: selectedBed,
        admissionType,
        primaryDiagnosis,
      };
      const res = await hospitalService.createIpdAdmission(payload);
      success('Patient admitted to IPD');
      onSuccess && onSuccess(res);
      onClose();
    } catch (err) {
      console.error('IPD admit failed', err);
      // The API returns { success:false, error:"..." }; toast/render the message string,
      // never the object (React can't render an object child — it was crashing the page).
      const data = err.response?.data;
      const msg =
        typeof data === 'string' ? data : data?.error || data?.message || 'Failed to admit patient';
      toastError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-lg w-full max-w-xl p-6 max-h-[90vh] overflow-y-auto">
        <div className="flex justify-between items-center mb-4">
          <h3 className="text-lg font-bold">Admit to IPD</h3>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            Close
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <span className="block text-sm font-medium text-gray-700 mb-1">Ward</span>
            {wards && wards.length === 1 ? (
              <div className="w-full px-3 py-2 bg-gray-50 border border-gray-200 text-gray-800 rounded text-sm font-semibold flex items-center justify-between">
                <span>{wards[0].wardName}</span>
                <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                  Assigned
                </span>
              </div>
            ) : (
              <select
                className="w-full border rounded px-3 py-2"
                value={selectedWard || ''}
                onChange={(e) => setSelectedWard(Number(e.target.value))}
              >
                <option value="">Select ward</option>
                {wards.map((w) => (
                  <option key={w.wardId} value={w.wardId}>
                    {w.wardName}
                  </option>
                ))}
              </select>
            )}
          </div>

          <div>
            <span className="block text-sm font-medium text-gray-700 mb-1">Bed (Available)</span>
            {beds && beds.length === 1 ? (
              <div className="w-full px-3 py-2 bg-gray-50 border border-gray-200 text-gray-805 rounded text-sm font-semibold flex items-center justify-between">
                <span>
                  {beds[0].bedCode} — {beds[0].status}
                </span>
                <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                  Assigned
                </span>
              </div>
            ) : (
              <select
                className="w-full border rounded px-3 py-2"
                value={selectedBed || ''}
                onChange={(e) => setSelectedBed(Number(e.target.value))}
              >
                <option value="">Select bed</option>
                {beds.map((b) => (
                  <option key={b.bedId} value={b.bedId}>
                    {b.bedCode} — {b.status}
                  </option>
                ))}
              </select>
            )}
          </div>

          <div>
            <label htmlFor="fld-13" className="block text-sm font-medium text-gray-700">
              Admission Type
            </label>
            <select
              id="fld-13"
              className="w-full border rounded px-3 py-2"
              value={admissionType}
              onChange={(e) => setAdmissionType(e.target.value)}
            >
              <option value="ELECTIVE">Elective</option>
              <option value="EMERGENCY">Emergency</option>
            </select>
          </div>

          <div>
            <label htmlFor="fld-12" className="block text-sm font-medium text-gray-700">
              Primary Diagnosis
            </label>
            <textarea
              id="fld-12"
              rows={3}
              value={primaryDiagnosis}
              onChange={(e) => setPrimaryDiagnosis(e.target.value)}
              className="w-full border rounded px-3 py-2"
            />
          </div>

          <div className="flex justify-end gap-3">
            <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded">
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={loading}
              className="px-4 py-2 bg-primary-600 text-white rounded"
            >
              {loading ? 'Admitting...' : 'Admit'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default IpdAdmitModal;
