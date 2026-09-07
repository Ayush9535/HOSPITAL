import React, { useEffect, useState, useCallback } from 'react';
import DateSelect from '../../../components/DateSelect';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';
import { printHtml } from '../../../utils/printHtml';

/**
 * OtListPrint - today's OT List: patient, age, procedure, theatre, time, surgeon.
 *
 * The sheet a hospital prints and pins to the theatre door each morning. Backed by the
 * server's date-scoped read, so what is printed is exactly what is scheduled.
 */
const fmtTime = (dt) =>
  dt ? new Date(dt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '—';

const OtListPrint = ({ onClose }) => {
  const { error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (d) => {
      setLoading(true);
      try {
        setRows(await otService.getOtList(d));
      } catch (e) {
        toastError(e?.response?.data?.error || 'Failed to load OT list');
      } finally {
        setLoading(false);
      }
    },
    [toastError]
  );

  useEffect(() => {
    load(date);
  }, [date]); // eslint-disable-line react-hooks/exhaustive-deps

  const print = () => {
    const body = rows
      .map(
        (r, i) => `
            <tr>
              <td>${i + 1}</td>
              <td>${r.scheduledAt ? fmtTime(r.scheduledAt) : '—'}</td>
              <td>${r.otRoomName || r.otWardName || '—'}</td>
              <td>${r.patientName || '—'}${r.patientAge != null ? ` (${r.patientAge})` : ''}</td>
              <td>${r.procedureName || '—'}</td>
              <td>${r.surgeonName || '—'}</td>
              <td>${r.anaesthetistName || '—'}</td>
              <td>${(r.status || '').replace('_', ' ')}</td>
            </tr>`
      )
      .join('');
    printHtml(`
            <html><head><title>OT List — ${date}</title>
            <style>
              body{font-family:Arial,sans-serif;padding:24px;color:#111}
              h1{font-size:18px;margin:0}
              .sub{color:#666;font-size:13px;margin:2px 0 16px}
              table{width:100%;border-collapse:collapse;font-size:12px}
              th,td{border:1px solid #ccc;padding:6px 8px;text-align:left}
              th{background:#f3f4f6}
            </style></head><body>
            <h1>${user?.hospitalName || 'Operation Theatre'} — OT List</h1>
            <div class="sub">${new Date(date + 'T00:00:00').toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</div>
            <table><thead><tr>
              <th>#</th><th>Time</th><th>Theatre</th><th>Patient (Age)</th><th>Procedure</th><th>Surgeon</th><th>Anaesthetist</th><th>Status</th>
            </tr></thead><tbody>${body || '<tr><td colspan="8" style="text-align:center">No cases</td></tr>'}</tbody></table>
            </body></html>`);
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-3xl my-8">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 className="text-lg font-bold text-gray-900">OT List</h2>
          <div className="flex items-center gap-3">
            <DateSelect value={date} onChange={(v) => setDate(v)} />
            <button
              onClick={print}
              disabled={loading}
              className="px-4 py-2 rounded-lg text-sm font-semibold bg-indigo-600 text-white hover:bg-indigo-700"
            >
              Print
            </button>
            <button
              onClick={onClose}
              className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
            >
              ×
            </button>
          </div>
        </div>
        <div className="px-6 py-4">
          {loading ? (
            <div className="text-center text-gray-400 py-10">Loading…</div>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-gray-500 border-b border-gray-200">
                <tr>
                  <th className="text-left py-2">Time</th>
                  <th className="text-left py-2">Theatre</th>
                  <th className="text-left py-2">Patient</th>
                  <th className="text-left py-2">Procedure</th>
                  <th className="text-left py-2">Surgeon</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {rows.map((r) => (
                  <tr key={r.publicId}>
                    <td className="py-2">{fmtTime(r.scheduledAt)}</td>
                    <td className="py-2">{r.otRoomName || r.otWardName || '—'}</td>
                    <td className="py-2">
                      {r.patientName || '—'}
                      {r.patientAge != null ? ` (${r.patientAge})` : ''}
                    </td>
                    <td className="py-2">{r.procedureName || '—'}</td>
                    <td className="py-2">{r.surgeonName || '—'}</td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={5} className="text-center text-gray-400 py-8">
                      No cases scheduled.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
};

export default OtListPrint;
