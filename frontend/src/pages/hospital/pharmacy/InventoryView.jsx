import React, { useState, useCallback, useEffect } from 'react';
import {
  ViewLayout,
  ViewToolbar,
  SearchInput,
} from '../../../components/pharmacy/shared/ViewComponents';
import { SkeletonTableRow, SkeletonFeed } from '../../../components/Skeleton';
import StockAdjustmentModal from '../../../components/StockAdjustmentModal';
import { useViewManager } from '../../../hooks/pharmacy/useViewManager';
import authService from '../../../services/authService';
import categoriesApi from '../../../services/pharmacy/categoriesApi';
import inventoryApi from '../../../services/pharmacy/inventoryApi';

const InventoryView = ({ onNavigate, refreshKey = 0 }) => {
  const currentUser = authService.getCurrentUser();
  // Standalone pharmacy ERP: no category filter / no manual "Add Medicine"
  // (medicines enter via Purchase). Hospital/clinic pharmacy keeps current UI.
  const isStandalonePharmacy =
    currentUser?.modules?.includes('PHARMACY') && !currentUser?.modules?.includes('OPD');
  const [categories, setCategories] = useState([]);
  const [selectedItem, setSelectedItem] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [txLoading, setTxLoading] = useState(false);
  const [isAdjustmentModalOpen, setIsAdjustmentModalOpen] = useState(false);
  const [isBatchModalOpen, setIsBatchModalOpen] = useState(false);
  const [isDetailModalOpen, setIsDetailModalOpen] = useState(false);

  const [selectedStatus, setSelectedStatus] = useState('all');
  const [selectedCategory, setSelectedCategory] = useState('all');

  // Restoration: Multi-criteria filtering logic
  const fetchFn = useCallback(
    (search, page, size) => {
      if (selectedStatus === 'low-stock') return inventoryApi.getLowStock(page, size);
      if (selectedStatus === 'expiring') return inventoryApi.getExpiring(30, page, size);
      return inventoryApi.getInventory(
        search,
        page,
        size,
        selectedCategory === 'all' ? null : selectedCategory
      );
    },
    [selectedStatus, selectedCategory]
  );

  const {
    data: inventory,
    loading,
    error,
    search,
    page,
    pageSize,
    totalPages,
    totalElements,
    handleSearch,
    handlePageChange,
    refresh,
  } = useViewManager(fetchFn, { dependencies: [selectedStatus, selectedCategory, refreshKey] });

  useEffect(() => {
    categoriesApi.getAll('', 0, 100).then((res) => setCategories(res.content || []));
  }, []);

  const fetchTransactions = useCallback(async (batchId) => {
    setTxLoading(true);
    try {
      const data = await inventoryApi.getTransactions(batchId, 0, 10);
      setTransactions(data.content || []);
    } finally {
      setTxLoading(false);
    }
  }, []);

  useEffect(() => {
    if (selectedItem) fetchTransactions(selectedItem.id);
  }, [selectedItem, fetchTransactions]);

  const handleExport = () => {
    const headers = ['Medicine', 'Batch', 'Category', 'Quantity', 'Expiry', 'MRP', 'Cost'];
    const rows = inventory.map((item) => [
      `"${item.medicine?.medicineName}"`,
      `"${item.batchNumber}"`,
      `"${item.medicine?.category?.categoryName || 'N/A'}"`,
      item.currentQuantity,
      item.expiryDate,
      item.sellingPrice,
      item.purchaseRate,
    ]);
    const csvContent = [headers, ...rows].map((e) => e.join(',')).join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `Inventory_Export_${new Date().toLocaleDateString()}.csv`;
    link.click();
  };

  const getStatusInfo = (item) => {
    const qty = item.currentQuantity || 0;
    const reorder = item.medicine?.reorderLevel || 0;
    const expiry = new Date(item.expiryDate);
    const today = new Date();
    const nearExpiry = new Date();
    nearExpiry.setDate(today.getDate() + 30);

    if (qty <= 0) return { label: 'Out of Stock', color: 'bg-red-50 text-red-700 border-red-100' };
    if (expiry < today)
      return { label: 'Expired', color: 'bg-gray-100 text-gray-700 border-gray-200' };
    if (expiry < nearExpiry)
      return { label: 'Near Expiry', color: 'bg-orange-50 text-orange-700 border-orange-100' };
    if (qty <= reorder)
      return { label: 'Low Stock', color: 'bg-amber-50 text-amber-700 border-amber-100' };
    return { label: 'In Stock', color: 'bg-green-50 text-green-700 border-green-100' };
  };

  // Dynamically group inventory batches by medicine
  const groupedInventory = [];
  const seen = new Set();
  (inventory || []).forEach((item) => {
    const medId = item.medicineId;
    if (!seen.has(medId)) {
      seen.add(medId);
      const batches = (inventory || []).filter((b) => b.medicineId === medId);
      const totalQty = batches.reduce((sum, b) => sum + parseFloat(b.currentQuantity || 0), 0);
      const totalRes = batches.reduce((sum, b) => sum + parseFloat(b.reservedQuantity || 0), 0);
      const batchList = batches
        .map((b) => `${b.batchNumber} (${parseFloat(b.currentQuantity)})`)
        .join(', ');

      // Expiry tracking: nearest active batch expiry
      let nearestExpiry = item.expiryDate;
      batches.forEach((b) => {
        if (new Date(b.expiryDate) < new Date(nearestExpiry)) {
          nearestExpiry = b.expiryDate;
        }
      });

      // Representative batch details (oldest/first active)
      const repBatch = batches[0] || item;

      groupedInventory.push({
        ...repBatch,
        currentQuantity: totalQty,
        reservedQuantity: totalRes,
        batchNumber: batchList,
        expiryDate: nearestExpiry,
        allBatches: batches,
      });
    }
  });

  return (
    <ViewLayout
      error={error}
      toolbar={
        <ViewToolbar
          left={
            <>
              <SearchInput
                placeholder="Search inventory / batch..."
                value={search}
                onChange={(e) => handleSearch(e.target.value)}
              />
              {!isStandalonePharmacy && (
                <select
                  value={selectedCategory}
                  onChange={(e) => setSelectedCategory(e.target.value)}
                  className="px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium focus:ring-2 focus:ring-gray-900/5 focus:border-gray-900 outline-none transition-all"
                >
                  <option value="all">All Categories</option>
                  {categories.map((cat) => (
                    <option key={cat.id} value={cat.id}>
                      {cat.categoryName}
                    </option>
                  ))}
                </select>
              )}
              <select
                value={selectedStatus}
                onChange={(e) => setSelectedStatus(e.target.value)}
                className="px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium focus:ring-2 focus:ring-gray-900/5 focus:border-gray-900 outline-none transition-all"
              >
                <option value="all">All Status</option>
                <option value="low-stock">Low Stock</option>
                <option value="expiring">Near Expiry</option>
                <option value="out-of-stock">Out of Stock</option>
              </select>
            </>
          }
          right={
            <div className="flex gap-2">
              <button
                onClick={handleExport}
                className="px-4 py-2 border border-gray-300 text-gray-700 text-sm font-bold rounded-lg hover:bg-gray-50 transition-colors"
              >
                Export
              </button>
              {!isStandalonePharmacy && (
                <button
                  onClick={() => onNavigate('medicine_master')}
                  className="px-4 py-2 bg-gray-900 text-white text-sm font-bold rounded-lg hover:bg-gray-800 transition-all shadow-md"
                >
                  + Add Medicine
                </button>
              )}
            </div>
          }
        />
      }
      sidePanel={null}
    >
      <div className="h-full flex flex-col overflow-hidden">
        <div className="overflow-auto flex-1">
          <table className="w-full text-left border-collapse">
            <thead className="bg-gray-50/50 sticky top-0 z-10 border-b border-gray-100">
              <tr>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">
                  Medicine
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">
                  Category
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">
                  Manufacturer
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-right">
                  Quantity
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-center">
                  Stock Status
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-center">
                  Status
                </th>
                <th className="px-6 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-center w-36">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {loading ? (
                <>
                  {Array.from({ length: 6 }).map((_, i) => (
                    <SkeletonTableRow key={i} cols={7} delay={i} />
                  ))}
                </>
              ) : (
                groupedInventory.map((item) => (
                  <tr
                    key={item.id}
                    onClick={() => {
                      setSelectedItem(item);
                      setIsDetailModalOpen(true);
                    }}
                    className="group hover:bg-blue-50/30 cursor-pointer transition-all border-b border-gray-100"
                  >
                    <td className="px-6 py-4">
                      <div className="flex flex-col">
                        <span className="font-bold text-gray-900 text-sm group-hover:text-blue-700 transition-colors">
                          {item.medicine?.medicineName}
                        </span>
                        <span className="text-[10px] text-gray-400 font-bold uppercase tracking-wide">
                          {item.medicine?.genericName || 'N/A'}
                        </span>
                      </div>
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-600">
                      {item.medicine?.category?.categoryName || 'General'}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500 font-medium">
                      {item.medicine?.manufacturer?.manufacturerName || 'N/A'}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="font-bold text-gray-900 text-sm">{item.currentQuantity}</div>
                      {item.reservedQuantity > 0 && (
                        <span className="text-[10px] text-amber-600 font-bold block">
                          Res: {item.reservedQuantity}
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-black uppercase border ${getStatusInfo(item).color}`}
                      >
                        {getStatusInfo(item).label}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-black uppercase border ${
                          item.medicine?.isActive
                            ? 'bg-green-50 text-green-700 border-green-200'
                            : 'bg-red-50 text-red-700 border-red-200'
                        }`}
                      >
                        {item.medicine?.isActive ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setSelectedItem(item);
                          setIsDetailModalOpen(true);
                        }}
                        className="px-3 py-1.5 bg-gray-900 text-white text-xs font-bold rounded-lg hover:bg-gray-800 transition-all shadow-sm active:scale-95 flex items-center gap-1.5 mx-auto"
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
                            strokeWidth={2}
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                        Details
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="px-6 py-4 border-t border-gray-100 bg-gray-50/50 flex justify-between items-center">
          <span className="text-xs text-gray-500 font-medium">
            Showing {inventory.length} of {totalElements} items
          </span>
          <div className="flex gap-2">
            <button
              disabled={page === 0}
              onClick={() => handlePageChange(page - 1)}
              className="px-3 py-1 border border-gray-200 rounded text-xs font-bold bg-white disabled:opacity-50"
            >
              Prev
            </button>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => handlePageChange(page + 1)}
              className="px-3 py-1 border border-gray-200 rounded text-xs font-bold bg-white disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      </div>

      {/* Premium Full-Width Detail Overlay Modal */}
      {isDetailModalOpen && selectedItem && (
        <div className="fixed inset-0 bg-gray-950/60 backdrop-blur-sm z-50 flex items-center justify-center p-4 overflow-y-auto animate-in fade-in duration-300">
          <div className="bg-white rounded-2xl shadow-2xl border border-gray-150 max-w-5xl w-full max-h-[90vh] flex flex-col overflow-hidden animate-in zoom-in-95 duration-300">
            {/* Modal Header */}
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex justify-between items-center shrink-0">
              <div>
                <div className="flex items-center gap-3">
                  <h3 className="text-lg font-black text-gray-900">
                    {selectedItem.medicine?.medicineName}
                  </h3>
                  <span className="bg-blue-100 text-blue-700 px-2.5 py-0.5 rounded-full text-[9px] font-black uppercase tracking-wider">
                    {selectedItem.medicine?.category?.categoryName || 'General'}
                  </span>
                </div>
                <p className="text-[10px] text-gray-400 font-bold mt-1 uppercase tracking-wide">
                  Generic: {selectedItem.medicine?.genericName || 'N/A'}
                </p>
              </div>
              <button
                onClick={() => {
                  setIsDetailModalOpen(false);
                  setSelectedItem(null);
                }}
                className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-150 rounded-full transition-all"
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

            {/* Modal Body */}
            <div className="p-6 overflow-y-auto space-y-6 flex-1 bg-white">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                {/* Column 1: Medicine Specifications */}
                <div className="space-y-4">
                  <div className="bg-gray-50/50 p-4 rounded-xl border border-gray-100">
                    <h4 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3">
                      Medicine Specifications
                    </h4>
                    <div className="space-y-2 text-xs">
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">Strength / Dosage:</span>
                        <span className="font-bold text-gray-800">
                          {selectedItem.medicine?.strength || 'N/A'}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">UOM / Package:</span>
                        <span className="font-bold text-gray-800">
                          {selectedItem.medicine?.unitOfMeasure || 'N/A'}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">Medicine Type:</span>
                        <span className="font-bold text-gray-800">
                          {selectedItem.medicine?.medicineType || 'N/A'}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">Schedule Type:</span>
                        <span className="font-bold text-red-600 font-mono">
                          {selectedItem.medicine?.scheduleType || 'OTC'}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">Reorder Alert Level:</span>
                        <span className="font-bold text-amber-600">
                          {selectedItem.medicine?.reorderLevel || 0} Units
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-gray-100 pb-1.5">
                        <span className="text-gray-400 font-medium">GST Percentage:</span>
                        <span className="font-bold text-emerald-600">
                          {selectedItem.medicine?.gstPercentage || 0}%
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-gray-400 font-medium">Prescription Required:</span>
                        <span
                          className={`font-bold ${selectedItem.medicine?.requiresPrescription ? 'text-red-500' : 'text-green-500'}`}
                        >
                          {selectedItem.medicine?.requiresPrescription ? 'YES' : 'NO'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Quick Stats Grid */}
                  <div className="grid grid-cols-2 gap-3">
                    <div className="p-3 bg-blue-50 border border-blue-100 rounded-lg text-center shadow-sm">
                      <span className="text-[9px] uppercase font-bold text-blue-600 block mb-1">
                        Total Stock
                      </span>
                      <span className="text-xl font-black text-blue-900">
                        {selectedItem.currentQuantity}
                      </span>
                    </div>
                    <div className="p-3 bg-amber-50 border border-amber-100 rounded-lg text-center shadow-sm">
                      <span className="text-[9px] uppercase font-bold text-amber-600 block mb-1">
                        Reserved
                      </span>
                      <span className="text-xl font-black text-amber-900">
                        {selectedItem.reservedQuantity || 0}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Column 2: Available Batches Breakdown */}
                <div className="space-y-4">
                  <div className="bg-gray-50/50 p-4 rounded-xl border border-gray-100 h-full flex flex-col">
                    <h4 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3 shrink-0">
                      Available Batches Breakdown
                    </h4>
                    <div className="space-y-2 flex-1 overflow-y-auto max-h-60 pr-1">
                      {selectedItem.allBatches?.map((b) => (
                        <div
                          key={b.id}
                          className="flex justify-between items-center text-xs border-b border-gray-150 pb-2 last:border-0 last:pb-0"
                        >
                          <div>
                            <span className="font-bold text-gray-900 block">{b.batchNumber}</span>
                            <span className="text-[10px] text-gray-400 block font-medium">
                              Exp: {b.expiryDate}
                            </span>
                          </div>
                          <div className="text-right">
                            <span className="font-bold text-gray-900 block">₹{b.sellingPrice}</span>
                            <span className="text-[10px] text-blue-700 font-bold bg-blue-50 px-1.5 py-0.5 rounded inline-block mt-0.5">
                              {parseFloat(b.currentQuantity)} Units
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>

                {/* Column 3: Inventory Movement Log */}
                <div className="space-y-4">
                  <div className="bg-gray-50/50 p-4 rounded-xl border border-gray-100 h-full flex flex-col">
                    <h4 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3 shrink-0">
                      Inventory Movement Logs
                    </h4>
                    <div className="space-y-3 flex-1 overflow-y-auto max-h-60 pr-1 custom-scrollbar">
                      {txLoading ? (
                        <SkeletonFeed count={3} />
                      ) : transactions.length > 0 ? (
                        transactions.map((tx) => (
                          <div key={tx.id} className="flex gap-3 text-xs">
                            <div
                              className={`mt-1.5 w-1.5 h-1.5 rounded-full shrink-0 ${
                                tx.transactionType === 'SALE'
                                  ? 'bg-red-500'
                                  : tx.transactionType === 'ADJUSTMENT'
                                    ? 'bg-blue-500'
                                    : 'bg-green-500'
                              }`}
                            ></div>
                            <div className="flex-1 border-b border-gray-100 pb-1.5 last:border-0">
                              <div className="flex justify-between items-start">
                                <p className="font-bold text-gray-900 text-[11px]">
                                  {tx.transactionType} ({tx.quantity > 0 ? '+' : ''}
                                  {tx.quantity})
                                </p>
                                <span className="text-[9px] text-gray-400">
                                  {new Date(tx.createdAt).toLocaleDateString()}
                                </span>
                              </div>
                              <p className="text-gray-500 text-[9px] mt-0.5">
                                {tx.remarks || 'Standard transaction'}
                              </p>
                              <p className="text-[8px] text-gray-400 mt-1 font-medium font-mono">
                                Stock: {tx.quantityBefore} → {tx.quantityAfter}
                              </p>
                            </div>
                          </div>
                        ))
                      ) : (
                        <div className="text-center py-8 text-gray-400 text-xs italic">
                          No transaction history recorded yet.
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Modal Footer */}
            <div className="px-6 py-4 bg-gray-50 border-t border-gray-100 flex justify-between items-center shrink-0">
              <div className="flex gap-2">
                <button
                  onClick={() => setIsAdjustmentModalOpen(true)}
                  className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-xs font-bold rounded-lg transition-all shadow-md shadow-blue-200 active:scale-95"
                >
                  Adjust Stock (Audit)
                </button>
              </div>
              <button
                onClick={() => {
                  setIsDetailModalOpen(false);
                  setSelectedItem(null);
                }}
                className="px-5 py-2 bg-gray-950 text-white text-xs font-bold rounded-lg hover:bg-gray-800 transition-all shadow-md active:scale-95"
              >
                Close Details
              </button>
            </div>
          </div>
        </div>
      )}

      <StockAdjustmentModal
        isOpen={isAdjustmentModalOpen}
        onClose={() => setIsAdjustmentModalOpen(false)}
        item={selectedItem}
        onAdjustmentComplete={refresh}
      />
    </ViewLayout>
  );
};

export default InventoryView;
