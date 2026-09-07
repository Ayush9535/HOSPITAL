import { Dialog, Transition } from '@headlessui/react';
import classNames from 'classnames';
import React, { Fragment, useEffect, useState } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';

const HistoryDrawer = ({ isOpen, onClose, entityType, entityId, entityName }) => {
  const [clinicalLogs, setClinicalLogs] = useState([]);
  const [auditLogs, setAuditLogs] = useState([]);
  const [loading, setLoading] = useState(false);
  const { error: toastError } = useToast();

  useEffect(() => {
    if (isOpen && entityType && entityId) {
      fetchHistory();
    }
  }, [isOpen, entityType, entityId]);

  const fetchHistory = async () => {
    setLoading(true);
    try {
      if (entityType === 'PATIENT') {
        const [appointments, audits] = await Promise.all([
          hospitalService.getAppointmentsByPatient(entityId).catch(() => []),
          hospitalService.getEntityHistory('PATIENT', entityId).catch(() => []),
        ]);
        setClinicalLogs(appointments);
        setAuditLogs(audits);
      } else {
        const data = await hospitalService.getEntityHistory(entityType, entityId);
        setAuditLogs(data);
        setClinicalLogs([]);
      }
    } catch (error) {
      console.error('Failed to load history', error);
      toastError(error.response?.data?.message || 'Failed to load history');
    } finally {
      setLoading(false);
    }
  };

  const getActionColor = (action) => {
    return 'bg-gray-100 text-gray-700';
  };

  return (
    <Transition.Root show={isOpen} as={Fragment}>
      <Dialog as="div" className="relative z-50" onClose={onClose}>
        <Transition.Child
          as={Fragment}
          enter="ease-in-out duration-500"
          enterFrom="opacity-0"
          enterTo="opacity-100"
          leave="ease-in-out duration-500"
          leaveFrom="opacity-100"
          leaveTo="opacity-0"
        >
          <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
        </Transition.Child>

        <div className="fixed inset-0 overflow-hidden">
          <div className="absolute inset-0 overflow-hidden">
            <div className="pointer-events-none fixed inset-y-0 right-0 flex max-w-full pl-10">
              <Transition.Child
                as={Fragment}
                enter="transform transition ease-in-out duration-500 sm:duration-700"
                enterFrom="translate-x-full"
                enterTo="translate-x-0"
                leave="transform transition ease-in-out duration-500 sm:duration-700"
                leaveFrom="translate-x-0"
                leaveTo="translate-x-full"
              >
                <Dialog.Panel className="pointer-events-auto w-screen max-w-md">
                  <div className="flex h-full flex-col overflow-y-scroll bg-white shadow-xl">
                    <div className="bg-gray-900 px-4 py-6 sm:px-6">
                      <div className="flex items-center justify-between">
                        <Dialog.Title className="text-base font-semibold leading-6 text-white">
                          History: {entityName || entityId}
                        </Dialog.Title>
                        <div className="ml-3 flex h-7 items-center">
                          <button
                            type="button"
                            className="relative rounded-md bg-gray-900 text-gray-400 hover:text-white focus:outline-none focus:ring-2 focus:ring-white"
                            onClick={onClose}
                          >
                            <span className="absolute -inset-2.5" />
                            <span className="sr-only">Close panel</span>
                            <svg
                              className="h-6 w-6"
                              fill="none"
                              viewBox="0 0 24 24"
                              strokeWidth="1.5"
                              stroke="currentColor"
                              aria-hidden="true"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                d="M6 18L18 6M6 6l12 12"
                              />
                            </svg>
                          </button>
                        </div>
                      </div>
                      <div className="mt-1">
                        <p className="text-sm text-gray-400">
                          {entityType === 'PATIENT'
                            ? 'Clinical & System History'
                            : `Audit trail for ${entityType?.toLowerCase()}`}
                        </p>
                      </div>
                    </div>
                    <div className="relative flex-1 px-4 py-6 sm:px-6">
                      {loading ? (
                        <div className="flex justify-center py-8">
                          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
                        </div>
                      ) : clinicalLogs.length === 0 && auditLogs.length === 0 ? (
                        <div className="text-center text-gray-500 py-8">No history found.</div>
                      ) : (
                        <div className="space-y-8">
                          {/* Clinical History Section */}
                          {entityType === 'PATIENT' && (
                            <div>
                              {clinicalLogs.length > 0 ? (
                                <>
                                  <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wide mb-3 sticky top-0 bg-white py-2 z-10">
                                    Clinical History
                                  </h4>
                                  <ul className="space-y-4 mb-8">
                                    {clinicalLogs.map((appt) => (
                                      <li
                                        key={appt.id || appt.publicId}
                                        className="bg-gray-50 rounded-lg p-4 border border-gray-200"
                                      >
                                        <div className="flex justify-between items-start">
                                          <div>
                                            <p className="font-bold text-gray-900 text-sm">
                                              {new Date(appt.appointmentDate).toLocaleDateString(
                                                undefined,
                                                {
                                                  weekday: 'short',
                                                  year: 'numeric',
                                                  month: 'short',
                                                  day: 'numeric',
                                                }
                                              )}
                                            </p>
                                            <p className="text-xs text-gray-600 mt-1">
                                              {appt.appointmentTime?.substring(0, 5)} • Dr.{' '}
                                              {appt.doctorName}
                                            </p>
                                          </div>
                                          <span className="px-2 py-0.5 text-xs font-bold rounded-full bg-gray-100 text-gray-700">
                                            {appt.status}
                                          </span>
                                        </div>
                                        {appt.notes && (
                                          <div className="mt-2 text-sm text-gray-700 bg-white p-2 rounded border border-gray-200 italic">
                                            &quot;{appt.notes}&quot;
                                          </div>
                                        )}
                                      </li>
                                    ))}
                                  </ul>
                                </>
                              ) : (
                                <div className="mb-8 p-4 bg-gray-50 rounded-lg text-center text-sm text-gray-500">
                                  No clinical appointments yet.
                                </div>
                              )}
                            </div>
                          )}

                          {/* Audit Logs Section */}
                          {auditLogs.length > 0 && (
                            <div>
                              {entityType === 'PATIENT' && (
                                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wide mb-3 sticky top-0 bg-white py-2 z-10">
                                  System Activity
                                </h4>
                              )}
                              <ul className="space-y-6">
                                {auditLogs.map((log) => (
                                  <li key={log.id} className="relative flex gap-x-4">
                                    <div
                                      className={classNames(
                                        'absolute left-0 top-0 flex w-6 justify-center -bottom-6'
                                      )}
                                    >
                                      <div className="w-px bg-gray-200" />
                                    </div>
                                    <div className="relative flex h-6 w-6 flex-none items-center justify-center bg-white">
                                      <div className="h-1.5 w-1.5 rounded-full bg-gray-100 ring-1 ring-gray-300" />
                                    </div>
                                    <div className="flex-auto rounded-md p-3 ring-1 ring-inset ring-gray-200 bg-white">
                                      <div className="flex justify-between gap-x-4">
                                        <div className="py-0.5 text-xs leading-5 text-gray-500">
                                          <span className="font-medium text-gray-900">
                                            {log.performedBy}
                                          </span>{' '}
                                          {log.action.toLowerCase().replace(/_/g, ' ')}
                                        </div>
                                        <time
                                          dateTime={log.timestamp}
                                          className="flex-none py-0.5 text-xs leading-5 text-gray-500"
                                        >
                                          {new Date(log.timestamp).toLocaleDateString()}
                                        </time>
                                      </div>
                                      <p className="text-sm leading-6 text-gray-600 break-words mt-1">
                                        {log.details.split('. Reason:')[0]}
                                      </p>
                                      {log.reason && (
                                        <div className="mt-2 text-xs italic bg-gray-50 p-2 rounded text-gray-600 border border-gray-100">
                                          Note: {log.reason}
                                        </div>
                                      )}
                                    </div>
                                  </li>
                                ))}
                              </ul>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </div>
      </Dialog>
    </Transition.Root>
  );
};

export default HistoryDrawer;
