import React from 'react';

/**
 * OtBoard - presentational table of surgeries. Used by reception (Requests +
 * Scheduled/Live, with actions) and by the surgeon board (read-only).
 * `mode` = 'requests' | 'board' | 'doctor'. Action handlers are optional.
 */
/**
 * The board renders an action only when its handler is supplied.
 *
 * That was already true of Team, Checklist, Recovery and Close, and is now true of Schedule,
 * Cancel, Start and Complete too. It is what lets a caller gate by permission in one place: a
 * dashboard passes the handlers for the actions the current user actually holds, and the board
 * shows exactly those. Rendering a button whose endpoint will refuse the caller is the bug this
 * closes -- the action was offered, pressed, and answered with Access Denied.
 */
const statusPill = (status) => {
  const map = {
    REQUESTED: 'bg-amber-50 text-amber-700',
    APPROVED: 'bg-teal-50 text-teal-700',
    SCHEDULED: 'bg-blue-50 text-blue-700',
    PRE_OP: 'bg-indigo-50 text-indigo-700',
    IN_PROGRESS: 'bg-green-50 text-green-700',
    COMPLETED: 'bg-gray-100 text-gray-600',
    CLOSED: 'bg-gray-100 text-gray-500',
    POSTPONED: 'bg-orange-50 text-orange-700',
    CANCELLED: 'bg-red-50 text-red-600',
  };
  return (
    <span
      className={`px-2 py-0.5 rounded text-xs font-semibold ${map[status] || 'bg-gray-100 text-gray-600'}`}
    >
      {(status || '').replace('_', ' ')}
    </span>
  );
};

const fmt = (dt) =>
  dt ? new Date(dt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '—';

const OtBoard = ({
  rows,
  mode,
  onSchedule,
  onCancel,
  onStart,
  onComplete,
  onTeam,
  onExecute,
  onRecovery,
  onClose,
  onApprove,
  onPreOp,
  onAnaesthesia,
  onPostpone,
}) => {
  if (!rows || rows.length === 0) {
    return <div className="text-center text-gray-400 py-16">No surgeries to show.</div>;
  }
  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-gray-600">
          <tr>
            <th className="text-left px-4 py-3 font-semibold">Patient</th>
            <th className="text-left px-4 py-3 font-semibold">Procedure</th>
            <th className="text-left px-4 py-3 font-semibold">Priority</th>
            <th className="text-left px-4 py-3 font-semibold">
              {mode === 'requests' ? 'Preferred' : 'Scheduled'}
            </th>
            {mode !== 'requests' && <th className="text-left px-4 py-3 font-semibold">Surgeon</th>}
            {mode !== 'requests' && <th className="text-left px-4 py-3 font-semibold">OT Ward</th>}
            <th className="text-left px-4 py-3 font-semibold">Status</th>
            {mode !== 'doctor' && <th className="text-right px-4 py-3 font-semibold">Actions</th>}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {rows.map((r) => (
            <tr key={r.publicId}>
              <td className="px-4 py-3">
                <div className="font-semibold text-gray-900">{r.patientName || '—'}</div>
                <div className="text-xs text-gray-500">{r.ipdNumber}</div>
              </td>
              <td className="px-4 py-3">{r.procedureName || '—'}</td>
              <td className="px-4 py-3">{r.priority}</td>
              <td className="px-4 py-3">
                {mode === 'requests' ? r.preferredDate || '—' : fmt(r.scheduledAt)}
              </td>
              {mode !== 'requests' && (
                <td className="px-4 py-3">
                  <div>{r.surgeonName || '—'}</div>
                  {r.anaesthetistName && (
                    <div className="text-xs text-gray-500">Anaes: {r.anaesthetistName}</div>
                  )}
                </td>
              )}
              {mode !== 'requests' && (
                <td className="px-4 py-3">{r.otRoomName || r.otWardName || '—'}</td>
              )}
              <td className="px-4 py-3">{statusPill(r.status)}</td>
              {mode !== 'doctor' && (
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {mode === 'requests' && (
                    <>
                      {onApprove && r.status === 'REQUESTED' && (
                        <button
                          onClick={() => onApprove(r)}
                          className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 mr-2"
                        >
                          Approve
                        </button>
                      )}
                      {onSchedule && (
                      <button
                        onClick={() => onSchedule(r)}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white bg-gray-900 hover:bg-gray-800 mr-2"
                      >
                        Schedule
                      </button>
                      )}
                      {onCancel && (
                      <button
                        onClick={() => onCancel(r)}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-red-600 hover:bg-red-50"
                      >
                        Cancel
                      </button>
                      )}
                    </>
                  )}
                  {mode === 'board' && onTeam && (
                    <button
                      onClick={() => onTeam(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-600 border border-gray-300 hover:bg-gray-50 mr-2"
                    >
                      Team
                    </button>
                  )}
                  {mode === 'board' &&
                    onExecute &&
                    (r.status === 'SCHEDULED' || r.status === 'PRE_OP' || r.status === 'IN_PROGRESS') && (
                      <button
                        onClick={() => onExecute(r)}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-indigo-600 border border-indigo-200 hover:bg-indigo-50 mr-2"
                      >
                        Checklist
                      </button>
                    )}
                  {mode === 'board' && onPreOp && r.status === 'SCHEDULED' && (
                    <button
                      onClick={() => onPreOp(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-600 border border-gray-300 hover:bg-gray-50 mr-2"
                    >
                      Pre-op
                    </button>
                  )}
                  {mode === 'board' &&
                    onAnaesthesia &&
                    (r.status === 'SCHEDULED' || r.status === 'PRE_OP') && (
                      <button
                        onClick={() => onAnaesthesia(r)}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-600 border border-gray-300 hover:bg-gray-50 mr-2"
                      >
                        Anaesthesia
                      </button>
                    )}
                  {mode === 'board' &&
                    onPostpone &&
                    (r.status === 'SCHEDULED' || r.status === 'PRE_OP') && (
                      <button
                        onClick={() => onPostpone(r)}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-amber-700 border border-amber-200 hover:bg-amber-50 mr-2"
                      >
                        Postpone
                      </button>
                    )}
                  {mode === 'board' && onStart && (r.status === 'SCHEDULED' || r.status === 'PRE_OP') && (
                    <button
                      onClick={() => onStart(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white bg-green-600 hover:bg-green-700"
                    >
                      Start
                    </button>
                  )}
                  {mode === 'board' && onComplete && r.status === 'IN_PROGRESS' && (
                    <button
                      onClick={() => onComplete(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white bg-gray-900 hover:bg-gray-800"
                    >
                      Complete
                    </button>
                  )}
                  {mode === 'board' && r.status === 'COMPLETED' && onRecovery && (
                    <button
                      onClick={() => onRecovery(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-teal-600 border border-teal-200 hover:bg-teal-50 mr-2"
                    >
                      Recovery
                    </button>
                  )}
                  {mode === 'board' && r.status === 'COMPLETED' && onClose && (
                    <button
                      onClick={() => onClose(r)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-600 border border-gray-300 hover:bg-gray-50"
                    >
                      Close
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default OtBoard;
