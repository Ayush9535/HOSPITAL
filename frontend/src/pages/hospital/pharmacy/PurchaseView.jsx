import React, { useState, useEffect, useCallback } from 'react';
import { SkeletonTableRow } from '../../../components/Skeleton';
import { useToast } from '../../../context/ToastContext';
import purchaseApi from '../../../services/pharmacy/purchaseApi';
import PurchaseForm from './PurchaseForm';

const PurchaseView = ({ refreshKey = 0 }) => {
  const toast = useToast();
  const [invoices, setInvoices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [pageInfo, setPageInfo] = useState({ page: 0, size: 10, totalElements: 0 });

  const fetchInvoices = useCallback(async () => {
    try {
      setLoading(true);
      const data = await purchaseApi.getAll(pageInfo.page, pageInfo.size);
      setInvoices(data.content || []);
      // Only update totalElements if it has changed to prevent unnecessary re-renders
      if (data.totalElements !== pageInfo.totalElements) {
        setPageInfo((prev) => ({ ...prev, totalElements: data.totalElements }));
      }
    } catch (err) {
      toast.error('Failed to load purchase invoices');
    } finally {
      setLoading(false);
    }
  }, [pageInfo.page, pageInfo.size, toast]);

  useEffect(() => {
    fetchInvoices();
  }, [fetchInvoices]);

  useEffect(() => {
    if (refreshKey > 0) fetchInvoices();
  }, [refreshKey]);

  const handleSavePurchase = async (payload) => {
    try {
      await purchaseApi.create(payload);
      toast.success(
        `Purchase ${payload.postingStatus === 'POSTED' ? 'posted' : 'saved'} successfully`
      );
      fetchInvoices();
    } catch (err) {
      console.error(err);
      throw err;
    }
  };

  const [postingId, setPostingId] = useState(null);

  const handlePostDraft = async (id) => {
    if (postingId) return;
    setPostingId(id);
    try {
      await purchaseApi.postInvoice(id);
      toast.success('Purchase invoice posted and inventory updated');
      fetchInvoices();
    } catch (err) {
      toast.error('Failed to post invoice');
    } finally {
      setPostingId(null);
    }
  };

  // Calculate quick stats from loaded invoices (Simplified for this view)
  const stats = {
    monthTotal: invoices.reduce((sum, inv) => sum + (inv.totalAmount || 0), 0),
    pendingPayables: invoices
      .filter((i) => i.paymentStatus !== 'PAID')
      .reduce((sum, inv) => sum + (inv.totalAmount || 0), 0),
    suppliers: new Set(invoices.map((i) => i.supplierId)).size,
  };

  return (
    <div className="space-y-6">
      {/* Stats Row */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white p-5 border border-gray-200 rounded-2xl shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-1">
              Loaded Purchases
            </p>
            <p className="text-2xl font-black text-gray-900">
              ₹{stats.monthTotal.toLocaleString()}
            </p>
          </div>
          <div className="p-3 bg-blue-50 text-blue-600 rounded-xl">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z"
              />
            </svg>
          </div>
        </div>
        <div className="bg-white p-5 border border-gray-200 rounded-2xl shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-1">
              Pending Payables
            </p>
            <p className="text-2xl font-black text-gray-900">
              ₹{stats.pendingPayables.toLocaleString()}
            </p>
          </div>
          <div className="p-3 bg-amber-50 text-amber-600 rounded-xl">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
          </div>
        </div>
        <div className="bg-white p-5 border border-gray-200 rounded-2xl shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-1">
              Active Suppliers
            </p>
            <p className="text-2xl font-black text-gray-900">{stats.suppliers}</p>
          </div>
          <div className="p-3 bg-indigo-50 text-indigo-600 rounded-xl">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </div>
        </div>
      </div>

      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-2xl font-black text-gray-900">Purchase Inwards</h2>
          <p className="text-sm text-gray-500 font-medium">
            Manage medicine stock acquisition and inventory posting.
          </p>
        </div>
        <button
          onClick={() => setIsFormOpen(true)}
          className="px-6 py-3 bg-gray-900 text-white rounded-xl text-sm font-bold shadow-xl hover:shadow-gray-200 hover:-translate-y-0.5 transition-all flex items-center gap-2 active:scale-95"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          Create New Purchase Entry
        </button>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left [&_th]:whitespace-nowrap">
            <thead className="bg-gray-50 text-[11px] font-bold text-gray-400 uppercase tracking-widest border-b border-gray-100">
              <tr>
                <th className="px-6 py-4">Invoice Detail</th>
                <th className="px-6 py-4">Supplier</th>
                <th className="px-6 py-4">Date</th>
                <th className="px-6 py-4 text-right">Total Amount</th>
                <th className="px-6 py-4 text-center">Status</th>
                <th className="px-6 py-4 text-center">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {loading ? (
                <>
                  {Array.from({ length: 5 }).map((_, i) => (
                    <SkeletonTableRow key={i} cols={6} delay={i} />
                  ))}
                </>
              ) : invoices.length === 0 ? (
                <tr>
                  <td colSpan="6" className="px-6 py-10 text-center text-gray-400 font-medium">
                    No purchase invoices found.
                  </td>
                </tr>
              ) : (
                invoices.map((inv) => (
                  <tr key={inv.id} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-4">
                      <div className="font-bold text-gray-900">{inv.invoiceNumber}</div>
                      <div className="text-[10px] text-gray-400 font-mono">ID: #{inv.id}</div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-medium text-gray-700">
                        {inv.supplier?.supplierName || 'Unknown'}
                      </div>
                    </td>
                    <td className="px-6 py-4 text-gray-500 font-medium">
                      {new Date(inv.invoiceDate).toLocaleDateString()}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="font-black text-gray-900">
                        ₹{inv.totalAmount?.toLocaleString()}
                      </div>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span
                        className={`inline-flex px-2.5 py-1 rounded-lg text-[10px] font-black uppercase tracking-tight border ${
                          inv.postingStatus === 'POSTED'
                            ? 'bg-green-50 text-green-700 border-green-100'
                            : 'bg-amber-50 text-amber-700 border-amber-100'
                        }`}
                      >
                        {inv.postingStatus}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      {inv.postingStatus === 'DRAFT' ? (
                        <button
                          onClick={() => handlePostDraft(inv.id)}
                          disabled={!!postingId}
                          className={`px-3 py-1 text-white text-[10px] font-bold rounded-md transition-colors flex items-center gap-1.5 ${postingId === inv.id ? 'bg-gray-400 cursor-not-allowed' : postingId ? 'bg-gray-300 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
                        >
                          {postingId === inv.id && (
                            <svg
                              className="animate-spin h-3 w-3 text-white"
                              xmlns="http://www.w3.org/2000/svg"
                              fill="none"
                              viewBox="0 0 24 24"
                            >
                              <circle
                                className="opacity-25"
                                cx="12"
                                cy="12"
                                r="10"
                                stroke="currentColor"
                                strokeWidth="4"
                              ></circle>
                              <path
                                className="opacity-75"
                                fill="currentColor"
                                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                              ></path>
                            </svg>
                          )}
                          {postingId === inv.id ? 'Posting...' : 'Post & Inward'}
                        </button>
                      ) : (
                        <span className="text-[10px] font-bold text-gray-300">Finalized</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <PurchaseForm
        isOpen={isFormOpen}
        onClose={() => setIsFormOpen(false)}
        onSave={handleSavePurchase}
      />
    </div>
  );
};

export default PurchaseView;
