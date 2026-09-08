import React, { useState, useEffect, useCallback, useRef } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import hospitalService from '../../../services/hospitalService';
import inventoryApi from '../../../services/pharmacy/inventoryApi';
import salesApi from '../../../services/pharmacy/salesApi';
import { printBlob } from '../../../utils/printPdf';

const BillingCounterView = ({ initialData }) => {
  const user = authService.getCurrentUser();
  const isStandalonePharmacy =
    user?.modules?.includes('PHARMACY') && !user?.modules?.includes('OPD');

  const toast = useToast();
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Patient State
  const [patientSearch, setPatientSearch] = useState('');
  const [billingMode, setBillingMode] = useState('WALK_IN'); // 'WALK_IN' or 'PRESCRIPTION'
  const [pendingPrescriptions, setPendingPrescriptions] = useState([]);
  const [selectedRx, setSelectedRx] = useState(null);
  const [patientId, setPatientId] = useState(null);
  const [doctorId, setDoctorId] = useState(null);

  // Fetch Pending Prescriptions
  useEffect(() => {
    const fetchRx = async () => {
      try {
        const data = await hospitalService.getPendingPrescriptions();
        const grouped = [];
        const seen = new Set();
        (data || []).forEach((item) => {
          const key = item.medicalRecordId;
          if (key && !seen.has(key)) {
            seen.add(key);
            grouped.push({
              medicalRecordId: key,
              patientName: item.patientName || 'Unknown Patient',
              patientAge: item.patientAge || 'N/A',
              patientGender: item.patientGender || 'N/A',
              doctorName: item.doctorName || 'Unknown Doctor',
              patientId: item.patientId || null,
              doctorId: item.doctorId || null,
              medicines: (data || []).filter((m) => m.medicalRecordId === key),
            });
          }
        });
        setPendingPrescriptions(grouped);
      } catch (err) {
        console.error('Error fetching prescriptions:', err);
      }
    };
    if (billingMode === 'PRESCRIPTION') {
      fetchRx();
    }
  }, [billingMode]);

  // Load prescription medicines into cart
  const loadPrescriptionItems = async (rx) => {
    setBillItems([]);
    const newCartItems = [];
    toast.info('Loading prescribed items & auto-assigning FEFO stock batches...');

    for (const med of rx.medicines) {
      try {
        const batches = await inventoryApi.searchBatches(med.medicineName);
        if (batches && batches.length > 0) {
          const batch = batches[0]; // Oldest active batch by FEFO

          // Default to 10 or parse if possible
          let qty = 10;
          if (med.dosage && med.dosage.toLowerCase().includes('tab')) qty = 10;

          newCartItems.push({
            batchId: batch.id,
            medicineId: batch.medicineId,
            name: batch.medicine?.medicineName || med.medicineName,
            batch: batch.batchNumber,
            expiry: batch.expiryDate,
            qty: qty,
            price: batch.sellingPrice,
            taxPercent:
              batch.gstPercentage !== null && batch.gstPercentage !== undefined
                ? batch.gstPercentage
                : batch.medicine?.gstPercentage || 0,
            discount: 0,
            total: qty * batch.sellingPrice,
            outOfStock: false,
          });
        } else {
          newCartItems.push({
            batchId: null,
            medicineId: null,
            name: med.medicineName,
            batch: 'OUT OF STOCK',
            expiry: '-',
            qty: 0,
            price: 0,
            taxPercent: 0,
            discount: 0,
            total: 0,
            outOfStock: true,
          });
        }
      } catch (err) {
        console.error('Error loading batch for prescription item:', med.medicineName, err);
      }
    }

    setBillItems(newCartItems);
    calculateTotals(newCartItems);
    toast.success('Prescribed items loaded successfully!');
  };

  // Item Search State
  const [itemSearch, setItemSearch] = useState('');
  const [itemResults, setItemResults] = useState([]);
  // When a suggestion is picked we set itemSearch to its name; this flag stops that programmatic
  // change from re-triggering the search effect (which would reopen the just-closed dropdown).
  const suppressItemSearchRef = useRef(false);
  const [selectedBatch, setSelectedBatch] = useState(null);
  const [sellQty, setSellQty] = useState(1);

  // Bill State
  const [billItems, setBillItems] = useState([]);
  const [paymentMethod, setPaymentMethod] = useState('CASH');
  const [lastSavedSaleId, setLastSavedSaleId] = useState(null);

  useEffect(() => {
    if (initialData) {
      if (initialData.patient) {
        setPatientSearch(initialData.patient.patientName);
      }
    }
  }, [initialData]);

  // Totals
  const [totals, setTotals] = useState({
    subtotal: 0,
    tax: 0,
    discount: 0,
    net: 0,
  });

  const [billDiscount, setBillDiscount] = useState(0);

  const calculateTotals = useCallback(
    (items, manualDisc = billDiscount) => {
      let sub = 0;
      let tax = 0;
      let disc = parseFloat(manualDisc || 0);

      items.forEach((item) => {
        const lineSub = item.qty * item.price;
        const lineTax = (lineSub * (item.taxPercent || 0)) / 100;
        sub += lineSub;
        tax += lineTax;
      });

      setTotals({
        subtotal: sub,
        tax: tax,
        discount: disc,
        net: Math.max(0, sub + tax - disc),
      });
    },
    [billDiscount]
  );

  const handleDiscountChange = (val) => {
    const numDisc = parseFloat(val) || 0;
    setBillDiscount(numDisc);
    calculateTotals(billItems, numDisc);
  };

  // Item Search (FEFO Autocomplete)
  useEffect(() => {
    if (suppressItemSearchRef.current) {
      suppressItemSearchRef.current = false;
      return;
    }
    const timer = setTimeout(async () => {
      if (itemSearch.length > 1) {
        try {
          const data = await inventoryApi.searchBatches(itemSearch);
          setItemResults(data || []);
        } catch (err) {}
      } else {
        setItemResults([]);
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [itemSearch]);

  const addToCart = () => {
    if (!selectedBatch) {
      toast.error('Please select a medicine batch first');
      return;
    }

    if (sellQty <= 0 || sellQty > selectedBatch.currentQuantity) {
      toast.error('Invalid quantity or insufficient stock');
      return;
    }

    // Check if item already in cart
    const existingIdx = billItems.findIndex((i) => i.batchId === selectedBatch.id);
    let newItems;
    if (existingIdx > -1) {
      newItems = [...billItems];
      newItems[existingIdx].qty += parseFloat(sellQty);
      newItems[existingIdx].total = newItems[existingIdx].qty * newItems[existingIdx].price;
    } else {
      const newItem = {
        batchId: selectedBatch.id,
        medicineId: selectedBatch.medicineId,
        name: selectedBatch.medicine?.medicineName,
        batch: selectedBatch.batchNumber,
        expiry: selectedBatch.expiryDate,
        qty: parseFloat(sellQty),
        price: selectedBatch.sellingPrice,
        taxPercent:
          selectedBatch.gstPercentage !== null && selectedBatch.gstPercentage !== undefined
            ? selectedBatch.gstPercentage
            : selectedBatch.medicine?.gstPercentage || 0,
        discount: 0,
        total: parseFloat(sellQty) * selectedBatch.sellingPrice,
      };
      newItems = [...billItems, newItem];
    }

    setBillItems(newItems);
    calculateTotals(newItems);

    // Reset Item Pickers
    setItemSearch('');
    setSelectedBatch(null);
    setSellQty(1);
  };

  const removeItem = (idx) => {
    const newItems = billItems.filter((_, i) => i !== idx);
    setBillItems(newItems);
    calculateTotals(newItems);
  };

  const handlePrintInvoice = async (saleId) => {
    const idToPrint = saleId || lastSavedSaleId;
    if (!idToPrint) {
      toast.error('No completed sale invoice to print!');
      return;
    }

    try {
      const blob = await salesApi.downloadPDF(idToPrint);
      // Print (open the print dialog) instead of downloading — same behaviour as consultation
      // complete. printBlob renders the PDF in a hidden iframe and fires the browser print dialog.
      await printBlob(new Blob([blob], { type: 'application/pdf' }));
    } catch (err) {
      toast.error('Failed to print invoice');
    }
  };

  const handleCompleteSale = async () => {
    if (billItems.length === 0) {
      toast.error('Bill is empty!');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = {
        patientId: patientId,
        // Patient name is optional for a walk-in counter sale; label it generically when blank.
        patientName: patientSearch.trim() || 'Walk-in Customer',
        paymentMethod: paymentMethod,
        isIpdBill: paymentMethod === 'IPD_BILL',
        ipdAdmissionId: null,
        prescriptionId: selectedRx ? selectedRx.medicalRecordId : null,
        doctorId: doctorId,
        subtotal: totals.subtotal,
        taxAmount: totals.tax,
        discountAmount: totals.discount,
        netAmount: totals.net,
        items: billItems
          .filter((i) => !i.outOfStock)
          .map((i) => ({
            medicineId: i.medicineId,
            medicineBatchId: i.batchId,
            quantity: i.qty,
            unitPrice: i.price,
            taxPercentage: i.taxPercent,
            taxAmount: (i.qty * i.price * i.taxPercent) / 100,
            discountPercentage: 0,
            discountAmount: 0,
            totalAmount: i.total,
          })),
      };

      const savedSale = await salesApi.create(payload);
      toast.success('Bill completed successfully!');
      if (savedSale && savedSale.id) {
        setLastSavedSaleId(savedSale.id);
        // Automatically download/print the receipt PDF
        handlePrintInvoice(savedSale.id);
      }

      // Reset everything
      setBillItems([]);
      setPatientSearch('');
      setPatientId(null);
      setDoctorId(null);
      setSelectedRx(null);
      setBillDiscount(0);
      setTotals({ subtotal: 0, tax: 0, discount: 0, net: 0 });
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to complete sale');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="h-full flex flex-col gap-4 -mt-2">
      {/* Top Patient Bar & Mode Switcher */}
      <div className="bg-white border border-gray-200 rounded-lg p-3 flex flex-wrap items-center justify-between gap-4 shadow-sm">
        <div className="flex items-center gap-4 flex-1">
          {/* Segmented Mode Switcher */}
          {!isStandalonePharmacy && (
            <div className="flex gap-1 p-1 bg-gray-100 rounded-lg border border-gray-200">
              <button
                onClick={() => {
                  setBillingMode('WALK_IN');
                  setPatientSearch('');
                  setPatientId(null);
                  setDoctorId(null);
                  setSelectedRx(null);
                  setBillItems([]);
                }}
                className={`px-4 py-1.5 rounded-md font-bold text-xs transition-all ${billingMode === 'WALK_IN' ? 'bg-white text-gray-900 shadow-sm border border-gray-200/50' : 'text-gray-500 hover:text-gray-900'}`}
              >
                🚶‍♂️ Walk-In Customer
              </button>
              <button
                onClick={() => {
                  setBillingMode('PRESCRIPTION');
                  setPatientSearch('');
                  setPatientId(null);
                  setDoctorId(null);
                  setSelectedRx(null);
                  setBillItems([]);
                }}
                className={`px-4 py-1.5 rounded-md font-bold text-xs transition-all ${billingMode === 'PRESCRIPTION' ? 'bg-white text-gray-900 shadow-sm border border-gray-200/50' : 'text-gray-500 hover:text-gray-900'}`}
              >
                📋 Doctor Prescription
              </button>
            </div>
          )}

          {/* Conditional Name Inputs */}
          {billingMode === 'WALK_IN' ? (
            <div className="relative w-80 animate-in fade-in duration-200">
              <input
                type="text"
                placeholder="Enter Walk-In Patient Name..."
                value={patientSearch}
                onChange={(e) => setPatientSearch(e.target.value)}
                className="pl-4 pr-4 py-2 border border-gray-300 rounded-lg w-full text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all font-semibold"
              />
            </div>
          ) : (
            <div className="relative w-96 animate-in fade-in duration-200">
              <select
                onChange={(e) => {
                  const rxId = e.target.value;
                  if (rxId) {
                    const rx = pendingPrescriptions.find(
                      (p) => p.medicalRecordId.toString() === rxId
                    );
                    if (rx) {
                      setSelectedRx(rx);
                      setPatientSearch(rx.patientName);
                      setPatientId(rx.patientId);
                      setDoctorId(rx.doctorId);
                      loadPrescriptionItems(rx);
                    }
                  } else {
                    setSelectedRx(null);
                    setPatientSearch('');
                    setPatientId(null);
                    setDoctorId(null);
                    setBillItems([]);
                  }
                }}
                className="pl-3 pr-8 py-2 border border-gray-300 rounded-lg w-full text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all font-bold text-gray-700 bg-white cursor-pointer"
              >
                <option value="">-- Choose Pending Doctor Prescription --</option>
                {pendingPrescriptions.map((rx) => (
                  <option key={rx.medicalRecordId} value={rx.medicalRecordId}>
                    {rx.patientName} (Dr. {rx.doctorName})
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
        {!isStandalonePharmacy && (
          <div className="flex gap-2">
            <span
              className={`text-xs font-black px-2 py-1 rounded transition-colors uppercase tracking-wider ${billingMode === 'WALK_IN' ? 'bg-gray-100 text-gray-700' : 'bg-blue-100 text-blue-700'}`}
            >
              {billingMode === 'WALK_IN' ? 'Walk-in Mode' : 'Hospital Rx Mode'}
            </span>
          </div>
        )}
      </div>

      <div className="flex flex-1 min-h-0 flex-col lg:flex-row gap-4">
        {/* MAIN SECTION: Unified POS Billing Grid */}
        <div className="lg:flex-1 flex flex-col gap-4 min-h-0">
          {/* Unified Grid Table Card — only the item list scrolls; the action bar stays visible. */}
          <div className="bg-white border border-gray-200 rounded-xl flex flex-col flex-1 min-h-0 overflow-hidden shadow-sm">
            <div className="px-4 py-4 border-b border-gray-100 bg-gray-50/50 flex justify-between items-center">
              <h3 className="font-bold text-gray-800 flex items-center gap-2">
                <svg
                  className="w-5 h-5 text-gray-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z"
                  />
                </svg>
                Pharmacy Sales Billing Grid
              </h3>
              <div className="flex items-center gap-4">
                <span className="bg-blue-100 text-blue-700 px-2 py-0.5 rounded text-[10px] font-bold">
                  Total Items: {billItems.length}
                </span>
              </div>
            </div>
            <div className="overflow-auto flex-1 min-h-0">
              <table className="min-w-full text-sm text-left">
                <thead className="bg-gray-50 text-[10px] text-gray-400 font-bold uppercase tracking-widest sticky top-0 z-10 border-b">
                  <tr>
                    <th className="px-6 py-4 font-medium w-16 text-center">#</th>
                    <th className="px-6 py-4 font-medium">Medicine Name / Search</th>
                    <th className="px-6 py-4 font-medium text-center w-24">Qty</th>
                    <th className="px-6 py-4 font-medium text-right w-36">Selling Price</th>
                    <th className="px-6 py-4 font-medium text-right w-36">Total (incl. GST)</th>
                    <th className="px-6 py-4 text-center w-32">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {/* Unified Input Row at the Top */}
                  <tr className="bg-blue-50/20 border-b-2 border-blue-100">
                    <td className="px-6 py-4 font-bold text-gray-900 text-center">Sr.No</td>
                    <td className="px-6 py-4">
                      <div className="relative">
                        <input
                          type="text"
                          placeholder="Search medicine name or batch (FEFO ordered)..."
                          value={itemSearch}
                          onChange={(e) => setItemSearch(e.target.value)}
                          className="w-full pl-9 pr-3 py-2 border border-blue-200 rounded-lg text-xs focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none bg-white font-medium shadow-inner"
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
                        {itemResults.length > 0 && (
                          <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-xl z-20 max-h-60 overflow-auto py-1">
                            {itemResults.map((item) => (
                              <div
                                key={item.id}
                                role="button"
                                tabIndex={0}
                                onClick={() => {
                                  suppressItemSearchRef.current = true;
                                  setSelectedBatch(item);
                                  setItemSearch(item.medicine?.medicineName);
                                  setItemResults([]);
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault();
                                    e.currentTarget.click();
                                  }
                                }}
                                className="px-4 py-2 hover:bg-blue-50 cursor-pointer border-b border-gray-50 last:border-0 text-xs"
                              >
                                <div className="flex justify-between font-bold text-gray-900">
                                  <span>{item.medicine?.medicineName}</span>
                                  <span>₹{item.sellingPrice}</span>
                                </div>
                                <div className="flex justify-between text-[10px] text-gray-500 mt-0.5">
                                  <span>
                                    Batch: {item.batchNumber} • Exp: {item.expiryDate}
                                  </span>
                                  <span
                                    className={`${item.currentQuantity < 50 ? 'text-amber-600' : 'text-green-600'} font-bold`}
                                  >
                                    Stock: {item.currentQuantity}
                                  </span>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                      {selectedBatch && (
                        <div className="text-[10px] text-blue-700 font-bold mt-2 flex items-center gap-3 animate-in fade-in slide-in-from-top-1 duration-200">
                          <span className="bg-blue-100/70 px-1.5 py-0.5 rounded">
                            Batch: {selectedBatch.batchNumber}
                          </span>
                          <span className="bg-blue-100/70 px-1.5 py-0.5 rounded text-red-500">
                            Exp: {selectedBatch.expiryDate}
                          </span>
                          <span className="bg-blue-100/70 px-1.5 py-0.5 rounded text-green-700">
                            Stock: {selectedBatch.currentQuantity}
                          </span>
                        </div>
                      )}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <input
                        type="number"
                        value={sellQty}
                        onChange={(e) => setSellQty(e.target.value)}
                        disabled={!selectedBatch}
                        className="w-16 px-2 py-1.5 border border-blue-200 rounded-lg text-center text-xs font-black bg-white text-blue-900 outline-none disabled:bg-gray-50 disabled:text-gray-400 focus:ring-2 focus:ring-blue-500"
                      />
                    </td>
                    <td className="px-6 py-4 text-right text-gray-700 font-bold">
                      {selectedBatch ? `₹${selectedBatch.sellingPrice.toFixed(2)}` : '₹0.00'}
                    </td>
                    <td className="px-6 py-4 text-right font-black text-blue-900">
                      {selectedBatch
                        ? `₹${(parseFloat(sellQty || 0) * selectedBatch.sellingPrice).toFixed(2)}`
                        : '₹0.00'}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <button
                        onClick={addToCart}
                        disabled={!selectedBatch}
                        className="w-full py-2 bg-blue-600 text-white text-xs font-bold rounded-lg hover:bg-blue-700 disabled:bg-gray-100 disabled:text-gray-400 transition-all flex items-center justify-center gap-1 shadow-sm active:scale-95"
                      >
                        <svg
                          className="w-3.5 h-3.5"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2.5}
                            d="M12 4v16m8-8H4"
                          />
                        </svg>
                        Add Item
                      </button>
                    </td>
                  </tr>

                  {/* Render Current Cart Items */}
                  {billItems.length > 0 ? (
                    billItems.map((item, idx) => {
                      const gstAmount = (item.qty * item.price * (item.taxPercent || 0)) / 100;
                      const lineTotalInclGst = item.total + gstAmount;
                      return (
                        <tr
                          key={idx}
                          className={`hover:bg-gray-50 transition-colors ${item.outOfStock ? 'bg-red-50/40' : ''}`}
                        >
                          <td className="px-6 py-4 text-gray-400 text-center font-bold">
                            {idx + 1}
                          </td>
                          <td className="px-6 py-4">
                            <div className="font-bold text-gray-900 flex items-center gap-2">
                              {item.name}
                              {item.outOfStock && (
                                <span className="bg-red-100 text-red-700 border border-red-200 px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider">
                                  Out of Stock
                                </span>
                              )}
                            </div>
                            <div className="text-[10px] text-gray-500 mt-0.5 flex gap-2">
                              <span className="bg-gray-100 px-1.5 rounded">
                                Batch: {item.batch}
                              </span>
                              <span className="bg-gray-100 px-1.5 rounded text-red-500">
                                Exp: {item.expiry}
                              </span>
                              {item.taxPercent > 0 && (
                                <span className="bg-emerald-50 text-emerald-700 px-1.5 rounded font-bold">
                                  GST {item.taxPercent}%: ₹{gstAmount.toFixed(2)}
                                </span>
                              )}
                            </div>
                          </td>
                          <td className="px-6 py-4 text-center">
                            <div className="font-black text-gray-900">{item.qty}</div>
                          </td>
                          <td className="px-6 py-4 text-right text-gray-700 font-medium">
                            ₹{item.price.toFixed(2)}
                          </td>
                          <td className="px-6 py-4 text-right font-black text-gray-900">
                            ₹{lineTotalInclGst.toFixed(2)}
                            {gstAmount > 0 && (
                              <div className="text-[10px] font-medium text-gray-400">
                                ₹{item.total.toFixed(2)} + ₹{gstAmount.toFixed(2)} GST
                              </div>
                            )}
                          </td>
                          <td className="px-6 py-4 text-center">
                            <button
                              onClick={() => removeItem(idx)}
                              className="text-gray-300 hover:text-red-500 p-1.5 rounded-full hover:bg-red-50 transition-all active:scale-95"
                            >
                              <svg
                                className="w-4 h-4"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                                />
                              </svg>
                            </button>
                          </td>
                        </tr>
                      );
                    })
                  ) : (
                    <tr>
                      <td colSpan="6" className="px-6 py-16 text-center text-gray-400 italic">
                        <div className="flex flex-col items-center gap-2">
                          <svg
                            className="w-12 h-12 opacity-20"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={1}
                              d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z"
                            />
                          </svg>
                          <span>
                            Your billing cart is empty. Search and add an item using the top entry
                            row!
                          </span>
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
        {/* RIGHT SECTION: Checkout / Payment */}
        <div className="lg:w-80 space-y-4 flex flex-col min-h-0 overflow-y-auto">
          {/* Summary Card */}
          <div className="bg-white border border-gray-200 rounded-2xl overflow-hidden flex-shrink-0 shadow-sm">
            <div className="bg-gray-900 text-white p-5 text-center">
              <div className="text-[10px] uppercase font-black tracking-widest opacity-50 mb-1">
                Net Amount Payable
              </div>
              <div className="text-4xl font-black">₹{totals.net.toFixed(2)}</div>
            </div>
            <div className="p-5 space-y-3 bg-white border-b border-gray-200 text-sm">
              <div className="flex justify-between text-gray-500 font-medium">
                <span>Sub Total</span>
                <span>₹{totals.subtotal.toFixed(2)}</span>
              </div>
              <div className="flex justify-between text-gray-500 font-medium">
                <span>Tax (GST)</span>
                <span>+₹{totals.tax.toFixed(2)}</span>
              </div>
              <div className="flex justify-between text-gray-500 font-medium">
                <span>Discount</span>
                <span className="text-green-600">-₹{totals.discount.toFixed(2)}</span>
              </div>
              <div className="flex justify-between font-black text-gray-900 pt-3 border-t border-dashed border-gray-200 text-lg">
                <span>Final Bill</span>
                <span>₹{totals.net.toFixed(2)}</span>
              </div>
            </div>
            <div className="p-5 bg-gray-50/50">
              <span className="block text-[10px] font-black text-gray-400 mb-3 uppercase tracking-wider">
                Select Payment Method
              </span>
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={() => setPaymentMethod('CASH')}
                  className={`p-3 text-[11px] font-black rounded-xl border transition-all flex flex-col items-center gap-1 ${paymentMethod === 'CASH' ? 'bg-gray-900 text-white border-gray-900 shadow-md' : 'bg-white text-gray-600 border-gray-200 hover:border-gray-900'}`}
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M17 9V7a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2m2 4h10a2 2 0 002-2v-6a2 2 0 00-2-2H9a2 2 0 00-2 2v6a2 2 0 002 2zm-5-9a2 2 0 11-4 0 2 2 0 014 0z"
                    />
                  </svg>
                  Cash
                </button>
                <button
                  onClick={() => setPaymentMethod('UPI')}
                  className={`p-3 text-[11px] font-black rounded-xl border transition-all flex flex-col items-center gap-1 ${paymentMethod === 'UPI' ? 'bg-gray-900 text-white border-gray-900 shadow-md' : 'bg-white text-gray-600 border-gray-200 hover:border-gray-900'}`}
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"
                    />
                  </svg>
                  UPI / QR
                </button>
                {!isStandalonePharmacy && (
                  <button
                    onClick={() => setPaymentMethod('IPD_BILL')}
                    className={`p-3 text-[11px] font-black rounded-xl border transition-all col-span-2 flex items-center justify-center gap-2 ${paymentMethod === 'IPD_BILL' ? 'bg-gray-900 text-white border-gray-900 shadow-md' : 'bg-white text-gray-600 border-gray-200 hover:border-gray-900'}`}
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
                      />
                    </svg>
                    Post to IPD Admission Bill
                  </button>
                )}
              </div>
            </div>
            <div className="p-5 bg-gray-50/50 border-t border-gray-100">
              <label
                htmlFor="fld-211"
                className="block text-[10px] font-black text-gray-400 mb-2 uppercase tracking-wider"
              >
                Discount Amount (₹)
              </label>
              <input
                id="fld-211"
                type="number"
                placeholder="Enter discount amount..."
                value={billDiscount || ''}
                onChange={(e) => handleDiscountChange(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-xl text-xs focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none bg-white font-medium"
              />
            </div>
          </div>
        </div>
      </div>

      {/* BOTTOM ACTION BAR */}
      <div className="bg-white border border-gray-200 p-4 rounded-xl shadow-xl flex items-center justify-between flex-shrink-0">
        <div className="flex gap-3">
          <button className="px-6 py-2.5 border border-gray-200 text-gray-600 text-sm font-bold rounded-xl hover:bg-gray-50 transition-all active:scale-95">
            Hold Bill (F4)
          </button>
          <button
            onClick={() => {
              setBillItems([]);
              setTotals({ subtotal: 0, tax: 0, discount: 0, net: 0 });
            }}
            className="px-6 py-2.5 bg-red-50 text-red-600 text-sm font-bold rounded-xl hover:bg-red-100 transition-all active:scale-95"
          >
            Clear Cart
          </button>
        </div>

        <div className="flex gap-4 items-center">
          <div className="text-right hidden sm:block">
            <p className="text-[10px] font-black text-gray-400 uppercase tracking-widest">
              Total to Pay
            </p>
            <p className="text-2xl font-black text-gray-900">₹{totals.net.toFixed(2)}</p>
          </div>
          <button
            onClick={handleCompleteSale}
            disabled={isSubmitting || billItems.length === 0}
            className={`px-8 py-3 text-white text-sm font-bold rounded-xl flex items-center gap-3 transition-all active:scale-95 shadow-xl shadow-green-200 ${isSubmitting || billItems.length === 0 ? 'bg-gray-300 text-gray-500 cursor-not-allowed shadow-none' : 'bg-green-600 hover:bg-green-700'}`}
          >
            {isSubmitting ? (
              <LoadingSpinner size="xs" color="white" />
            ) : (
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M5 13l4 4L19 7"
                />
              </svg>
            )}
            Complete & Save Sale (F12)
          </button>
        </div>
      </div>
    </div>
  );
};

export default BillingCounterView;
