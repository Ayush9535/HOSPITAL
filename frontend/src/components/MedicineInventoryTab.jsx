import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import { extractApiError } from '../utils/apiError';
import ConfirmationModal from './ConfirmationModal';
import DateSelect from './DateSelect';
import FrequencyInput from './FrequencyInput';

/**
 * @param {boolean} hidePrescribingColumns - Hide the Frequency / Duration / Manufacturer
 *   columns in Purchase History. Reception doesn't prescribe, so those columns are noise for
 *   them; the admin and doctor views keep them.
 */
// Shows/hides the "will be registered in the global catalog" hint under the medicine-name field.
const updateCatalogHint = (name, suggestions, searchingCatalog) => {
  const hint = document.getElementById('catalog-hint');
  if (!hint) return;
  const isKnown = suggestions.some((x) => x.name.toLowerCase() === name.toLowerCase());
  if (name.length >= 3 && !isKnown && !searchingCatalog) {
    hint.innerText =
      '💡 This medicine will be registered automatically in the global catalog dictionary.';
    hint.classList.remove('hidden');
  } else {
    hint.classList.add('hidden');
  }
};

const MedicineInventoryTab = ({ hidePrescribingColumns = false }) => {
  const [subTab, setSubTab] = useState('inventory'); // 'inventory' or 'purchase'

  // Data states
  const [inventoryList, setInventoryList] = useState([]);
  const [purchaseList, setPurchaseList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(null);

  // Modal states
  const [stockModal, setStockModal] = useState({ isOpen: false, isEdit: false, data: null });

  const { success, error: toastError } = useToast();

  const [confirmState, setConfirmState] = useState({
    open: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  const [stockMedicineQuery, setStockMedicineQuery] = useState('');
  const [showStockSuggestions, setShowStockSuggestions] = useState(false);
  const [suggestions, setSuggestions] = useState([]);
  const [searchingCatalog, setSearchingCatalog] = useState(false);
  const [stockFormState, setStockFormState] = useState({
    type: 'Tablet',
    manufacturer: '',
    minStockLevel: '10',
    defaultDosage: '',
    defaultFrequency: '',
    defaultDuration: '',
  });
  const [expiryDate, setExpiryDate] = useState('');

  useEffect(() => {
    if (stockModal.isOpen) {
      setStockMedicineQuery(stockModal.data?.name || '');
      setStockFormState({
        type: stockModal.data?.type || 'Tablet',
        manufacturer: stockModal.data?.manufacturer || '',
        minStockLevel: stockModal.data?.minStockLevel?.toString() || '10',
        defaultDosage: stockModal.data?.defaultDosage || '',
        defaultFrequency: stockModal.data?.defaultFrequency || '',
        defaultDuration: stockModal.data?.defaultDuration || '',
      });
      setExpiryDate(stockModal.data?.expiryDate || '');
    } else {
      setStockMedicineQuery('');
    }
    setShowStockSuggestions(false);
    setSuggestions([]);
  }, [stockModal.isOpen, stockModal.data]);

  // Debounced medicine search from central catalog
  useEffect(() => {
    const query = stockMedicineQuery.trim();
    if (query.length < 3) {
      setSuggestions([]);
      return;
    }

    setSearchingCatalog(true);
    const timer = setTimeout(async () => {
      try {
        const res = await hospitalService.searchMedicines(query);
        setSuggestions(res || []);
      } catch (err) {
        console.error('Error searching medicines:', err);
      } finally {
        setSearchingCatalog(false);
      }
    }, 300);

    return () => clearTimeout(timer);
  }, [stockMedicineQuery]);

  // Handle catalog hint display reactively
  useEffect(() => {
    updateCatalogHint(stockMedicineQuery.trim(), suggestions, searchingCatalog);
  }, [stockMedicineQuery, suggestions, searchingCatalog]);

  // Fetch active stock inventory
  // Errors are deliberately NOT caught here. They used to be, and swallowing them left the
  // list at whatever it already held -- [] on first load -- so a server that could not be
  // reached rendered as a pharmacy stocking nothing at all. A read failure and an empty
  // inventory are different facts and a nurse or pharmacist must be able to tell them apart.
  const fetchInventory = async () => {
    const res = await hospitalService.getInventoryMedicines();
    setInventoryList(res || []);
  };

  // Fetch purchases history
  const fetchPurchases = async () => {
    const res = await hospitalService.getMedicinePurchases();
    setPurchaseList(res || []);
  };

  const loadData = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      if (subTab === 'inventory') {
        await fetchInventory();
      } else if (subTab === 'purchase') {
        await fetchPurchases();
      }
    } catch (err) {
      // ERROR is its own state, alongside loading / empty / loaded.
      setLoadError(extractApiError(err, 'Failed to load medicine inventory data.'));
      toastError(extractApiError(err, 'Failed to load medicine inventory data.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [subTab]);

  // Handle Stock Intake / Purchase Save
  const handleStockSubmit = async (e) => {
    e.preventDefault();
    const form = e.target;
    const medicineName = form.medicineName.value.trim();
    const type = form.type.value;
    const stockQuantity = parseInt(form.stockQuantity.value);
    const unitPrice = parseFloat(form.unitPrice.value);
    const minStockLevel = parseInt(form.minStockLevel.value);
    const expiryDate = form.expiryDate.value;
    const manufacturer = form.manufacturer?.value?.trim() || '';
    const defaultDosage = form.defaultDosage?.value?.trim() || '';
    // Frequency is captured by the FrequencyInput control (controlled state), not a named form field.
    const defaultFrequency = (stockFormState.defaultFrequency || '').trim();
    const defaultDuration = form.defaultDuration?.value?.trim() || '';

    if (!medicineName) return;

    const payload = {
      name: medicineName,
      type,
      quantity: stockQuantity, // mapped to quantity in purchase schema
      unitPrice,
      minStockLevel,
      expiryDate: expiryDate ? expiryDate : null,
      manufacturer: manufacturer || null,
      defaultDosage: defaultDosage || null,
      defaultFrequency: defaultFrequency || null,
      defaultDuration: defaultDuration || null,
    };

    try {
      setLoading(true);
      await hospitalService.addMedicinePurchase(payload);
      success('Purchase recorded and stock inventory updated.');
      setStockModal({ isOpen: false, isEdit: false, data: null });
      loadData();
    } catch (err) {
      toastError(err.response?.data?.error || 'Failed to record medicine purchase.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="p-6 bg-white rounded-2xl border border-gray-200/80 shadow-sm space-y-6">
      {/* Header and Toggle Controls */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 border-b border-gray-100 pb-4">
        <div>
          <h2 className="text-xl font-bold text-gray-900">In-Clinic Medicine Inventory</h2>
          <p className="text-sm text-gray-500">
            Manage active in-clinic physical stock levels and purchase history.
          </p>
        </div>

        {/* Segmented Top-Tab Toggle */}
        <div className="flex bg-gray-100 p-1 rounded-xl w-full sm:w-auto overflow-x-auto whitespace-nowrap scrollbar-none">
          <button
            type="button"
            onClick={() => setSubTab('inventory')}
            className={`flex-1 sm:flex-none px-3 sm:px-5 py-2 text-sm font-semibold rounded-lg transition-all whitespace-nowrap ${subTab === 'inventory' ? 'bg-white text-teal-700 shadow-sm' : 'text-gray-600 hover:text-gray-900'}`}
          >
            Inventory
          </button>
          <button
            type="button"
            onClick={() => setSubTab('purchase')}
            className={`flex-1 sm:flex-none px-3 sm:px-5 py-2 text-sm font-semibold rounded-lg transition-all whitespace-nowrap ${subTab === 'purchase' ? 'bg-white text-teal-700 shadow-sm' : 'text-gray-600 hover:text-gray-900'}`}
          >
            Purchase History
          </button>
        </div>
      </div>

      {/* Quick Actions Panel */}
      <div className="flex justify-between items-center bg-teal-50/40 p-4 rounded-xl border border-teal-100/60">
        <div className="text-sm text-teal-800 font-medium">
          {subTab === 'inventory' &&
            `Displaying ${inventoryList.filter((x) => x.isActive !== false).length} active stock items in-clinic`}
          {subTab === 'purchase' && `Displaying ${purchaseList.length} purchase ledger entries`}
        </div>
        {subTab === 'purchase' && (
          <button
            type="button"
            onClick={() => setStockModal({ isOpen: true, isEdit: false, data: null })}
            className="px-4 py-2 bg-teal-600 text-white rounded-lg hover:bg-teal-700 transition font-semibold text-sm shadow-md shadow-teal-600/10 active:scale-95"
          >
            + Add Stock (Purchase Intake)
          </button>
        )}
      </div>

      {/* Main Tables */}
      {loadError ? (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-5 text-center"
        >
          <p className="text-sm font-semibold text-red-800">Couldn&apos;t load medicines</p>
          <p className="mt-1 text-sm text-red-700">{loadError}</p>
          <button
            type="button"
            onClick={loadData}
            className="mt-3 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700"
          >
            Retry
          </button>
        </div>
      ) : loading && inventoryList.length === 0 && purchaseList.length === 0 ? (
        <div className="space-y-3">
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
        </div>
      ) : subTab === 'inventory' ? (
        /* INVENTORY TAB LIST */
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left [&_th]:px-3 [&_th]:whitespace-nowrap [&_td]:px-3">
            <thead>
              <tr className="border-b border-gray-200 text-gray-500 font-medium">
                <th className="pb-3 text-left">Medicine Name</th>
                <th className="pb-3 text-center">Type</th>
                <th className="pb-3 text-center">Quantity</th>
                <th className="pb-3 text-right">Unit Price</th>
                <th className="pb-3 text-center">Expiry Date</th>
                <th className="pb-3 text-center">Stock Level</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {inventoryList
                .filter((x) => x.isActive !== false)
                .map((item) => {
                  const isLow = item.stockQuantity <= item.minStockLevel;
                  return (
                    <tr key={item.id} className="hover:bg-slate-50/50 transition">
                      <td className="py-3 font-semibold text-gray-800">{item.name}</td>
                      <td className="py-3 text-center text-gray-600">
                        <span className="px-2 py-0.5 text-xs bg-slate-100 rounded-full font-medium">
                          {item.type || 'Tablet'}
                        </span>
                      </td>
                      <td className="py-3 text-center font-bold text-gray-900">
                        {item.stockQuantity}
                      </td>
                      <td className="py-3 text-right text-gray-900 font-medium">
                        ₹{item.unitPrice?.toFixed(2)}
                      </td>
                      <td className="py-3 text-center text-gray-500">
                        {item.expiryDate ? new Date(item.expiryDate).toLocaleDateString() : '-'}
                      </td>
                      <td className="py-3 text-center">
                        {isLow ? (
                          <span className="px-2 py-1 text-xs font-bold bg-amber-50 text-amber-700 border border-amber-200 rounded-full">
                            Low (Min: {item.minStockLevel})
                          </span>
                        ) : (
                          <span className="px-2 py-1 text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-full">
                            Good (Min: {item.minStockLevel})
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              {inventoryList.filter((x) => x.isActive !== false).length === 0 && (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-gray-400">
                    No stock items in inventory. Record purchases in the &quot;Purchase
                    History&quot; tab to add stock.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ) : (
        /* PURCHASE HISTORY TAB LIST */
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left [&_th]:px-3 [&_th]:whitespace-nowrap [&_td]:px-3">
            <thead>
              <tr className="border-b border-gray-200 text-gray-500 font-medium">
                <th className="pb-3 text-left">Medicine Name</th>
                <th className="pb-3 text-center">Type</th>
                <th className="pb-3 text-center">Quantity Purchased</th>
                <th className="pb-3 text-right">Unit Price</th>
                <th className="pb-3 text-right">Total Cost</th>
                <th className="pb-3 text-center">Expiry Date</th>
                <th className="pb-3 text-left">Dosage</th>
                {!hidePrescribingColumns && (
                  <>
                    <th className="pb-3 text-left">Frequency</th>
                    <th className="pb-3 text-left">Duration</th>
                    <th className="pb-3 text-left">Manufacturer</th>
                  </>
                )}
                <th className="pb-3 text-center">Purchase Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {purchaseList.map((item) => {
                const totalCost = item.quantity * item.unitPrice;
                return (
                  <tr key={item.id} className="hover:bg-slate-50/50 transition">
                    <td className="py-3 font-semibold text-gray-800">{item.name}</td>
                    <td className="py-3 text-center text-gray-600">
                      <span className="px-2 py-0.5 text-xs bg-slate-100 rounded-full font-medium">
                        {item.type || 'Tablet'}
                      </span>
                    </td>
                    <td className="py-3 text-center font-bold text-gray-900">{item.quantity}</td>
                    <td className="py-3 text-right text-gray-900 font-medium">
                      ₹{item.unitPrice?.toFixed(2)}
                    </td>
                    <td className="py-3 text-right text-teal-700 font-semibold">
                      ₹{totalCost.toFixed(2)}
                    </td>
                    <td className="py-3 text-center text-gray-500">
                      {item.expiryDate ? new Date(item.expiryDate).toLocaleDateString() : '-'}
                    </td>
                    <td className="py-3 text-left text-gray-600">{item.defaultDosage || '-'}</td>
                    {!hidePrescribingColumns && (
                      <>
                        <td className="py-3 text-left text-gray-600">
                          {item.defaultFrequency || '-'}
                        </td>
                        <td className="py-3 text-left text-gray-600">
                          {item.defaultDuration || '-'}
                        </td>
                        <td className="py-3 text-left text-gray-500">{item.manufacturer || '-'}</td>
                      </>
                    )}
                    <td className="py-3 text-center text-gray-500">
                      {item.purchaseDate ? new Date(item.purchaseDate).toLocaleString() : '-'}
                    </td>
                  </tr>
                );
              })}
              {purchaseList.length === 0 && (
                <tr>
                  <td
                    colSpan={hidePrescribingColumns ? 8 : 11}
                    className="py-8 text-center text-gray-400"
                  >
                    No purchase history found. Click &quot;+ Add Stock (Purchase Intake)&quot; to
                    record a purchase.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* MODAL 1: ADD/EDIT ACTIVE INVENTORY STOCK */}
      {stockModal.isOpen && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg max-h-[90vh] overflow-y-auto animate-fade-in-up">
            <div className="p-6 border-b border-gray-100 flex justify-between items-center bg-slate-50">
              <h3 className="text-lg font-bold text-gray-800">
                {stockModal.isEdit ? 'Edit Stock Details' : 'Add Stock Intake'}
              </h3>
              <button
                type="button"
                onClick={() => setStockModal({ isOpen: false, isEdit: false, data: null })}
                className="text-gray-400 hover:text-gray-600"
              >
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

            <form onSubmit={handleStockSubmit} className="p-6 space-y-4">
              {/* Medicine Name Autocomplete Input */}
              <div className="relative">
                <label htmlFor="fld-22" className="block text-sm font-semibold text-gray-700 mb-1">
                  Medicine Name <span className="text-red-600">*</span>
                </label>
                <input
                  id="fld-22"
                  type="text"
                  name="medicineName"
                  placeholder="Type medicine name..."
                  required
                  disabled={stockModal.isEdit}
                  value={stockMedicineQuery}
                  onChange={(e) => {
                    setStockMedicineQuery(e.target.value);
                    setShowStockSuggestions(true);
                  }}
                  onFocus={() => setShowStockSuggestions(true)}
                  onBlur={() => {
                    setTimeout(() => setShowStockSuggestions(false), 200);
                  }}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none disabled:bg-gray-100"
                />
                {showStockSuggestions && stockMedicineQuery.trim().length >= 3 && (
                  <div className="absolute left-0 right-0 mt-1 max-h-40 overflow-y-auto bg-white rounded-lg border border-gray-200 shadow-lg z-50 divide-y divide-gray-100">
                    {searchingCatalog ? (
                      <div className="p-2.5 text-center text-xs text-gray-400">Searching...</div>
                    ) : suggestions.length > 0 ? (
                      suggestions.map((c) => (
                        <button
                          key={c.id}
                          type="button"
                          onMouseDown={() => {
                            setStockMedicineQuery(c.name);
                            setShowStockSuggestions(false);
                            setStockFormState({
                              type: c.type || 'Tablet',
                              manufacturer: '',
                              minStockLevel: '10',
                              defaultDosage: '',
                              defaultFrequency: '',
                              defaultDuration: '',
                            });
                            const hint = document.getElementById('catalog-hint');
                            if (hint) hint.classList.add('hidden');
                          }}
                          className="w-full text-left px-4 py-2 hover:bg-slate-50 text-sm font-medium text-gray-800"
                        >
                          {c.name}{' '}
                          <span className="text-xs text-gray-400 font-normal">({c.type})</span>
                        </button>
                      ))
                    ) : (
                      <div className="p-2.5 text-center text-xs text-gray-400">
                        No matching catalog medicine.
                      </div>
                    )}
                  </div>
                )}
                <p id="catalog-hint" className="text-xs text-amber-600 font-medium mt-1 hidden"></p>
              </div>

              {/* Type Select */}
              <div>
                <label htmlFor="fld-21" className="block text-sm font-semibold text-gray-700 mb-1">
                  Type <span className="text-red-600">*</span>
                </label>
                <select
                  id="fld-21"
                  name="type"
                  required
                  value={stockFormState.type}
                  onChange={(e) => setStockFormState((prev) => ({ ...prev, type: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                >
                  <option value="Tablet">Tablet</option>
                  <option value="Capsule">Capsule</option>
                  <option value="Syrup">Syrup</option>
                  <option value="Injection">Injection</option>
                  <option value="Saline">Saline</option>
                  <option value="Cream">Cream</option>
                  <option value="Other">Other</option>
                </select>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label
                    htmlFor="fld-20"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Quantity <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-20"
                    type="number"
                    name="stockQuantity"
                    min="1"
                    required
                    placeholder="0"
                    defaultValue={
                      stockModal.data?.stockQuantity !== undefined
                        ? stockModal.data.stockQuantity
                        : ''
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
                <div>
                  <label
                    htmlFor="fld-19"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Unit Price (₹) <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-19"
                    type="number"
                    name="unitPrice"
                    step="0.01"
                    min="0"
                    required
                    placeholder="0.00"
                    defaultValue={
                      stockModal.data?.unitPrice !== undefined ? stockModal.data.unitPrice : ''
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label
                    htmlFor="fld-18"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Low Stock Warning Limit <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-18"
                    type="number"
                    name="minStockLevel"
                    min="0"
                    required
                    placeholder="10"
                    value={stockFormState.minStockLevel}
                    onChange={(e) =>
                      setStockFormState((prev) => ({ ...prev, minStockLevel: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
                <div>
                  <label
                    htmlFor="fld-17"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Expiry Date
                  </label>
                  <DateSelect value={expiryDate} onChange={setExpiryDate} />
                  <input type="hidden" name="expiryDate" value={expiryDate} />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label
                    htmlFor="fld-16"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Manufacturer
                  </label>
                  <input
                    id="fld-16"
                    type="text"
                    name="manufacturer"
                    placeholder="e.g. Generic"
                    value={stockFormState.manufacturer}
                    onChange={(e) =>
                      setStockFormState((prev) => ({ ...prev, manufacturer: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
                <div>
                  <label
                    htmlFor="fld-15"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Default Dosage
                  </label>
                  <input
                    id="fld-15"
                    type="text"
                    name="defaultDosage"
                    placeholder="e.g. 500mg"
                    value={stockFormState.defaultDosage}
                    onChange={(e) =>
                      setStockFormState((prev) => ({ ...prev, defaultDosage: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <span className="block text-sm font-semibold text-gray-700 mb-1">
                    Default Frequency
                  </span>
                  <div className="w-full border border-gray-300 rounded-lg px-3 py-2">
                    <FrequencyInput
                      value={stockFormState.defaultFrequency}
                      onChange={(val) =>
                        setStockFormState((prev) => ({ ...prev, defaultFrequency: val }))
                      }
                    />
                  </div>
                </div>
                <div>
                  <label
                    htmlFor="fld-14"
                    className="block text-sm font-semibold text-gray-700 mb-1"
                  >
                    Default Duration
                  </label>
                  <input
                    id="fld-14"
                    type="text"
                    name="defaultDuration"
                    placeholder="e.g. 5 Days"
                    value={stockFormState.defaultDuration}
                    onChange={(e) =>
                      setStockFormState((prev) => ({ ...prev, defaultDuration: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
              </div>

              <div className="pt-4 flex gap-3">
                <button
                  type="button"
                  onClick={() => setStockModal({ isOpen: false, isEdit: false, data: null })}
                  className="flex-1 px-4 py-2.5 border border-gray-300 text-gray-700 rounded-lg font-semibold hover:bg-slate-50 transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 px-4 py-2.5 bg-teal-600 text-white rounded-lg font-semibold hover:bg-teal-700 transition shadow-md shadow-teal-600/10"
                >
                  {stockModal.isEdit ? 'Save Changes' : 'Restock / Intake'}
                </button>
              </div>
            </form>
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
    </div>
  );
};

export default MedicineInventoryTab;
