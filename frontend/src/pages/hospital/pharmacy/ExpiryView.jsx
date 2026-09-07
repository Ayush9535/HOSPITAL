import React, { useState, useEffect, useMemo } from 'react';
import ConfirmationModal from '../../../components/ConfirmationModal';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import inventoryApi from '../../../services/pharmacy/inventoryApi';

const ExpiryView = () => {
  const { success, error: toastError } = useToast();
  const currentUser = authService.getCurrentUser();
  // Standalone pharmacy ERP: expired stock can be dispatched back to supplier at a lower rate.
  const isStandalonePharmacy =
    currentUser?.modules?.includes('PHARMACY') && !currentUser?.modules?.includes('OPD');
  const [batches, setBatches] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Active sub-filters
  const [activeFilter, setActiveFilter] = useState('ALL'); // ALL, EXPIRED, CRITICAL, NEAR
  const [searchTerm, setSearchTerm] = useState('');

  // Remarks modal state for disposal
  const [selectedBatch, setSelectedBatch] = useState(null);
  const [isDisposalModalOpen, setIsDisposalModalOpen] = useState(false);
  const [disposalRemarks, setDisposalRemarks] = useState('Safe pharmaceutical destruction program');

  // Fetch expiry alerts (90 days threshold captures everything expiring inside the next quarter)
  const fetchExpiryAlerts = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await inventoryApi.getExpiring(90, 0, 100);
      if (data && data.content) {
        setBatches(data.content || []);
      } else {
        setBatches(data || []);
      }
    } catch (err) {
      console.error('Failed fetching expiring batches', err);
      setError('Failed to load expiring batches. Please ensure database connection.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchExpiryAlerts();
  }, []);

  // 1. Client-Side Analysis & Statistics
  const stats = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    let expiredCount = 0;
    let criticalCount = 0;
    let nearCount = 0;
    let totalWasteValue = 0;

    batches.forEach((b) => {
      if (b.status === 'DISPOSED') return; // Do not calculate disposed inventory as waste threat

      const expDate = new Date(b.expiryDate);
      const qty = parseFloat(b.currentQuantity || 0);
      const cost = parseFloat(b.purchaseRate || 0);
      const value = qty * cost;

      if (expDate <= today) {
        expiredCount++;
        totalWasteValue += value;
      } else {
        const diffTime = expDate - today;
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays <= 30) {
          criticalCount++;
          totalWasteValue += value;
        } else if (diffDays <= 90) {
          nearCount++;
          totalWasteValue += value;
        }
      }
    });

    return { expiredCount, criticalCount, nearCount, totalWasteValue };
  }, [batches]);

  // 2. Tab Filter & Search matching logic
  const filteredBatches = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    return batches.filter((b) => {
      const expDate = new Date(b.expiryDate);
      const diffTime = expDate - today;
      const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

      // Search Term
      const matchesSearch =
        b.medicine?.medicineName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        b.batchNumber?.toLowerCase().includes(searchTerm.toLowerCase());

      if (!matchesSearch) return false;

      // Expiry Categorization tab
      if (activeFilter === 'EXPIRED') {
        return expDate <= today;
      } else if (activeFilter === 'CRITICAL') {
        return expDate > today && diffDays <= 30;
      } else if (activeFilter === 'NEAR') {
        return expDate > today && diffDays > 30 && diffDays <= 90;
      }

      return true; // ALL tab
    });
  }, [batches, activeFilter, searchTerm]);

  // Expiry Status helper
  const getExpirySafety = (expiryDateStr) => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const expDate = new Date(expiryDateStr);

    if (expDate <= today) {
      return { label: 'Expired', color: 'bg-red-50 text-red-700 border-red-200' };
    }
    const diffTime = expDate - today;
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

    if (diffDays <= 30) {
      return {
        label: `Expiring (${diffDays} Days)`,
        color: 'bg-amber-50 text-amber-700 border-amber-200',
      };
    }
    return {
      label: `Near Expiry (${diffDays} Days)`,
      color: 'bg-blue-50 text-blue-700 border-blue-200',
    };
  };

  const [confirmState, setConfirmState] = useState({
    open: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  // 🚫 Block batch operation
  const [blockingId, setBlockingId] = useState(null);
  const handleBlockBatch = (batchId) => {
    if (blockingId) return;
    setConfirmState({
      open: true,
      title: 'Freeze Batch',
      message:
        'Are you sure you want to FREEZE this batch? This immediately blocks it from being sold or dispensed at the POS billing counter!',
      onConfirm: async () => {
        setBlockingId(batchId);
        try {
          await inventoryApi.blockBatch(batchId);
          success('Batch status successfully updated to BLOCKED!');
          fetchExpiryAlerts();
        } catch (err) {
          console.error('Block failed', err);
          toastError('Failed to block batch.');
        } finally {
          setBlockingId(null);
        }
      },
    });
  };

  // ↩️ Dispatch expired stock back to supplier at a (usually lower) rate
  const [returnBatch, setReturnBatch] = useState(null);
  const [isReturnModalOpen, setIsReturnModalOpen] = useState(false);
  const [returnQty, setReturnQty] = useState('');
  const [returnRate, setReturnRate] = useState('');
  const [processingReturn, setProcessingReturn] = useState(false);

  const openReturnModal = (batch) => {
    setReturnBatch(batch);
    setReturnQty(String(parseFloat(batch.currentQuantity || 0)));
    setReturnRate(batch.purchaseRate != null ? String(batch.purchaseRate) : '');
    setIsReturnModalOpen(true);
  };

  const handleReturnToSupplier = async () => {
    if (!returnBatch || processingReturn) return;
    const qty = parseFloat(returnQty);
    const rate = parseFloat(returnRate);
    if (!(qty > 0)) {
      toastError('Enter a valid return quantity.');
      return;
    }
    if (qty > parseFloat(returnBatch.currentQuantity)) {
      toastError('Return quantity exceeds available stock.');
      return;
    }
    if (!(rate >= 0)) {
      toastError('Enter a valid return rate.');
      return;
    }
    if (!returnBatch.supplierId) {
      toastError('This batch has no linked supplier to return to.');
      return;
    }
    setProcessingReturn(true);
    try {
      await inventoryApi.processSupplierReturn(returnBatch.supplierId, [
        { medicineBatchId: returnBatch.id, quantityToReturn: qty, returnRate: rate },
      ]);
      success('Expired stock dispatched back to supplier.');
      setIsReturnModalOpen(false);
      setReturnBatch(null);
      fetchExpiryAlerts();
    } catch (err) {
      console.error('Supplier return failed', err);
      toastError(err.response?.data?.message || 'Failed to dispatch stock back to supplier.');
    } finally {
      setProcessingReturn(false);
    }
  };

  // 🗑️ Dispose batch write-off operation
  const [disposingBatch, setDisposingBatch] = useState(false);
  const handleDisposeBatch = async () => {
    if (!selectedBatch || disposingBatch) return;
    setDisposingBatch(true);
    try {
      await inventoryApi.disposeBatch(selectedBatch.id, disposalRemarks);
      success('Batch has been successfully disposed. Current inventory is set to 0 Units.');
      setIsDisposalModalOpen(false);
      setSelectedBatch(null);
      fetchExpiryAlerts();
    } catch (err) {
      console.error('Disposal failed', err);
      toastError('Failed to write off batch stock.');
    } finally {
      setDisposingBatch(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header toolbar */}
      <div className="bg-white border border-gray-200 rounded-lg p-4 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-gray-900">Expiry Management & Safeguards</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Control batch deactivations, log disposals, and trace financial waste metrics.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="relative w-72">
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search by Medicine / Batch..."
              className="pl-10 pr-4 py-2 border border-gray-300 rounded-lg w-full text-sm outline-none focus:border-gray-900 focus:ring-1 focus:ring-gray-900 bg-white transition-all"
            />
            <svg
              className="w-4 h-4 text-gray-400 absolute left-3 top-1/2 transform -translate-y-1/2"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
          </div>
          <button
            onClick={fetchExpiryAlerts}
            className="p-2 text-gray-600 border border-gray-300 rounded bg-white hover:bg-gray-50"
            title="Refresh lists"
          >
            <svg
              className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Quick Analytics Summary cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white p-4 border border-gray-200 rounded-lg">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] uppercase font-bold text-gray-400">Already Expired</span>
            <span className="w-2.5 h-2.5 bg-red-500 rounded-full animate-ping"></span>
          </div>
          <p className="text-2xl font-black text-red-600">{stats.expiredCount} Batches</p>
          <p className="text-[10px] text-gray-400 font-bold mt-1">Requires immediate disposal!</p>
        </div>

        <div className="bg-white p-4 border border-gray-200 rounded-lg">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] uppercase font-bold text-gray-400">
              Critical Expiry (30 Days)
            </span>
            <span className="w-2 h-2 bg-amber-500 rounded-full"></span>
          </div>
          <p className="text-2xl font-black text-amber-600">{stats.criticalCount} Batches</p>
          <p className="text-[10px] text-gray-400 font-bold mt-1">Block or dispense with urgency</p>
        </div>

        <div className="bg-white p-4 border border-gray-200 rounded-lg">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] uppercase font-bold text-gray-400">
              Near Expiry (90 Days)
            </span>
            <span className="w-2 h-2 bg-blue-500 rounded-full"></span>
          </div>
          <p className="text-2xl font-black text-blue-600">{stats.nearCount} Batches</p>
          <p className="text-[10px] text-gray-400 font-bold mt-1">Eligible for supplier returns</p>
        </div>

        <div className="bg-white p-4 border border-gray-200 rounded-lg bg-gradient-to-br from-gray-900 to-gray-800 text-white shadow-sm border-0">
          <div className="flex items-center justify-between mb-1 text-gray-300">
            <span className="text-[10px] uppercase font-bold tracking-wider">
              Potential Capital Loss
            </span>
            <svg
              className="w-4 h-4 text-emerald-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
          </div>
          <p className="text-2xl font-black text-emerald-400">
            ₹{stats.totalWasteValue.toLocaleString()}
          </p>
          <p className="text-[9px] text-gray-300 font-bold mt-1 uppercase tracking-wide">
            Stock cost evaluation
          </p>
        </div>
      </div>

      {/* Smart Tab Selectors */}
      <div className="flex border-b border-gray-200 bg-white px-4 pt-3 rounded-lg border">
        <button
          onClick={() => setActiveFilter('ALL')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 ${
            activeFilter === 'ALL'
              ? 'border-gray-900 text-gray-900'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          All Alerts ({batches.length})
        </button>
        <button
          onClick={() => setActiveFilter('EXPIRED')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 ${
            activeFilter === 'EXPIRED'
              ? 'border-red-500 text-red-600'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          Already Expired ({stats.expiredCount})
        </button>
        <button
          onClick={() => setActiveFilter('CRITICAL')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 ${
            activeFilter === 'CRITICAL'
              ? 'border-amber-500 text-amber-600'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          Critical Expiring ({stats.criticalCount})
        </button>
        <button
          onClick={() => setActiveFilter('NEAR')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 ${
            activeFilter === 'NEAR'
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          Near Expiry ({stats.nearCount})
        </button>
      </div>

      {/* Expiry alerts table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden flex flex-col min-h-[300px]">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead className="bg-gray-50 text-[10px] text-gray-400 font-black uppercase tracking-widest border-b border-gray-150 sticky top-0">
              <tr>
                <th className="px-6 py-4">Medicine / Batch</th>
                <th className="px-6 py-4">Category</th>
                <th className="px-6 py-4">Expiry Date</th>
                <th className="px-6 py-4 text-right">Available stock</th>
                <th className="px-6 py-4 text-right">Purchase Cost</th>
                <th className="px-6 py-4 text-right">Total Loss Value</th>
                <th className="px-6 py-4 text-center">Batch Status</th>
                <th className="px-6 py-4 text-right w-48">Operations</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 text-xs">
              {loading ? (
                <tr>
                  <td colSpan={8} className="py-24 text-center text-gray-400 font-bold">
                    Analyzing inventory expiration timelines...
                  </td>
                </tr>
              ) : filteredBatches.length > 0 ? (
                filteredBatches.map((b) => {
                  const safety = getExpirySafety(b.expiryDate);
                  const totalLoss = parseFloat(b.currentQuantity) * parseFloat(b.purchaseRate);

                  return (
                    <tr
                      key={b.id}
                      className="hover:bg-gray-50/50 transition-colors border-b border-gray-100"
                    >
                      <td className="px-6 py-4">
                        <div className="flex flex-col">
                          <span className="font-bold text-gray-900">
                            {b.medicine?.medicineName}
                          </span>
                          <span className="text-[10px] text-gray-400 font-bold uppercase tracking-wider">
                            Batch: {b.batchNumber}
                          </span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-gray-600">
                        {b.medicine?.category?.categoryName || 'General'}
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-col gap-1">
                          <span className="font-semibold text-gray-700">{b.expiryDate}</span>
                          <span
                            className={`px-2 py-0.5 rounded-[4px] text-[8px] font-black uppercase border w-max ${safety.color}`}
                          >
                            {safety.label}
                          </span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-right font-bold text-gray-900">
                        {parseFloat(b.currentQuantity)} {b.medicine?.unitOfMeasure || 'Units'}
                      </td>
                      <td className="px-6 py-4 text-right text-gray-600 font-medium">
                        ₹{b.purchaseRate}
                      </td>
                      <td className="px-6 py-4 text-right font-black text-red-600">
                        ₹{totalLoss.toLocaleString()}
                      </td>
                      <td className="px-6 py-4 text-center">
                        <span
                          className={`px-2 py-0.5 rounded-[4px] text-[9px] font-black uppercase ${
                            b.status === 'BLOCKED'
                              ? 'bg-amber-100 text-amber-800'
                              : b.status === 'DISPOSED'
                                ? 'bg-gray-100 text-gray-600'
                                : 'bg-green-100 text-green-800'
                          }`}
                        >
                          {b.status || 'ACTIVE'}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex justify-end gap-2">
                          {b.status !== 'BLOCKED' && b.status !== 'DISPOSED' && (
                            <button
                              onClick={() => handleBlockBatch(b.id)}
                              disabled={!!blockingId}
                              className={`px-2.5 py-1.5 border rounded font-bold text-[10px] uppercase tracking-wide active:scale-95 transition-all ${blockingId === b.id ? 'bg-gray-200 text-gray-400 border-gray-200 cursor-not-allowed' : blockingId ? 'opacity-50 cursor-not-allowed bg-amber-50 text-amber-700 border-amber-200' : 'bg-amber-50 text-amber-700 hover:bg-amber-100 border-amber-200'}`}
                              title="Deactivate batch from billing counter"
                            >
                              {blockingId === b.id ? 'Blocking...' : 'Block'}
                            </button>
                          )}
                          {isStandalonePharmacy &&
                            b.status !== 'DISPOSED' &&
                            b.supplierId &&
                            parseFloat(b.currentQuantity) > 0 && (
                              <button
                                onClick={() => openReturnModal(b)}
                                className="px-2.5 py-1.5 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 border border-indigo-200 rounded font-bold text-[10px] uppercase tracking-wide active:scale-95 transition-all"
                                title="Dispatch expired stock back to supplier at a lower rate"
                              >
                                Return
                              </button>
                            )}
                          {b.status !== 'DISPOSED' && (
                            <button
                              onClick={() => {
                                setSelectedBatch(b);
                                setIsDisposalModalOpen(true);
                              }}
                              className="px-2.5 py-1.5 bg-red-50 text-red-700 hover:bg-red-100 border border-red-200 rounded font-bold text-[10px] uppercase tracking-wide active:scale-95 transition-all"
                              title="Write off stock value to 0"
                            >
                              Dispose
                            </button>
                          )}
                          {b.status === 'DISPOSED' && (
                            <span className="text-[10px] text-gray-400 font-medium italic">
                              Fully Disposed
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              ) : (
                <tr>
                  <td colSpan={8} className="py-20 text-center text-gray-400 italic">
                    No expiry warnings matched your active search filters.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <ConfirmationModal
        isOpen={confirmState.open}
        title={confirmState.title}
        message={confirmState.message}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState({ open: false })}
      />

      {/* Dispatch-back-to-supplier modal */}
      {isReturnModalOpen && returnBatch && (
        <div className="fixed inset-0 bg-gray-950/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-xl shadow-2xl border border-gray-150 max-w-md w-full p-6 animate-in zoom-in-95 duration-200">
            <div className="flex justify-between items-start mb-4">
              <div>
                <h3 className="font-bold text-gray-900 text-lg">Return to Supplier</h3>
                <p className="text-xs text-gray-500 mt-1">
                  {returnBatch.medicine?.medicineName} · Batch {returnBatch.batchNumber}
                </p>
              </div>
              <button
                onClick={() => {
                  setIsReturnModalOpen(false);
                  setReturnBatch(null);
                }}
                className="text-gray-400 hover:text-gray-600"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
            <div className="space-y-4">
              <div className="bg-indigo-50 border border-indigo-100 p-3 rounded-lg text-xs text-indigo-700">
                Dispatch expired/near-expiry stock back to the supplier. Enter the agreed return
                rate (often lower than purchase). Available:{' '}
                <strong>{parseFloat(returnBatch.currentQuantity)}</strong> units.
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label
                    htmlFor="fld-217"
                    className="text-[10px] font-black text-gray-400 uppercase tracking-widest block mb-1.5"
                  >
                    Quantity
                  </label>
                  <input
                    id="fld-217"
                    type="number"
                    min="0"
                    step="0.01"
                    value={returnQty}
                    onChange={(e) => setReturnQty(e.target.value)}
                    className="w-full border border-gray-300 rounded p-2 text-sm outline-none focus:border-gray-900 transition-colors"
                  />
                </div>
                <div>
                  <label
                    htmlFor="fld-216"
                    className="text-[10px] font-black text-gray-400 uppercase tracking-widest block mb-1.5"
                  >
                    Return Rate (₹)
                  </label>
                  <input
                    id="fld-216"
                    type="number"
                    min="0"
                    step="0.01"
                    value={returnRate}
                    onChange={(e) => setReturnRate(e.target.value)}
                    className="w-full border border-gray-300 rounded p-2 text-sm outline-none focus:border-gray-900 transition-colors"
                    placeholder="e.g. lower than purchase"
                  />
                </div>
              </div>
              <div className="flex justify-between text-xs text-gray-500 px-1">
                <span>Estimated credit</span>
                <span className="font-bold text-gray-800">
                  ₹{((parseFloat(returnQty) || 0) * (parseFloat(returnRate) || 0)).toLocaleString()}
                </span>
              </div>
            </div>
            <div className="flex justify-end gap-2 mt-6 pt-4 border-t border-gray-100">
              <button
                onClick={() => {
                  setIsReturnModalOpen(false);
                  setReturnBatch(null);
                }}
                disabled={processingReturn}
                className={`px-4 py-2 border border-gray-300 text-gray-700 text-xs font-bold rounded-lg ${processingReturn ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50'}`}
              >
                Cancel
              </button>
              <button
                onClick={handleReturnToSupplier}
                disabled={processingReturn}
                className={`px-4 py-2 text-white text-xs font-bold rounded-lg shadow-md shadow-indigo-200 ${processingReturn ? 'bg-gray-400 cursor-not-allowed' : 'bg-indigo-600 hover:bg-indigo-700'}`}
              >
                {processingReturn ? 'Dispatching...' : 'Dispatch Return'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Disposal remarks Modal overlay */}
      {isDisposalModalOpen && selectedBatch && (
        <div className="fixed inset-0 bg-gray-950/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-xl shadow-2xl border border-gray-150 max-w-md w-full p-6 animate-in zoom-in-95 duration-200">
            <div className="flex justify-between items-start mb-4">
              <div>
                <h3 className="font-bold text-gray-900 text-lg">Stock Write-Off & Disposal</h3>
                <p className="text-xs text-gray-500 mt-1">
                  Medicine: {selectedBatch.medicine?.medicineName}
                </p>
              </div>
              <button
                onClick={() => {
                  setIsDisposalModalOpen(false);
                  setSelectedBatch(null);
                }}
                className="text-gray-400 hover:text-gray-600"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
            <div className="space-y-4">
              <div className="bg-red-50 border border-red-100 p-3 rounded-lg text-xs text-red-700">
                <strong>Warning:</strong> This operation writes off all remaining **
                {parseFloat(selectedBatch.currentQuantity)}** stock units of Batch **
                {selectedBatch.batchNumber}** to **0**, and marks the batch as permanently disposed!
                This is logged as an audit transaction.
              </div>
              <div>
                <label
                  htmlFor="fld-215"
                  className="text-[10px] font-black text-gray-400 uppercase tracking-widest block mb-1.5"
                >
                  Disposal Audit Remarks
                </label>
                <textarea
                  id="fld-215"
                  value={disposalRemarks}
                  onChange={(e) => setDisposalRemarks(e.target.value)}
                  className="w-full border border-gray-300 rounded p-2 text-xs outline-none focus:border-gray-900 transition-colors"
                  rows="3"
                  placeholder="Enter reason for drug disposal..."
                />
              </div>
            </div>
            <div className="flex justify-end gap-2 mt-6 pt-4 border-t border-gray-100">
              <button
                onClick={() => {
                  setIsDisposalModalOpen(false);
                  setSelectedBatch(null);
                }}
                disabled={disposingBatch}
                className={`px-4 py-2 border border-gray-300 text-gray-700 text-xs font-bold rounded-lg ${disposingBatch ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50'}`}
              >
                Cancel
              </button>
              <button
                onClick={handleDisposeBatch}
                disabled={disposingBatch}
                className={`px-4 py-2 text-white text-xs font-bold rounded-lg shadow-md shadow-red-200 flex items-center gap-2 ${disposingBatch ? 'bg-gray-400 cursor-not-allowed' : 'bg-red-600 hover:bg-red-700'}`}
              >
                {disposingBatch && (
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
                {disposingBatch ? 'Disposing...' : 'Confirm Disposal'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ExpiryView;
