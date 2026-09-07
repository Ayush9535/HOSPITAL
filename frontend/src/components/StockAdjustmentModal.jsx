import React, { useState } from 'react';
import inventoryApi from '../services/pharmacy/inventoryApi';

const StockAdjustmentModal = ({ isOpen, onClose, item, onAdjustmentComplete }) => {
  const [adjustmentQty, setAdjustmentQty] = useState('');
  const [remarks, setRemarks] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState(null);

  if (!isOpen || !item) return null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!adjustmentQty || Number.isNaN(Number(adjustmentQty))) {
      setError('Please enter a valid quantity.');
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      await inventoryApi.adjustStock({
        batchId: item.id,
        quantity: parseFloat(adjustmentQty),
        remarks: remarks,
      });
      onAdjustmentComplete();
      onClose();
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to adjust stock. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-md max-h-[90vh] overflow-y-auto animate-in zoom-in-95 duration-200">
        <div className="p-6 border-b border-gray-100 flex justify-between items-center bg-gray-50/50">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Adjust Stock</h2>
            <p className="text-xs text-gray-500 mt-1">
              {item.medicine?.medicineName} • Batch: {item.batchNumber}
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-6">
          {error && (
            <div className="p-3 bg-red-50 border border-red-100 text-red-700 text-xs rounded-lg flex gap-2 items-start">
              <svg
                className="w-4 h-4 mt-0.5 shrink-0"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <span>{error}</span>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div className="p-3 bg-blue-50 rounded-lg border border-blue-100">
              <span className="text-[10px] uppercase font-bold text-blue-600 tracking-wider">
                Current Stock
              </span>
              <p className="text-xl font-black text-blue-900 mt-1">{item.currentQuantity}</p>
            </div>
            <div className="p-3 bg-gray-50 rounded-lg border border-gray-100">
              <span className="text-[10px] uppercase font-bold text-gray-400 tracking-wider">
                Type
              </span>
              <p className="text-sm font-bold text-gray-700 mt-1">Manual Audit</p>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <label
                htmlFor="fld-10020"
                className="block text-xs font-bold text-gray-700 uppercase tracking-wider mb-2"
              >
                Adjustment Quantity
              </label>
              <div className="relative">
                <input
                  id="fld-10020"
                  type="number"
                  step="0.01"
                  value={adjustmentQty}
                  onChange={(e) => setAdjustmentQty(e.target.value)}
                  placeholder="e.g. +10 or -5"
                  className="w-full px-4 py-3 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all font-semibold"
                  required
                />
                <div className="absolute right-4 top-1/2 -translate-y-1/2 text-xs text-gray-400 font-medium">
                  Units
                </div>
              </div>
              <p className="text-[10px] text-gray-400 mt-2 italic">
                Use positive numbers to add stock, negative to subtract.
              </p>
            </div>

            <div>
              <label
                htmlFor="fld-33"
                className="block text-xs font-bold text-gray-700 uppercase tracking-wider mb-2"
              >
                Remarks / Reason
              </label>
              <textarea
                id="fld-33"
                value={remarks}
                onChange={(e) => setRemarks(e.target.value)}
                placeholder="Reason for adjustment (e.g., Damaged, Inventory mismatch...)"
                className="w-full px-4 py-3 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all h-24 resize-none"
              ></textarea>
            </div>
          </div>

          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-3 border border-gray-200 text-gray-600 text-sm font-bold rounded-lg hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className={`flex-1 py-3 text-white text-sm font-bold rounded-lg transition-all ${isSubmitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-blue-600 hover:bg-blue-700 shadow-lg shadow-blue-200'}`}
            >
              {isSubmitting ? 'Processing...' : 'Apply Adjustment'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default StockAdjustmentModal;
