import React, { useState, useEffect } from 'react';
import PdfViewerModal from '../../../components/PdfViewerModal';
import salesApi from '../../../services/pharmacy/salesApi';

/**
 * BillingHistoryView — the pharmacy sales bill history (all completed sales), with a per-row
 * "View" that opens the invoice in a viewer (inline bill + print + download).
 *
 * Shared by the pharmacy-tenant admin dashboard (HospitalAdminDashboard "Billing" tab) and the
 * pharmacist's PharmacyDashboard ("Billing" tab) so both see the same list. Pharmacy-scoped only.
 */
const BillingHistoryView = ({ refreshKey }) => {
  const [bills, setBills] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [viewerBill, setViewerBill] = useState(null); // bill whose invoice is open in the viewer
  const pageSize = 10;

  useEffect(() => {
    let active = true;
    setLoading(true);
    salesApi
      .getHistory(page, pageSize)
      .then((data) => {
        if (!active) return;
        const content = data.content || data || [];
        setBills(content);
        setTotalPages(data.totalPages || 1);
        setTotalElements(data.totalElements != null ? data.totalElements : content.length);
      })
      .catch(() => {
        if (active) setBills([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [page, refreshKey]);

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-gray-900">Billing</h2>
          <p className="text-xs text-gray-500 mt-0.5">All pharmacy sales bills</p>
        </div>
        <span className="text-xs font-medium text-gray-500">{totalElements} bills</span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
            <tr>
              <th className="px-6 py-3">Bill No.</th>
              <th className="px-6 py-3">Date</th>
              <th className="px-6 py-3">Customer</th>
              <th className="px-6 py-3 text-center">Items</th>
              <th className="px-6 py-3">Payment</th>
              <th className="px-6 py-3 text-right">Amount</th>
              <th className="px-6 py-3 text-right">Invoice</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={7} className="py-16 text-center text-gray-400">
                  Loading bills...
                </td>
              </tr>
            ) : bills.length > 0 ? (
              bills.map((b) => (
                <tr key={b.id} className="hover:bg-gray-50/50">
                  <td className="px-6 py-3 font-bold text-gray-900">{b.billNumber}</td>
                  <td className="px-6 py-3 text-gray-600">
                    {b.createdAt ? new Date(b.createdAt).toLocaleDateString() : '-'}
                  </td>
                  <td className="px-6 py-3 text-gray-700">{b.patientName || 'Walk-in'}</td>
                  <td className="px-6 py-3 text-center text-gray-600">{b.items?.length ?? '-'}</td>
                  <td className="px-6 py-3">
                    <span className="text-xs font-medium text-gray-600">
                      {b.paymentMethod || '-'}
                    </span>
                    <span
                      className={`ml-2 px-2 py-0.5 rounded text-[9px] font-black uppercase ${b.paymentStatus === 'PAID' ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'}`}
                    >
                      {b.paymentStatus || '-'}
                    </span>
                  </td>
                  <td className="px-6 py-3 text-right font-bold text-gray-900">
                    ₹{Number(b.netAmount || 0).toLocaleString()}
                  </td>
                  <td className="px-6 py-3 text-right">
                    <button
                      onClick={() => setViewerBill(b)}
                      className="px-3 py-1 text-xs font-bold text-indigo-600 border border-indigo-200 bg-indigo-50 rounded hover:bg-indigo-100"
                    >
                      View
                    </button>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={7} className="py-16 text-center text-gray-400 italic">
                  No bills yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="px-6 py-3 border-t border-gray-100 flex items-center justify-between">
        <span className="text-xs text-gray-500">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <div className="flex gap-2">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className={`px-3 py-1.5 rounded border text-xs font-bold ${page === 0 ? 'text-gray-300 border-gray-200 cursor-not-allowed' : 'text-gray-700 border-gray-300 hover:bg-gray-50'}`}
          >
            ← Prev
          </button>
          <button
            disabled={page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
            className={`px-3 py-1.5 rounded border text-xs font-bold ${page + 1 >= totalPages ? 'text-gray-300 border-gray-200 cursor-not-allowed' : 'text-gray-700 border-gray-300 hover:bg-gray-50'}`}
          >
            Next →
          </button>
        </div>
      </div>
      {viewerBill && (
        <PdfViewerModal
          endpointPath={`/pharmacy/sales/${viewerBill.id}/pdf`}
          title={`Invoice ${viewerBill.billNumber || viewerBill.id}`}
          onClose={() => setViewerBill(null)}
        />
      )}
    </div>
  );
};

export default BillingHistoryView;
