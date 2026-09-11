import React, { useState, useEffect, useMemo } from 'react';
import ConfirmationModal from '../../../components/ConfirmationModal';
import { useToast } from '../../../context/ToastContext';
import inventoryApi from '../../../services/pharmacy/inventoryApi';
import salesApi from '../../../services/pharmacy/salesApi';
import suppliersApi from '../../../services/pharmacy/suppliersApi';

const ReturnsView = () => {
  const { success, error: toastError } = useToast();

  // Tab state: PATIENT, SUPPLIER
  const [activeTab, setActiveTab] = useState('PATIENT');

  const [confirmState, setConfirmState] = useState({
    open: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  // --- 1. PATIENT RETURN STATE ---
  const [billSearch, setBillSearch] = useState('');
  const [searchingBill, setSearchingBill] = useState(false);
  const [originalSale, setOriginalSale] = useState(null);
  const [patientReturns, setPatientReturns] = useState({}); // { batchId: { qtyToReturn: 0 } }
  const [processingRefund, setProcessingRefund] = useState(false);

  // --- 2. SUPPLIER RETURN STATE ---
  const [suppliers, setSuppliers] = useState([]);
  const [selectedSupplierId, setSelectedSupplierId] = useState('');
  const [batchQuery, setBatchQuery] = useState('');
  const [searchingBatches, setSearchingBatches] = useState(false);
  const [batchSuggestions, setBatchSuggestions] = useState([]);
  const [supplierReturnItems, setSupplierReturnItems] = useState([]); // [ { batch, qtyToReturn: 0 } ]
  const [processingSupplierReturn, setProcessingSupplierReturn] = useState(false);

  // --- 3. RETURNS HISTORY MODAL STATE ---
  const [showHistoryModal, setShowHistoryModal] = useState(false);
  const [historyData, setHistoryData] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyTotalPages, setHistoryTotalPages] = useState(0);

  const loadHistory = async (page = 0) => {
    setHistoryLoading(true);
    try {
      const res = await inventoryApi.getReturnsHistory(page, 8);
      if (res) {
        setHistoryData(res.content || []);
        setHistoryPage(res.number || 0);
        setHistoryTotalPages(res.totalPages || 0);
      }
    } catch (err) {
      console.error('Failed loading returns history', err);
      toastError('Failed to fetch returns history.');
    } finally {
      setHistoryLoading(false);
    }
  };

  const handleOpenHistory = () => {
    setShowHistoryModal(true);
    loadHistory(0);
  };

  // Load initial data
  useEffect(() => {
    const fetchSuppliers = async () => {
      try {
        const res = await suppliersApi.getAll('', 0, 100);
        if (res && res.content) {
          setSuppliers(res.content);
        } else {
          setSuppliers(res || []);
        }
      } catch (err) {
        console.error('Failed loading suppliers', err);
      }
    };
    fetchSuppliers();
  }, []);

  // --- 1. Patient Returns Handlers ---
  const handleBillSearch = async (e) => {
    if (e) e.preventDefault();
    if (!billSearch.trim()) return;

    setSearchingBill(true);
    setOriginalSale(null);
    setPatientReturns({});
    try {
      const sale = await salesApi.searchByBillNumber(billSearch.trim());
      setOriginalSale(sale);

      // Initialize return states for all sale items
      const initialReturns = {};
      sale.items.forEach((item) => {
        initialReturns[item.medicineBatchId] = {
          qtyToReturn: 0,
          unitPrice: item.unitPrice,
          maxQty: item.quantity,
          medicineName: item.medicineBatch?.medicine?.medicineName || 'Unknown Medicine',
          batchNumber: item.medicineBatch?.batchNumber || 'N/A',
        };
      });
      setPatientReturns(initialReturns);
      success('Invoice found!');
    } catch (err) {
      console.error('Sale search failed', err);
      toastError(
        err.response?.data?.message || 'Invoice not found. Please verify the bill number.'
      );
    } finally {
      setSearchingBill(false);
    }
  };

  const handlePatientQtyChange = (batchId, val) => {
    const num = parseFloat(val) || 0;
    const max = patientReturns[batchId].maxQty;

    setPatientReturns((prev) => ({
      ...prev,
      [batchId]: {
        ...prev[batchId],
        qtyToReturn: num > max ? max : num < 0 ? 0 : num,
      },
    }));
  };


  // Calculate customer refund total
  const patientRefundTotal = useMemo(() => {
    let total = 0;
    Object.keys(patientReturns).forEach((key) => {
      const item = patientReturns[key];
      total += item.qtyToReturn * item.unitPrice;
    });
    return total;
  }, [patientReturns]);

  const handleProcessRefund = () => {
    if (!originalSale) return;

    // Compile items with positive return quantities
    const items = Object.keys(patientReturns)
      .map((key) => ({
        medicineBatchId: parseInt(key),
        quantityToReturn: patientReturns[key].qtyToReturn,
      }))
      .filter((item) => item.quantityToReturn > 0);

    if (items.length === 0) {
      toastError('Please enter at least one item quantity to return.');
      return;
    }

    setConfirmState({
      open: true,
      title: 'Process Patient Refund',
      message: `Are you sure you want to process this refund of ₹${patientRefundTotal.toLocaleString()}? This will create stock reversal entries.`,
      onConfirm: async () => {
        setProcessingRefund(true);
        try {
          await salesApi.processReturn(originalSale.id, items);
          success(
            `Refund processed successfully! ₹${patientRefundTotal.toLocaleString()} refunded.`
          );
          setOriginalSale(null);
          setBillSearch('');
          setPatientReturns({});
        } catch (err) {
          console.error('Refund failed', err);
          toastError(err.response?.data?.message || 'Failed to process patient refund.');
        } finally {
          setProcessingRefund(false);
        }
      },
    });
  };

  // --- 2. Supplier Returns Handlers ---
  const handleBatchSearch = async (val) => {
    setBatchQuery(val);
    if (val.trim().length < 2) {
      setBatchSuggestions([]);
      return;
    }

    setSearchingBatches(true);
    try {
      const results = await inventoryApi.searchBatches(val);
      setBatchSuggestions(results || []);
    } catch (err) {
      console.error('Batch search failed', err);
    } finally {
      setSearchingBatches(false);
    }
  };

  const handleSelectBatch = (batch) => {
    // Prevent duplicate add
    if (supplierReturnItems.some((i) => i.batch.id === batch.id)) {
      toastError('Batch is already added to return list.');
      return;
    }

    setSupplierReturnItems((prev) => [...prev, { batch, qtyToReturn: 0 }]);
    setBatchQuery('');
    setBatchSuggestions([]);
  };

  const handleSupplierQtyChange = (idx, val) => {
    const num = parseFloat(val) || 0;
    const max = parseFloat(supplierReturnItems[idx].batch.currentQuantity || 0);

    setSupplierReturnItems((prev) => {
      const list = [...prev];
      list[idx].qtyToReturn = num > max ? max : num < 0 ? 0 : num;
      return list;
    });
  };

  const handleRemoveSupplierItem = (idx) => {
    setSupplierReturnItems((prev) => prev.filter((_, i) => i !== idx));
  };

  // Calculate supplier return credit note total
  const supplierRefundTotal = useMemo(() => {
    let total = 0;
    supplierReturnItems.forEach((item) => {
      const rate = parseFloat(item.batch.purchaseRate || 0);
      total += item.qtyToReturn * rate;
    });
    return total;
  }, [supplierReturnItems]);

  const handleProcessSupplierReturn = () => {
    if (!selectedSupplierId) {
      toastError('Please select a supplier first.');
      return;
    }

    const items = supplierReturnItems
      .map((item) => ({
        medicineBatchId: item.batch.id,
        quantityToReturn: item.qtyToReturn,
      }))
      .filter((item) => item.quantityToReturn > 0);

    if (items.length === 0) {
      toastError('Please specify a positive return quantity for at least one batch.');
      return;
    }

    setConfirmState({
      open: true,
      title: 'Finalize Supplier Return',
      message: `Are you sure you want to finalize this Supplier Return dispatch of ₹${supplierRefundTotal.toLocaleString()}? This will deduct stocks from active inventory immediately.`,
      onConfirm: async () => {
        setProcessingSupplierReturn(true);
        try {
          await inventoryApi.processSupplierReturn(selectedSupplierId, items);
          success(
            `Supplier return dispatched successfully! Total claim value: ₹${supplierRefundTotal.toLocaleString()}`
          );
          setSupplierReturnItems([]);
          setSelectedSupplierId('');
        } catch (err) {
          console.error('Supplier return dispatch failed', err);
          toastError(err.response?.data?.message || 'Failed to process supplier return.');
        } finally {
          setProcessingSupplierReturn(false);
        }
      },
    });
  };

  return (
    <div className="space-y-6">
      {/* Header toolbar */}
      <div className="bg-white border border-gray-200 rounded-lg p-4 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-gray-900">Returns & Refunds Portal</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Manage inwards patient returns and outwards supplier dispatch notes with automated stock
            reversals.
          </p>
        </div>
        <button
          onClick={handleOpenHistory}
          className="px-4 py-2.5 border border-gray-300 hover:border-gray-900 text-gray-700 hover:text-gray-900 bg-white rounded-lg text-xs font-bold transition-all flex items-center gap-2 active:scale-95 shadow-sm"
        >
          📜 Returns History Log
        </button>
      </div>

      {/* Smart Navigation Tabs */}
      <div className="flex border-b border-gray-200 bg-white px-4 pt-3 rounded-lg border">
        <button
          onClick={() => setActiveTab('PATIENT')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 flex items-center gap-2 ${
            activeTab === 'PATIENT'
              ? 'border-gray-900 text-gray-900'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          🚶‍♂️ Customer / Patient Refunds
        </button>
        <button
          onClick={() => setActiveTab('SUPPLIER')}
          className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 flex items-center gap-2 ${
            activeTab === 'SUPPLIER'
              ? 'border-gray-900 text-gray-900'
              : 'border-transparent text-gray-400 hover:text-gray-600'
          }`}
        >
          📦 Supplier Dispatch Returns
        </button>
      </div>

      {/* Main view container based on active tab */}
      {activeTab === 'PATIENT' ? (
        // --- PATIENT RETURNS SECTION ---
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left/Main Column - Sale details and item selection (Span 2) */}
          <div className="lg:col-span-2 space-y-6">
            {/* Search Invoice panel */}
            <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm space-y-4">
              <h3 className="text-xs font-black uppercase text-gray-400 tracking-wider">
                Search Original Sale Bill
              </h3>
              <form onSubmit={handleBillSearch} className="flex gap-3">
                <input
                  type="text"
                  value={billSearch}
                  onChange={(e) => setBillSearch(e.target.value)}
                  placeholder="Enter Bill Number (e.g. PHB-171598463832)..."
                  className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm bg-white outline-none focus:border-gray-900 transition-all font-semibold"
                  required
                />
                <button
                  type="submit"
                  disabled={searchingBill}
                  className="px-6 py-2.5 bg-gray-900 text-white rounded-lg text-sm font-bold hover:bg-gray-800 transition-colors disabled:bg-gray-400 active:scale-95"
                >
                  {searchingBill ? 'Searching...' : 'Search Bill'}
                </button>
              </form>
            </div>

            {originalSale ? (
              <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
                <div className="px-5 py-4 border-b border-gray-150 bg-gray-50/50 flex justify-between items-center">
                  <div>
                    <h3 className="font-bold text-gray-800 text-sm">Bill Items Overview</h3>
                    <p className="text-[10px] text-gray-400 mt-0.5">
                      Specify the quantity being returned per item. Refunded medicine is not returned to saleable stock.
                    </p>
                  </div>
                  <span className="text-xs font-black bg-gray-200 text-gray-700 px-2 py-0.5 rounded-full">
                    {originalSale.items?.length || 0} Products
                  </span>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead className="bg-gray-50 border-b border-gray-150 text-[10px] text-gray-400 font-bold uppercase tracking-wider">
                      <tr>
                        <th className="px-6 py-3.5">Medicine</th>
                        <th className="px-6 py-3.5 text-right">Sold Qty</th>
                        <th className="px-6 py-3.5 text-center">Qty to Return</th>
                        <th className="px-6 py-3.5 text-center">Put back to Shelf?</th>
                        <th className="px-6 py-3.5 text-right">Unit Price</th>
                        <th className="px-6 py-3.5 text-right">Refund Subtotal</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {originalSale.items.map((item) => {
                        const ret = patientReturns[item.medicineBatchId] || {
                          qtyToReturn: 0,
                        };
                        const refundSub = ret.qtyToReturn * item.unitPrice;

                        // Expiry verification check
                        const today = new Date();
                        today.setHours(0, 0, 0, 0);
                        const expDate = item.medicineBatch?.expiryDate
                          ? new Date(item.medicineBatch.expiryDate)
                          : null;
                        const isExpired = expDate ? expDate <= today : false;

                        return (
                          <tr
                            key={item.id}
                            className={`hover:bg-gray-50/30 transition-colors ${isExpired ? 'bg-red-50/30' : ''}`}
                          >
                            <td className="px-6 py-4">
                              <div className="flex flex-col">
                                <span className="font-bold text-gray-900">
                                  {item.medicineBatch?.medicine?.medicineName || 'Unknown Product'}
                                </span>
                                <span className="text-[10px] text-gray-400 font-semibold uppercase flex items-center gap-1.5 mt-0.5">
                                  Batch: {item.medicineBatch?.batchNumber}
                                  {isExpired && (
                                    <span className="px-1.5 py-0.5 bg-red-100 text-red-700 rounded text-[8px] font-black uppercase border border-red-200">
                                      Expired Batch - Return Locked
                                    </span>
                                  )}
                                </span>
                              </div>
                            </td>
                            <td className="px-6 py-4 text-right font-bold text-gray-600">
                              {item.quantity} Units
                            </td>
                            <td className="px-6 py-4 text-center">
                              <input
                                type="number"
                                value={isExpired ? 0 : ret.qtyToReturn || ''}
                                onChange={(e) =>
                                  handlePatientQtyChange(item.medicineBatchId, e.target.value)
                                }
                                max={item.quantity}
                                min={0}
                                disabled={isExpired}
                                placeholder={isExpired ? 'N/A' : '0'}
                                className={`w-16 px-2 py-1 text-center font-bold border rounded focus:border-gray-900 outline-none text-xs bg-white ${
                                  isExpired
                                    ? 'border-red-200 text-red-400 bg-red-50/50 cursor-not-allowed'
                                    : 'border-gray-300'
                                }`}
                              />
                            </td>
                            <td className="px-6 py-4 text-center">
{/* Medicine that has been with a patient is not put back on the shelf, so there is
                                  no choice to offer here. The refund still goes through. */}
                              <span
                                className="text-[10px] font-black uppercase tracking-widest text-gray-500"
                                title="Returned medicine is refunded but not added back to saleable stock."
                              >
                                Not restocked
                              </span>
                            </td>
                            <td className="px-6 py-4 text-right font-semibold text-gray-600">
                              ₹{item.unitPrice}
                            </td>
                            <td className="px-6 py-4 text-right font-black text-red-600">
                              ₹{isExpired ? '0' : refundSub.toLocaleString()}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="bg-white border border-gray-200 border-dashed rounded-lg p-16 text-center text-gray-400 text-sm">
                <svg
                  className="w-12 h-12 text-gray-300 mx-auto mb-3"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                    d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
                Enter a valid invoice bill number to load customer purchase transaction.
              </div>
            )}
          </div>

          {/* Right Column - Refund Summary Panel */}
          <div className="space-y-6">
            <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm space-y-4">
              <h3 className="text-xs font-black uppercase text-gray-400 tracking-wider">
                Refund Summary
              </h3>

              {originalSale ? (
                <div className="space-y-3.5 pt-1 text-xs">
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Bill Number</span>
                    <span className="font-bold text-gray-800">{originalSale.billNumber}</span>
                  </div>
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Patient Name</span>
                    <span className="font-bold text-gray-800">
                      {originalSale.patientName || 'Walk-In'}
                    </span>
                  </div>
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Original Total</span>
                    <span className="font-semibold text-gray-700">₹{originalSale.netAmount}</span>
                  </div>
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Payment Mode</span>
                    <span className="font-bold text-gray-800 uppercase">
                      {originalSale.paymentMethod}
                    </span>
                  </div>

                  {/* Big bold total */}
                  <div className="bg-red-50 p-4 border border-red-100 rounded-lg flex items-center justify-between mt-6">
                    <div className="flex flex-col">
                      <span className="text-[10px] text-red-600 font-bold uppercase tracking-wider">
                        Net Cashback Refund
                      </span>
                      <span className="text-xs text-red-400 font-semibold">
                        Including tax reversals
                      </span>
                    </div>
                    <span className="text-2xl font-black text-red-600">
                      ₹{patientRefundTotal.toLocaleString()}
                    </span>
                  </div>

                  <button
                    onClick={handleProcessRefund}
                    disabled={processingRefund || patientRefundTotal <= 0}
                    className="w-full py-3 bg-red-600 hover:bg-red-700 text-white rounded-lg font-bold transition-all disabled:bg-gray-300 text-xs uppercase tracking-wider active:scale-95 shadow-md shadow-red-200 mt-2"
                  >
                    {processingRefund ? 'Processing...' : 'Process Patient Refund'}
                  </button>
                </div>
              ) : (
                <p className="text-xs text-gray-400 italic">
                  No bill searched. Load a transaction first.
                </p>
              )}
            </div>
          </div>
        </div>
      ) : (
        // --- SUPPLIER RETURNS SECTION ---
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left/Main Column - Batch lookup and dispatch items */}
          <div className="lg:col-span-2 space-y-6">
            {/* Supplier Selection and batch auto-suggest */}
            <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm space-y-4">
              <h3 className="text-xs font-black uppercase text-gray-400 tracking-wider">
                Select Vendor & Add Expired/Expiring Drugs
              </h3>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label
                    htmlFor="fld-222"
                    className="text-[10px] font-black text-gray-400 uppercase tracking-widest block mb-1.5"
                  >
                    Supplier / Vendor
                  </label>
                  <select
                    id="fld-222"
                    value={selectedSupplierId}
                    onChange={(e) => setSelectedSupplierId(e.target.value)}
                    className="w-full border border-gray-300 rounded-lg p-2.5 text-xs outline-none focus:border-gray-900 bg-white font-semibold"
                    required
                  >
                    <option value="">-- Choose Supplier --</option>
                    {suppliers.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.supplierName}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="relative">
                  <label
                    htmlFor="fld-221"
                    className="text-[10px] font-black text-gray-400 uppercase tracking-widest block mb-1.5"
                  >
                    Add Medicine Batch (FEFO Search)
                  </label>
                  <input
                    id="fld-221"
                    type="text"
                    value={batchQuery}
                    onChange={(e) => handleBatchSearch(e.target.value)}
                    disabled={!selectedSupplierId}
                    placeholder={
                      selectedSupplierId
                        ? 'Search medicine / batch number...'
                        : 'Choose supplier first...'
                    }
                    className="w-full border border-gray-300 rounded-lg p-2.5 text-xs outline-none focus:border-gray-900 bg-white font-semibold"
                  />
                  {searchingBatches && (
                    <span className="absolute right-3 bottom-3 text-[10px] font-bold text-gray-400">
                      Searching...
                    </span>
                  )}

                  {/* Dropdown Suggestions */}
                  {batchSuggestions.length > 0 && (
                    <div className="absolute left-0 right-0 mt-1 bg-white border border-gray-200 shadow-xl rounded-lg z-50 divide-y divide-gray-150 max-h-56 overflow-y-auto">
                      {batchSuggestions.map((b) => (
                        <button
                          key={b.id}
                          type="button"
                          onClick={() => handleSelectBatch(b)}
                          className="w-full text-left px-4 py-2 hover:bg-gray-50 flex items-center justify-between text-xs transition-colors"
                        >
                          <div className="flex flex-col">
                            <span className="font-bold text-gray-900">
                              {b.medicine?.medicineName}
                            </span>
                            <span className="text-[10px] text-gray-400 font-semibold uppercase">
                              Batch: {b.batchNumber} | Exp: {b.expiryDate}
                            </span>
                          </div>
                          <span className="font-black text-red-600 bg-red-50 border border-red-150 px-2 py-0.5 rounded-full">
                            {parseFloat(b.currentQuantity)} Units Avail
                          </span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* Dispatch list table */}
            <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
              <div className="px-5 py-4 border-b border-gray-150 bg-gray-50/50 flex justify-between items-center">
                <div>
                  <h3 className="font-bold text-gray-800 text-sm">Dispatched Vendor Return Note</h3>
                  <p className="text-[10px] text-gray-400 mt-0.5">
                    Specify return quantities for the supplier note.
                  </p>
                </div>
                <span className="text-xs font-black bg-gray-200 text-gray-700 px-2 py-0.5 rounded-full">
                  {supplierReturnItems.length} Products
                </span>
              </div>

              {supplierReturnItems.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead className="bg-gray-50 border-b border-gray-150 text-[10px] text-gray-400 font-bold uppercase tracking-wider">
                      <tr>
                        <th className="px-6 py-3.5">Medicine / Batch</th>
                        <th className="px-6 py-3.5 text-right">Available stock</th>
                        <th className="px-6 py-3.5 text-center w-36">Return Qty</th>
                        <th className="px-6 py-3.5 text-right">Purchase Cost</th>
                        <th className="px-6 py-3.5 text-right">Refund Claim</th>
                        <th className="px-6 py-3.5 text-center">Operation</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {supplierReturnItems.map((item, idx) => {
                        const rate = parseFloat(item.batch.purchaseRate || 0);
                        const refundClaim = item.qtyToReturn * rate;

                        return (
                          <tr key={item.batch.id} className="hover:bg-gray-50/30 transition-colors">
                            <td className="px-6 py-4">
                              <div className="flex flex-col">
                                <span className="font-bold text-gray-900">
                                  {item.batch.medicine?.medicineName}
                                </span>
                                <span className="text-[10px] text-gray-400 font-semibold uppercase">
                                  Batch: {item.batch.batchNumber} | Exp: {item.batch.expiryDate}
                                </span>
                              </div>
                            </td>
                            <td className="px-6 py-4 text-right font-bold text-red-600">
                              {parseFloat(item.batch.currentQuantity)} Units
                            </td>
                            <td className="px-6 py-4 text-center">
                              <input
                                type="number"
                                value={item.qtyToReturn || ''}
                                onChange={(e) => handleSupplierQtyChange(idx, e.target.value)}
                                max={parseFloat(item.batch.currentQuantity || 0)}
                                min={0}
                                placeholder="0"
                                className="w-20 px-2 py-1 text-center font-bold border border-gray-300 rounded focus:border-gray-900 outline-none text-xs bg-white"
                              />
                            </td>
                            <td className="px-6 py-4 text-right font-semibold text-gray-600">
                              ₹{rate}
                            </td>
                            <td className="px-6 py-4 text-right font-black text-gray-900">
                              ₹{refundClaim.toLocaleString()}
                            </td>
                            <td className="px-6 py-4 text-center">
                              <button
                                onClick={() => handleRemoveSupplierItem(idx)}
                                className="text-red-600 hover:text-red-800 font-bold"
                              >
                                Remove
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="bg-white border-t border-gray-150 p-16 text-center text-gray-400 text-sm">
                  No batches added to return Note. Use search autocomplete above.
                </div>
              )}
            </div>
          </div>

          {/* Right Column - Supplier Dispatch Summary */}
          <div className="space-y-6">
            <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm space-y-4">
              <h3 className="text-xs font-black uppercase text-gray-400 tracking-wider">
                Dispatch Note Summary
              </h3>

              {selectedSupplierId ? (
                <div className="space-y-3.5 pt-1 text-xs">
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Supplier Name</span>
                    <span className="font-bold text-gray-800">
                      {suppliers.find((s) => s.id === parseInt(selectedSupplierId))?.supplierName ||
                        'Unknown Vendor'}
                    </span>
                  </div>
                  <div className="flex justify-between border-b border-gray-100 pb-2">
                    <span className="text-gray-500">Return Reference</span>
                    <span className="font-bold text-gray-800">RTV-{Date.now()}</span>
                  </div>

                  {/* Big bold total */}
                  <div className="bg-emerald-50 p-4 border border-emerald-100 rounded-lg flex items-center justify-between mt-6">
                    <div className="flex flex-col">
                      <span className="text-[10px] text-emerald-700 font-bold uppercase tracking-wider">
                        Total Value Claimed
                      </span>
                      <span className="text-xs text-emerald-500 font-semibold">
                        Credit Refund draft
                      </span>
                    </div>
                    <span className="text-2xl font-black text-emerald-700">
                      ₹{supplierRefundTotal.toLocaleString()}
                    </span>
                  </div>

                  <button
                    onClick={handleProcessSupplierReturn}
                    disabled={processingSupplierReturn || supplierRefundTotal <= 0}
                    className="w-full py-3 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg font-bold transition-all disabled:bg-gray-300 text-xs uppercase tracking-wider active:scale-95 shadow-md shadow-emerald-200 mt-2"
                  >
                    {processingSupplierReturn ? 'Dispatching...' : 'Dispatch Supplier Return'}
                  </button>
                </div>
              ) : (
                <p className="text-xs text-gray-400 italic">
                  No Supplier Selected. Select a vendor above to start compilation.
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      <ConfirmationModal
        isOpen={confirmState.open}
        title={confirmState.title}
        message={confirmState.message}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState({ open: false })}
      />

      {/* --- RETURNS HISTORY OVERLAY MODAL --- */}
      {showHistoryModal && (
        <div className="fixed inset-0 bg-gray-900/60 backdrop-blur-sm flex items-center justify-center z-50 p-4 transition-all">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-4xl overflow-hidden border border-gray-150 flex flex-col max-h-[85vh]">
            {/* Modal Header */}
            <div className="px-6 py-4 border-b border-gray-100 bg-gray-50 flex justify-between items-center">
              <div>
                <h3 className="font-bold text-gray-955 text-sm">Returns & Refunds Audit History</h3>
                <p className="text-[10px] text-gray-400 mt-0.5">
                  Live log of all customer restocks, write-offs, and supplier dispatches.
                </p>
              </div>
              <button
                onClick={() => setShowHistoryModal(false)}
                className="text-gray-400 hover:text-gray-700 text-xs font-bold bg-gray-100 px-3 py-1.5 rounded-lg active:scale-95 transition-all"
              >
                Close Log
              </button>
            </div>

            {/* Modal Content */}
            <div className="flex-1 overflow-y-auto p-6">
              {historyLoading ? (
                <div className="text-center py-16 text-gray-400 text-xs font-bold">
                  Loading audit logs...
                </div>
              ) : historyData.length > 0 ? (
                <div className="overflow-x-auto border border-gray-150 rounded-lg">
                  <table className="w-full text-left border-collapse text-[11px]">
                    <thead className="bg-gray-50 border-b border-gray-150 text-[9px] text-gray-400 font-bold uppercase tracking-wider">
                      <tr>
                        <th className="px-4 py-3">Timestamp</th>
                        <th className="px-4 py-3">Medicine / Batch</th>
                        <th className="px-4 py-3 text-center">Reference Type</th>
                        <th className="px-4 py-3 text-right">Adjustment Qty</th>
                        <th className="px-4 py-3">Logs & Remarks</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 font-semibold text-gray-700">
                      {historyData.map((tx) => (
                        <tr key={tx.id} className="hover:bg-gray-50/50 transition-colors">
                          <td className="px-4 py-3.5 text-gray-500 whitespace-nowrap">
                            {tx.createdAt ? new Date(tx.createdAt).toLocaleString() : 'N/A'}
                          </td>
                          <td className="px-4 py-3.5">
                            <div className="flex flex-col">
                              <span className="font-bold text-gray-900">
                                {tx.medicineBatch?.medicine?.medicineName || 'Unknown Product'}
                              </span>
                              <span className="text-[9px] text-gray-400 uppercase">
                                Batch: {tx.medicineBatch?.batchNumber || 'N/A'}
                              </span>
                            </div>
                          </td>
                          <td className="px-4 py-3.5 text-center">
                            <span
                              className={`px-2 py-0.5 rounded-full text-[8px] font-black uppercase ${
                                tx.referenceType === 'SUPPLIER_RETURN'
                                  ? 'bg-amber-100 text-amber-800 border border-amber-200'
                                  : 'bg-indigo-100 text-indigo-800 border border-indigo-200'
                              }`}
                            >
                              {tx.referenceType}
                            </span>
                          </td>
                          <td
                            className={`px-4 py-3.5 text-right font-black ${
                              parseFloat(tx.quantity) > 0 ? 'text-emerald-600' : 'text-red-600'
                            }`}
                          >
                            {parseFloat(tx.quantity) > 0 ? '+' : ''}
                            {parseFloat(tx.quantity)} Units
                          </td>
                          <td
                            className="px-4 py-3.5 text-gray-500 max-w-xs truncate"
                            title={tx.remarks}
                          >
                            {tx.remarks}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="text-center py-16 text-gray-400 text-xs">
                  No returns or refunds have been recorded yet.
                </div>
              )}
            </div>

            {/* Pagination footer */}
            {historyTotalPages > 1 && (
              <div className="px-6 py-4 border-t border-gray-100 bg-gray-50 flex items-center justify-between text-xs">
                <span className="text-gray-500 font-semibold">
                  Page {historyPage + 1} of {historyTotalPages}
                </span>
                <div className="flex gap-2">
                  <button
                    disabled={historyPage === 0}
                    onClick={() => loadHistory(historyPage - 1)}
                    className="px-3 py-1.5 bg-white border border-gray-300 hover:border-gray-900 text-gray-700 hover:text-gray-900 font-bold rounded-lg text-[10px] disabled:opacity-40 disabled:hover:border-gray-300 transition-all active:scale-95"
                  >
                    Previous
                  </button>
                  <button
                    disabled={historyPage >= historyTotalPages - 1}
                    onClick={() => loadHistory(historyPage + 1)}
                    className="px-3 py-1.5 bg-white border border-gray-300 hover:border-gray-900 text-gray-700 hover:text-gray-900 font-bold rounded-lg text-[10px] disabled:opacity-40 disabled:hover:border-gray-300 transition-all active:scale-95"
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default ReturnsView;
