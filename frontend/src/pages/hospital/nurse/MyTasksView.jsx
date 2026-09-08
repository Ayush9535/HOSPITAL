import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseService from '../../../services/nurseService';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * MyTasksView - lists tasks assigned to the logged-in nurse (Phase 1 Nurse module, M7).
 * Nurse can start PENDING tasks and complete IN_PROGRESS tasks with optional remarks.
 */
const MyTasksView = ({ refreshKey }) => {
  const { success, error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [tasks, setTasks] = useState([]);
  const [submittingId, setSubmittingId] = useState(null);
  const [completionModal, setCompletionModal] = useState({ isOpen: false, taskPublicId: null });
  const [completionRemarks, setCompletionRemarks] = useState('');

  const loadTasks = useCallback(async () => {
    setLoading(true);
    try {
      const data = await nurseService.getMyTasks();
      setTasks(Array.isArray(data) ? data : []);
    } catch (err) {
      toastError('Failed to load assigned tasks');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    loadTasks();
  }, [loadTasks, refreshKey]);

  const handleStart = async (publicId) => {
    setSubmittingId(publicId);
    try {
      await nurseService.updateTaskStatus(publicId, { status: 'IN_PROGRESS' });
      success('Task started');
      loadTasks();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to start task';
      toastError(typeof msg === 'string' ? msg : 'Failed to start task');
    } finally {
      setSubmittingId(null);
    }
  };

  const handleOpenComplete = (publicId) => {
    setCompletionRemarks('');
    setCompletionModal({ isOpen: true, taskPublicId: publicId });
  };

  const handleComplete = async () => {
    const { taskPublicId } = completionModal;
    if (!taskPublicId) return;

    setSubmittingId(taskPublicId);
    try {
      await nurseService.updateTaskStatus(taskPublicId, {
        status: 'COMPLETED',
        completionRemarks: completionRemarks.trim() || null,
      });
      success('Task completed successfully');
      setCompletionModal({ isOpen: false, taskPublicId: null });
      loadTasks();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to complete task';
      toastError(typeof msg === 'string' ? msg : 'Failed to complete task');
    } finally {
      setSubmittingId(null);
    }
  };

  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';

  const getPriorityBadge = (p) => {
    switch (p) {
      case 'HIGH':
        return 'bg-red-50 text-red-700 border-red-100';
      case 'LOW':
        return 'bg-gray-50 text-gray-600 border-gray-100';
      case 'MEDIUM':
      default:
        return 'bg-blue-50 text-blue-700 border-blue-100';
    }
  };

  const getStatusColor = (s) => {
    switch (s) {
      case 'PENDING':
        return 'bg-yellow-100 text-yellow-800';
      case 'IN_PROGRESS':
        return 'bg-blue-100 text-blue-800';
      case 'COMPLETED':
        return 'bg-green-100 text-green-800';
      case 'CANCELLED':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="space-y-6">
      <div className="bg-white border border-gray-200 rounded-xl p-5">
        <h3 className="font-bold text-gray-800 text-sm mb-1">My Assigned Tasks</h3>
        <p className="text-xs text-gray-500">
          Track and update the status of your patient care tasks.
        </p>
      </div>

      {tasks.length === 0 ? (
        <div className="bg-white border border-gray-200 rounded-xl p-10 text-center text-gray-500">
          No tasks assigned to you.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {tasks.map((task) => (
            <div
              key={task.publicId || task.id}
              className="bg-white border border-gray-200 rounded-xl p-5 shadow-sm flex flex-col justify-between"
            >
              <div className="space-y-3">
                <div className="flex items-start justify-between">
                  <h4 className="font-bold text-gray-900 text-sm line-clamp-1" title={task.title}>
                    {task.title}
                  </h4>
                  <div className="flex items-center gap-1.5">
                    <span
                      className={`px-2 py-0.5 text-xs font-semibold rounded border ${getPriorityBadge(task.priority)}`}
                    >
                      {task.priority}
                    </span>
                    <span
                      className={`px-2 py-0.5 text-xs font-semibold rounded-full ${getStatusColor(task.status)}`}
                    >
                      {task.status}
                    </span>
                  </div>
                </div>

                {task.description && (
                  <p className="text-xs text-gray-600 line-clamp-3 bg-gray-50 p-2.5 rounded border border-gray-100 italic">
                    {task.description}
                  </p>
                )}

                <div className="text-xxs text-gray-400 space-y-1">
                  <div>
                    Created:{' '}
                    <span className="font-medium text-gray-600">{fmt(task.createdAt)}</span>
                  </div>
                  {task.dueDate && (
                    <div>
                      Due: <span className="font-medium text-red-600">{fmt(task.dueDate)}</span>
                    </div>
                  )}
                  {task.completedAt && (
                    <div>
                      Completed:{' '}
                      <span className="font-medium text-green-600">{fmt(task.completedAt)}</span>
                    </div>
                  )}
                  {task.completionRemarks && (
                    <div className="text-gray-500 italic mt-1 font-normal bg-green-50/50 p-1.5 rounded border border-green-100/50">
                      Remarks: &quot;{task.completionRemarks}&quot;
                    </div>
                  )}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="mt-4 pt-3 border-t border-gray-100 flex justify-end">
                {task.status === 'PENDING' && (
                  <button
                    onClick={() => handleStart(task.publicId)}
                    disabled={submittingId === task.publicId}
                    className="px-3.5 py-1.5 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800 disabled:bg-gray-400"
                  >
                    {submittingId === task.publicId ? 'Starting…' : 'Start Task'}
                  </button>
                )}
                {task.status === 'IN_PROGRESS' && (
                  <button
                    onClick={() => handleOpenComplete(task.publicId)}
                    disabled={submittingId === task.publicId}
                    className="px-3.5 py-1.5 text-xs font-semibold rounded-lg bg-green-600 text-white hover:bg-green-700 disabled:bg-gray-400"
                  >
                    {submittingId === task.publicId ? 'Completing…' : 'Complete Task'}
                  </button>
                )}
                {task.status === 'COMPLETED' && (
                  <span className="text-xs font-semibold text-green-600 flex items-center gap-1">
                    ✓ Completed
                  </span>
                )}
                {task.status === 'CANCELLED' && (
                  <span className="text-xs font-semibold text-red-600">✗ Cancelled</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Complete Task remarks Modal */}
      {completionModal.isOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
          {...backdropProps(() => {
            setCompletionModal({ isOpen: false, taskPublicId: null });
          })}
        >
          <div className="bg-white rounded-lg border border-gray-200 w-full max-w-sm">
            <div className="p-6">
              <h3 className="text-lg font-bold text-gray-900 mb-3">Complete Task</h3>
              <div>
                <label htmlFor="fld-170" className="block text-xs font-medium text-gray-600 mb-1">
                  Completion Remarks (optional)
                </label>
                <textarea
                  id="fld-170"
                  value={completionRemarks}
                  onChange={(e) => setCompletionRemarks(e.target.value)}
                  placeholder="Enter any notes about how the task was completed..."
                  rows={3}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
              <div className="mt-5 flex justify-end gap-3">
                <button
                  onClick={() => setCompletionModal({ isOpen: false, taskPublicId: null })}
                  className="px-3.5 py-1.5 text-xs font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
                >
                  Cancel
                </button>
                <button
                  onClick={handleComplete}
                  className="px-3.5 py-1.5 text-xs font-semibold rounded-lg bg-green-600 text-white hover:bg-green-700"
                >
                  Mark Complete
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default MyTasksView;
