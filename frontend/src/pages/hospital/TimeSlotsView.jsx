import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import timeSlotService from '../../services/timeSlotService';
import { backdropProps } from '../../utils/modalA11y';

/**
 * TimeSlotsView - Nursing Management Phase B1: Shift Templates + Appointment Slots.
 * Self-contained admin screen: owns its own sub-tabs, data loading, and modals.
 */

// Strip seconds from "HH:mm:ss" -> "HH:mm" for display / <input type="time"> value.
const toHm = (t) => (t ? t.slice(0, 5) : '');

const SUB_TABS = [
  { id: 'shifts', label: 'Shift Templates' },
  { id: 'slots', label: 'Appointment Slots' },
];

const StatusBadge = ({ isActive }) => (
  <span
    className={`px-2 py-1 rounded text-xs font-bold ${
      isActive
        ? 'bg-green-50 text-green-700 border border-green-100'
        : 'bg-amber-50 text-amber-700 border border-amber-100'
    }`}
  >
    {isActive ? 'Active' : 'Inactive'}
  </span>
);

const TimeSlotModal = ({ open, mode, kind, initial, onClose, onSave, isSubmitting }) => {
  const [form, setForm] = useState({ name: '', startTime: '', endTime: '' });
  const [formError, setFormError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (mode === 'edit' && initial) {
      setForm({
        name: initial.name || '',
        startTime: toHm(initial.startTime),
        endTime: toHm(initial.endTime),
      });
    } else {
      setForm({ name: '', startTime: '', endTime: '' });
    }
    setFormError('');
  }, [open, mode, initial]);

  if (!open) return null;

  const isShift = kind === 'shifts';
  const title =
    mode === 'edit'
      ? isShift
        ? 'Edit Shift Template'
        : 'Edit Appointment Slot'
      : isShift
        ? 'Add Shift Template'
        : 'Add Slot';

  const handleSubmit = (e) => {
    e.preventDefault();
    setFormError('');
    if (isShift && !form.name.trim()) {
      setFormError('Name is required');
      return;
    }
    if (!form.startTime || !form.endTime) {
      setFormError('Start and end time are required');
      return;
    }
    const payload = isShift
      ? { name: form.name.trim(), startTime: form.startTime, endTime: form.endTime }
      : { startTime: form.startTime, endTime: form.endTime };
    onSave(payload);
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl shadow-lg w-full max-w-md max-h-[90vh] overflow-auto p-6">
        <h3 className="text-2xl font-bold mb-4">{title}</h3>
        <form onSubmit={handleSubmit} className="space-y-4">
          {isShift && (
            <div>
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
                placeholder="Name (e.g. Morning Shift) *"
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all"
                disabled={isSubmitting}
              />
            </div>
          )}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="fld-142" className="block text-xs font-semibold text-gray-600 mb-1">
                Start Time
              </label>
              <input
                id="fld-142"
                type="time"
                value={form.startTime}
                onChange={(e) => setForm((p) => ({ ...p, startTime: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all"
                disabled={isSubmitting}
              />
            </div>
            <div>
              <label htmlFor="fld-141" className="block text-xs font-semibold text-gray-600 mb-1">
                End Time
              </label>
              <input
                id="fld-141"
                type="time"
                value={form.endTime}
                onChange={(e) => setForm((p) => ({ ...p, endTime: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all"
                disabled={isSubmitting}
              />
            </div>
          </div>
          <p className="text-xs text-gray-500">
            A night shift may end before it starts (e.g. 00:00–08:00).
          </p>
          {formError && <p className="text-xs text-red-600">{formError}</p>}

          <div className="flex justify-end space-x-3 mt-6 pt-4 border-t border-gray-100">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 border border-gray-300 rounded text-sm hover:bg-gray-50 transition-colors"
              disabled={isSubmitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-4 py-2 bg-gray-900 text-white rounded text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {isSubmitting ? 'Saving...' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

const TimeSlotsView = () => {
  const { success, error: toastError } = useToast();
  const [subTab, setSubTab] = useState('shifts');
  const [shiftTemplates, setShiftTemplates] = useState([]);
  const [appointmentSlots, setAppointmentSlots] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const [modalOpen, setModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState('create');
  const [selected, setSelected] = useState(null);

  const fetchShiftTemplates = useCallback(async () => {
    try {
      const data = await timeSlotService.listShiftTemplates(false);
      setShiftTemplates(data || []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load shift templates');
    }
  }, [toastError]);

  const fetchAppointmentSlots = useCallback(async () => {
    try {
      const data = await timeSlotService.listAppointmentSlots(false);
      setAppointmentSlots(data || []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load appointment slots');
    }
  }, [toastError]);

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      await Promise.all([fetchShiftTemplates(), fetchAppointmentSlots()]);
    } finally {
      setLoading(false);
    }
  }, [fetchShiftTemplates, fetchAppointmentSlots]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const openAdd = () => {
    setSelected(null);
    setModalMode('create');
    setModalOpen(true);
  };

  const openEdit = (row) => {
    setSelected(row);
    setModalMode('edit');
    setModalOpen(true);
  };

  const handleSave = async (payload) => {
    setIsSubmitting(true);
    try {
      if (subTab === 'shifts') {
        if (modalMode === 'create') {
          await timeSlotService.createShiftTemplate(payload);
          success('Shift template created');
        } else {
          await timeSlotService.updateShiftTemplate(selected.publicId, payload);
          success('Shift template updated');
        }
        await fetchShiftTemplates();
      } else {
        if (modalMode === 'create') {
          await timeSlotService.createAppointmentSlot(payload);
          success('Appointment slot created');
        } else {
          await timeSlotService.updateAppointmentSlot(selected.publicId, payload);
          success('Appointment slot updated');
        }
        await fetchAppointmentSlots();
      }
      setModalOpen(false);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleActivate = async (row) => {
    try {
      await timeSlotService.activateShiftTemplate(row.publicId);
      success('Shift template activated');
      await fetchShiftTemplates();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to activate');
    }
  };

  const handleDeactivate = async (row) => {
    try {
      if (subTab === 'shifts') {
        await timeSlotService.deactivateShiftTemplate(row.publicId);
        success('Shift template deactivated');
        await fetchShiftTemplates();
      } else {
        await timeSlotService.deactivateAppointmentSlot(row.publicId);
        success('Appointment slot deactivated');
        await fetchAppointmentSlots();
      }
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to deactivate');
    }
  };

  return (
    <div className="p-6 space-y-4">
      {/* Sub-tabs */}
      <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 w-fit">
        {SUB_TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => setSubTab(t.id)}
            className={`px-4 py-2 text-sm font-semibold rounded-md transition-all ${
              subTab === t.id
                ? 'bg-white text-gray-900 shadow-sm'
                : 'text-gray-500 hover:text-gray-800'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="flex justify-end">
        <button
          onClick={openAdd}
          className="px-5 py-2.5 bg-gray-900 text-white text-sm font-bold rounded-xl hover:bg-gray-800 transition-all shadow-sm flex items-center gap-2"
        >
          <span className="text-lg leading-none">+</span>
          {subTab === 'shifts' ? 'Add Shift Template' : 'Add Slot'}
        </button>
      </div>

      <div className="bg-white border border-gray-200 rounded-2xl overflow-hidden shadow-sm">
        {loading ? (
          <div className="p-8 text-center text-gray-500">Loading...</div>
        ) : subTab === 'shifts' ? (
          shiftTemplates.length === 0 ? (
            <div className="p-8 text-center text-gray-500">No shift templates found.</div>
          ) : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-gray-600">Name</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-600">Start</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-600">End</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-600">Status</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-600">Actions</th>
                </tr>
              </thead>
              <tbody>
                {shiftTemplates.map((row) => (
                  <tr key={row.publicId} className="border-b border-gray-100 last:border-0">
                    <td className="px-4 py-3 font-semibold text-gray-900">{row.name}</td>
                    <td className="px-4 py-3 text-gray-700">{toHm(row.startTime)}</td>
                    <td className="px-4 py-3 text-gray-700">{toHm(row.endTime)}</td>
                    <td className="px-4 py-3">
                      <StatusBadge isActive={row.isActive} />
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => openEdit(row)}
                          className="text-gray-600 hover:text-gray-900 font-medium"
                        >
                          Edit
                        </button>
                        {row.isActive ? (
                          <button
                            onClick={() => handleDeactivate(row)}
                            className="text-amber-600 hover:text-amber-700 font-medium"
                          >
                            Deactivate
                          </button>
                        ) : (
                          <button
                            onClick={() => handleActivate(row)}
                            className="text-green-600 hover:text-green-700 font-medium"
                          >
                            Activate
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        ) : appointmentSlots.length === 0 ? (
          <div className="p-8 text-center text-gray-500">No appointment slots found.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="px-4 py-3 text-left font-semibold text-gray-600">Start</th>
                <th className="px-4 py-3 text-left font-semibold text-gray-600">End</th>
                <th className="px-4 py-3 text-left font-semibold text-gray-600">Status</th>
                <th className="px-4 py-3 text-left font-semibold text-gray-600">Actions</th>
              </tr>
            </thead>
            <tbody>
              {appointmentSlots.map((row) => (
                <tr key={row.publicId} className="border-b border-gray-100 last:border-0">
                  <td className="px-4 py-3 text-gray-700">{toHm(row.startTime)}</td>
                  <td className="px-4 py-3 text-gray-700">{toHm(row.endTime)}</td>
                  <td className="px-4 py-3">
                    <StatusBadge isActive={row.isActive} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => openEdit(row)}
                        className="text-gray-600 hover:text-gray-900 font-medium"
                      >
                        Edit
                      </button>
                      {row.isActive && (
                        <button
                          onClick={() => handleDeactivate(row)}
                          className="text-amber-600 hover:text-amber-700 font-medium"
                        >
                          Deactivate
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <TimeSlotModal
        open={modalOpen}
        mode={modalMode}
        kind={subTab}
        initial={selected}
        onClose={() => setModalOpen(false)}
        onSave={handleSave}
        isSubmitting={isSubmitting}
      />
    </div>
  );
};

export default TimeSlotsView;
