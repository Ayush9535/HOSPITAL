import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import { extractApiError } from '../utils/apiError';
import ConfirmationModal from './ConfirmationModal';
import DateSelect from './DateSelect';

const HospitalInventoryTab = () => {
  const [subTab, setSubTab] = useState('inventory'); // 'inventory', 'purchase', or 'catalog' (Services)

  // Data states
  const [inventoryList, setInventoryList] = useState([]);
  const [purchaseList, setPurchaseList] = useState([]);
  const [servicesList, setServicesList] = useState([]);
  const [globalMasterItems, setGlobalMasterItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(null);

  // Modal states
  const [stockModal, setStockModal] = useState({ isOpen: false, isEdit: false, data: null });
  const [serviceModal, setServiceModal] = useState({ isOpen: false, isEdit: false, data: null });

  const { success, error: toastError } = useToast();

  const [confirmState, setConfirmState] = useState({
    open: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  // Stock Form States
  const [stockItemQuery, setStockItemQuery] = useState('');
  const [showStockSuggestions, setShowStockSuggestions] = useState(false);
  const [stockFormState, setStockFormState] = useState({
    type: 'Consumable',
    manufacturer: '',
    minStockLevel: '10',
  });
  const [expiryDate, setExpiryDate] = useState('');

  // Service Modal States
  const [selectedMasterItems, setSelectedMasterItems] = useState([]);
  // Extracted so the JSX handler isn't a deeply-nested arrow-in-arrow.
  const removeSelectedMasterItem = (id) =>
    setSelectedMasterItems((prev) => prev.filter((x) => x.id !== id));
  const [masterItemSearch, setMasterItemSearch] = useState('');
  const [showMasterSuggestions, setShowMasterSuggestions] = useState(false);

  useEffect(() => {
    if (stockModal.isOpen) {
      setStockItemQuery(stockModal.data?.name || '');
      setStockFormState({
        type: stockModal.data?.type || 'Consumable',
        manufacturer: stockModal.data?.manufacturer || '',
        minStockLevel: stockModal.data?.minStockLevel?.toString() || '10',
      });
      setExpiryDate(stockModal.data?.expiryDate || '');
    } else {
      setStockItemQuery('');
    }
    setShowStockSuggestions(false);
  }, [stockModal.isOpen, stockModal.data]);

  useEffect(() => {
    if (serviceModal.isOpen) {
      if (serviceModal.isEdit && serviceModal.data) {
        const matched = [];
        const service = serviceModal.data;
        if (service.masterItemIds && service.itemNames) {
          for (let i = 0; i < service.masterItemIds.length; i++) {
            matched.push({
              id: service.masterItemIds[i],
              name: service.itemNames[i] || `Item #${service.masterItemIds[i]}`,
            });
          }
        }
        setSelectedMasterItems(matched);
      } else {
        setSelectedMasterItems([]);
      }
      setMasterItemSearch('');
      setShowMasterSuggestions(false);
    }
  }, [serviceModal.isOpen, serviceModal.isEdit, serviceModal.data]);

  // Fetch master items & services
  // Deliberately does NOT swallow: a failed load must reach loadData so the UI can show an
  // error instead of an empty table. Catching here made loadData's own catch unreachable,
  // so an API failure rendered as "no inventory" -- indistinguishable from real empty stock.
  const fetchGlobalMasterItems = async () => {
    const res = await hospitalService.getGlobalMasterItems();
    setGlobalMasterItems(res || []);
  };

  // Deliberately does NOT swallow: a failed load must reach loadData so the UI can show an
  // error instead of an empty table. Catching here made loadData's own catch unreachable,
  // so an API failure rendered as "no inventory" -- indistinguishable from real empty stock.
  const fetchServices = async () => {
    const res = await hospitalService.getHospitalServices();
    setServicesList(res || []);
  };

  // Deliberately does NOT swallow: a failed load must reach loadData so the UI can show an
  // error instead of an empty table. Catching here made loadData's own catch unreachable,
  // so an API failure rendered as "no inventory" -- indistinguishable from real empty stock.
  const fetchInventory = async () => {
    const res = await hospitalService.getHospitalInventory();
    setInventoryList(res || []);
  };

  // Deliberately does NOT swallow: a failed load must reach loadData so the UI can show an
  // error instead of an empty table. Catching here made loadData's own catch unreachable,
  // so an API failure rendered as "no inventory" -- indistinguishable from real empty stock.
  const fetchPurchases = async () => {
    const res = await hospitalService.getHospitalInventoryPurchases();
    setPurchaseList(res || []);
  };

  const loadData = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      if (subTab === 'inventory') {
        await fetchInventory();
        await fetchServices();
      } else if (subTab === 'purchase') {
        await fetchPurchases();
        await fetchGlobalMasterItems();
      } else {
        await Promise.all([fetchServices(), fetchGlobalMasterItems()]);
      }
    } catch (err) {
      // ERROR is its own state. Never let a failed request render as "0 items".
      setLoadError(extractApiError(err, 'Failed to load hospital inventory data.'));
      toastError(extractApiError(err, 'Failed to load hospital inventory data.'));
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
    const itemName = form.itemName.value.trim();
    const type = form.type.value;
    const stockQuantity = parseInt(form.stockQuantity.value);
    const unitPrice = parseFloat(form.unitPrice.value);
    const minStockLevel = parseInt(form.minStockLevel.value);
    const expiryDate = form.expiryDate.value;
    const manufacturer = form.manufacturer.value.trim();

    if (!itemName) return;

    const payload = {
      name: itemName,
      type,
      quantity: stockQuantity,
      unitPrice,
      minStockLevel,
      expiryDate: expiryDate ? expiryDate : null,
      manufacturer: manufacturer ? manufacturer : null,
    };

    try {
      setLoading(true);
      await hospitalService.addHospitalInventoryPurchase(payload);
      success('Purchase recorded and stock inventory updated.');
      setStockModal({ isOpen: false, isEdit: false, data: null });
      loadData();
    } catch (err) {
      toastError(extractApiError(err, 'Failed to record purchase.'));
    } finally {
      setLoading(false);
    }
  };

  // Handle Service Save
  const handleServiceSubmit = async (e) => {
    e.preventDefault();
    const form = e.target;
    const name = form.name.value.trim();
    const charge = parseFloat(form.charge.value);

    if (!name) return;

    const payload = {
      name,
      charge,
      masterItemIds: selectedMasterItems.map((x) => x.id),
    };

    try {
      setLoading(true);
      if (serviceModal.isEdit) {
        await hospitalService.updateHospitalService(serviceModal.data.id, payload);
        success('Service updated successfully.');
      } else {
        await hospitalService.createHospitalService(payload);
        success('Service registered successfully.');
      }
      setServiceModal({ isOpen: false, isEdit: false, data: null });
      loadData();
    } catch (err) {
      toastError(extractApiError(err, 'Failed to save service.'));
    } finally {
      setLoading(false);
    }
  };

  const handleDeactivateStock = (id) => {
    setConfirmState({
      open: true,
      title: 'Remove Stock Item',
      message: 'Are you sure you want to remove this item from active stock inventory?',
      onConfirm: async () => {
        await hospitalService.deleteHospitalInventory(id);
        success('Item removed from inventory.');
        loadData();
      },
    });
  };

  const handleDeactivateService = (id) => {
    setConfirmState({
      open: true,
      title: 'Delete Service',
      message:
        "Are you sure you want to delete this service? Existing medical/billing records mapping it won't be affected.",
      onConfirm: async () => {
        await hospitalService.deleteHospitalService(id);
        success('Service deleted.');
        loadData();
      },
    });
  };

  return (
    <div className="p-6 bg-white rounded-2xl border border-gray-200/80 shadow-sm space-y-6">
      {/* Header and Toggle Controls */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 border-b border-gray-100 pb-4">
        <div>
          <h2 className="text-xl font-bold text-gray-900">
            Hospital Equipment & Service Inventory
          </h2>
          <p className="text-sm text-gray-500">
            Configure global item mappings to services and manage active physical stock levels for
            non-medicine procedures.
          </p>
        </div>

        {/* Segmented Top-Tab Toggle */}
        <div className="flex bg-gray-100 p-1 rounded-xl w-full sm:w-auto overflow-x-auto whitespace-nowrap scrollbar-none">
          <button
            type="button"
            onClick={() => setSubTab('inventory')}
            className={`flex-1 sm:flex-none px-3 sm:px-5 py-2 text-sm font-semibold rounded-lg transition-all whitespace-nowrap ${subTab === 'inventory' ? 'bg-white text-teal-700 shadow-sm' : 'text-gray-600 hover:text-gray-900'}`}
          >
            Active Stock
          </button>
          <button
            type="button"
            onClick={() => setSubTab('purchase')}
            className={`flex-1 sm:flex-none px-3 sm:px-5 py-2 text-sm font-semibold rounded-lg transition-all whitespace-nowrap ${subTab === 'purchase' ? 'bg-white text-teal-700 shadow-sm' : 'text-gray-600 hover:text-gray-900'}`}
          >
            Purchase History
          </button>
          <button
            type="button"
            onClick={() => setSubTab('catalog')}
            className={`flex-1 sm:flex-none px-3 sm:px-5 py-2 text-sm font-semibold rounded-lg transition-all whitespace-nowrap ${subTab === 'catalog' ? 'bg-white text-teal-700 shadow-sm' : 'text-gray-600 hover:text-gray-900'}`}
          >
            Services Lookup
          </button>
        </div>
      </div>

      {/* Quick Actions Panel */}
      <div className="flex justify-between items-center bg-teal-50/40 p-4 rounded-xl border border-teal-100/60">
        <div className="text-sm text-teal-800 font-medium">
          {subTab === 'inventory' &&
            `Displaying ${inventoryList.filter((x) => x.isActive !== false).length} active stock items in-clinic`}
          {subTab === 'purchase' && `Displaying ${purchaseList.length} purchase ledger entries`}
          {subTab === 'catalog' && `Displaying ${servicesList.length} services configured`}
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
        {subTab === 'catalog' && (
          <button
            type="button"
            onClick={() => setServiceModal({ isOpen: true, isEdit: false, data: null })}
            className="px-4 py-2 bg-teal-600 text-white rounded-lg hover:bg-teal-700 transition font-semibold text-sm shadow-md shadow-teal-600/10 active:scale-95"
          >
            + Add Service
          </button>
        )}
      </div>

      {/* Main Tables */}
      {loadError ? (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-5 text-center"
        >
          <p className="text-sm font-semibold text-red-800">Couldn&apos;t load inventory</p>
          <p className="mt-1 text-sm text-red-700">{loadError}</p>
          <button
            type="button"
            onClick={loadData}
            className="mt-3 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700"
          >
            Retry
          </button>
        </div>
      ) : loading &&
      inventoryList.length === 0 &&
      purchaseList.length === 0 &&
      servicesList.length === 0 ? (
        <div className="space-y-3">
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
          <div className="h-10 bg-slate-100 rounded-lg animate-pulse" />
        </div>
      ) : subTab === 'inventory' ? (
        /* INVENTORY TAB LIST */
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left">
            <thead>
              <tr className="border-b border-gray-200 text-gray-500 font-medium">
                <th className="pb-3 text-left">Item Name</th>
                <th className="pb-3 text-center">Type</th>
                <th className="pb-3 text-center">Quantity</th>
                <th className="pb-3 text-right">Unit Cost</th>
                <th className="pb-3 text-center">Expiry Date</th>
                <th className="pb-3 text-center">Stock Level</th>
                <th className="pb-3 text-right">Actions</th>
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
                          {item.type || 'Consumable'}
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
                      <td className="py-3 text-right">
                        <button
                          type="button"
                          onClick={() => handleDeactivateStock(item.id)}
                          className="text-red-500 hover:text-red-700 font-semibold"
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  );
                })}
              {inventoryList.filter((x) => x.isActive !== false).length === 0 && (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-gray-400">
                    No stock items in inventory. Record purchases in the &quot;Purchase
                    History&quot; tab to add stock.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ) : subTab === 'purchase' ? (
        /* PURCHASE HISTORY TAB LIST */
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left">
            <thead>
              <tr className="border-b border-gray-200 text-gray-500 font-medium">
                <th className="pb-3 text-left">Item Name</th>
                <th className="pb-3 text-center">Type</th>
                <th className="pb-3 text-center">Quantity Purchased</th>
                <th className="pb-3 text-right">Unit Price</th>
                <th className="pb-3 text-right">Total Cost</th>
                <th className="pb-3 text-center">Expiry Date</th>
                <th className="pb-3 text-left">Manufacturer</th>
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
                        {item.type || 'Consumable'}
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
                    <td className="py-3 text-left text-gray-500">{item.manufacturer || '-'}</td>
                    <td className="py-3 text-center text-gray-500">
                      {item.purchaseDate ? new Date(item.purchaseDate).toLocaleString() : '-'}
                    </td>
                  </tr>
                );
              })}
              {purchaseList.length === 0 && (
                <tr>
                  <td colSpan={8} className="py-8 text-center text-gray-400">
                    No purchase history found. Click &quot;+ Add Stock (Purchase Intake)&quot; to
                    record a purchase.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ) : (
        /* SERVICES LIST */
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm text-left">
            <thead>
              <tr className="border-b border-gray-200 text-gray-500 font-medium">
                <th className="pb-3 text-left">Service Name</th>
                <th className="pb-3 text-right">Charge (₹)</th>
                <th className="pb-3 text-left pl-6">Linked Master Items</th>
                <th className="pb-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {servicesList.map((service) => (
                <tr key={service.id} className="hover:bg-slate-50/50 transition">
                  <td className="py-3 font-semibold text-gray-800">{service.name}</td>
                  <td className="py-3 text-right text-teal-700 font-bold">
                    ₹{service.charge?.toFixed(2)}
                  </td>
                  <td className="py-3 text-left pl-6 text-gray-500">
                    {service.itemNames && service.itemNames.length > 0 ? (
                      <div className="flex flex-wrap gap-1">
                        {service.itemNames.map((name, i) => (
                          <span
                            key={i}
                            className="inline-block px-2 py-0.5 text-xs bg-slate-100 text-slate-700 rounded-full font-medium"
                          >
                            {name}
                          </span>
                        ))}
                      </div>
                    ) : (
                      <span className="text-xs text-gray-400 italic">
                        No inventory dependencies
                      </span>
                    )}
                  </td>
                  <td className="py-3 text-right space-x-2">
                    <button
                      type="button"
                      onClick={() => setServiceModal({ isOpen: true, isEdit: true, data: service })}
                      className="text-teal-600 hover:text-teal-800 font-semibold"
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeactivateService(service.id)}
                      className="text-red-500 hover:text-red-700 font-semibold"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
              {servicesList.length === 0 && (
                <tr>
                  <td colSpan={4} className="py-8 text-center text-gray-400">
                    No services configured yet. Click &quot;+ Add Service&quot; to configure one.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* MODAL 1: ADD ACTIVE INVENTORY STOCK */}
      {stockModal.isOpen && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg max-h-[90vh] overflow-y-auto animate-fade-in-up">
            <div className="p-6 border-b border-gray-100 flex justify-between items-center bg-slate-50">
              <h3 className="text-lg font-bold text-gray-800">Add Stock Intake</h3>
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
              {/* Item Name Autocomplete Input (uses global master items) */}
              <div className="relative">
                <label htmlFor="fld-11" className="block text-sm font-semibold text-gray-700 mb-1">
                  Item Name <span className="text-red-600">*</span>
                </label>
                <input
                  id="fld-11"
                  type="text"
                  name="itemName"
                  placeholder="Type master item name (e.g. Cotton, Bandage)..."
                  required
                  value={stockItemQuery}
                  onChange={(e) => {
                    setStockItemQuery(e.target.value);
                    setShowStockSuggestions(true);
                  }}
                  onFocus={() => setShowStockSuggestions(true)}
                  onBlur={() => {
                    setTimeout(() => setShowStockSuggestions(false), 200);
                  }}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none text-gray-800"
                />
                {showStockSuggestions && stockItemQuery.trim().length >= 1 && (
                  <div className="absolute left-0 right-0 mt-1 max-h-40 overflow-y-auto bg-white rounded-lg border border-gray-200 shadow-lg z-50 divide-y divide-gray-100">
                    {globalMasterItems
                      .filter((x) =>
                        x.name.toLowerCase().includes(stockItemQuery.toLowerCase().trim())
                      )
                      .map((c) => (
                        <button
                          key={c.id}
                          type="button"
                          onMouseDown={() => {
                            setStockItemQuery(c.name);
                            setShowStockSuggestions(false);
                          }}
                          className="w-full text-left px-4 py-2 hover:bg-slate-50 text-sm font-medium text-gray-800"
                        >
                          {c.name}
                        </button>
                      ))}
                    {globalMasterItems.filter((x) =>
                      x.name.toLowerCase().includes(stockItemQuery.toLowerCase().trim())
                    ).length === 0 && (
                      <div className="p-2.5 text-center text-xs text-gray-400">
                        No matching master items.
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* Type Select */}
              <div>
                <label htmlFor="fld-10" className="block text-sm font-semibold text-gray-700 mb-1">
                  Type <span className="text-red-600">*</span>
                </label>
                <select
                  id="fld-10"
                  name="type"
                  required
                  value={stockFormState.type}
                  onChange={(e) => setStockFormState((prev) => ({ ...prev, type: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none bg-white text-gray-800"
                >
                  <option value="Consumable">Consumable (Gloves, Swabs)</option>
                  <option value="Surgical">Surgical Instruments (Syringes, Needles)</option>
                  <option value="Fluid">Saline/Fluid</option>
                  <option value="Equipment">Diagnostic Equipment</option>
                  <option value="Other">Other</option>
                </select>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="fld-9" className="block text-sm font-semibold text-gray-700 mb-1">
                    Quantity <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-9"
                    type="number"
                    name="stockQuantity"
                    min="1"
                    required
                    placeholder="0"
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
                <div>
                  <label htmlFor="fld-8" className="block text-sm font-semibold text-gray-700 mb-1">
                    Unit Price (₹) <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-8"
                    type="number"
                    name="unitPrice"
                    step="0.01"
                    min="0"
                    required
                    placeholder="0.00"
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="fld-7" className="block text-sm font-semibold text-gray-700 mb-1">
                    Min Stock Warning Level <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="fld-7"
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
                  <label htmlFor="fld-6" className="block text-sm font-semibold text-gray-700 mb-1">
                    Expiry Date
                  </label>
                  <DateSelect value={expiryDate} onChange={setExpiryDate} />
                  <input type="hidden" name="expiryDate" value={expiryDate} />
                </div>
              </div>

              <div>
                <label htmlFor="fld-5" className="block text-sm font-semibold text-gray-700 mb-1">
                  Manufacturer
                </label>
                <input
                  id="fld-5"
                  type="text"
                  name="manufacturer"
                  placeholder="e.g. Generic Co."
                  value={stockFormState.manufacturer}
                  onChange={(e) =>
                    setStockFormState((prev) => ({ ...prev, manufacturer: e.target.value }))
                  }
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                />
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
                  Restock / Intake
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

      {/* MODAL 2: ADD/EDIT SERVICE */}
      {serviceModal.isOpen && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg max-h-[90vh] overflow-y-auto animate-fade-in-up">
            <div className="p-6 border-b border-gray-100 flex justify-between items-center bg-slate-50">
              <h3 className="text-lg font-bold text-gray-800">
                {serviceModal.isEdit ? 'Edit Service' : 'Add Service'}
              </h3>
              <button
                type="button"
                onClick={() => setServiceModal({ isOpen: false, isEdit: false, data: null })}
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

            <form onSubmit={handleServiceSubmit} className="p-6 space-y-4">
              <div>
                <label htmlFor="fld-4" className="block text-sm font-semibold text-gray-700 mb-1">
                  Service Name <span className="text-red-600">*</span>
                </label>
                <input
                  id="fld-4"
                  type="text"
                  name="name"
                  required
                  placeholder="e.g. Dressing, Nebulization, Injection"
                  defaultValue={serviceModal.data?.name || ''}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                />
              </div>

              <div>
                <label htmlFor="fld-3" className="block text-sm font-semibold text-gray-700 mb-1">
                  Charge (₹) <span className="text-red-600">*</span>
                </label>
                <input
                  id="fld-3"
                  type="number"
                  name="charge"
                  step="0.01"
                  min="0"
                  required
                  placeholder="0.00"
                  defaultValue={
                    serviceModal.data?.charge !== undefined ? serviceModal.data.charge : ''
                  }
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                />
              </div>

              {/* Linked Master Items Search and Select */}
              <div className="relative">
                <label htmlFor="fld-2" className="block text-sm font-semibold text-gray-700 mb-1">
                  Linked Master Items (Dependencies)
                </label>
                <input
                  id="fld-2"
                  type="text"
                  placeholder="Search global master items (Cotton, Syringe)..."
                  value={masterItemSearch}
                  onChange={(e) => {
                    setMasterItemSearch(e.target.value);
                    setShowMasterSuggestions(true);
                  }}
                  onFocus={() => setShowMasterSuggestions(true)}
                  onBlur={() => setTimeout(() => setShowMasterSuggestions(false), 200)}
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none text-gray-800"
                />

                {showMasterSuggestions && masterItemSearch.trim().length >= 1 && (
                  <div className="absolute left-0 right-0 mt-1 max-h-40 overflow-y-auto bg-white rounded-lg border border-gray-200 shadow-lg z-50 divide-y divide-gray-100">
                    {globalMasterItems
                      .filter(
                        (x) =>
                          x.name.toLowerCase().includes(masterItemSearch.toLowerCase().trim()) &&
                          !selectedMasterItems.some((item) => item.id === x.id)
                      )
                      .map((c) => (
                        <button
                          key={c.id}
                          type="button"
                          onMouseDown={() => {
                            setSelectedMasterItems((prev) => [...prev, { id: c.id, name: c.name }]);
                            setMasterItemSearch('');
                            setShowMasterSuggestions(false);
                          }}
                          className="w-full text-left px-4 py-2 hover:bg-slate-50 text-sm font-medium text-gray-800"
                        >
                          {c.name}
                        </button>
                      ))}
                    {globalMasterItems.filter(
                      (x) =>
                        x.name.toLowerCase().includes(masterItemSearch.toLowerCase().trim()) &&
                        !selectedMasterItems.some((item) => item.id === x.id)
                    ).length === 0 && (
                      <div className="p-2.5 text-center text-xs text-gray-400">
                        No matching master items.
                      </div>
                    )}
                  </div>
                )}

                {/* Selected Items Tags */}
                {selectedMasterItems.length > 0 && (
                  <div className="flex flex-wrap gap-2 mt-2">
                    {selectedMasterItems.map((item) => (
                      <span
                        key={item.id}
                        className="inline-flex items-center gap-1 px-3 py-1 bg-teal-50 text-teal-700 border border-teal-200 rounded-full text-xs font-semibold"
                      >
                        {item.name}
                        <button
                          type="button"
                          onClick={() => removeSelectedMasterItem(item.id)}
                          className="hover:text-teal-900 focus:outline-none text-teal-500 font-bold"
                        >
                          &times;
                        </button>
                      </span>
                    ))}
                  </div>
                )}
                <p className="text-xs text-gray-400 mt-1">
                  These items will be deducted from active stock using FEFO when this service is
                  administered to a patient.
                </p>
              </div>

              <div className="pt-4 flex gap-3">
                <button
                  type="button"
                  onClick={() => setServiceModal({ isOpen: false, isEdit: false, data: null })}
                  className="flex-1 px-4 py-2.5 border border-gray-300 text-gray-700 rounded-lg font-semibold hover:bg-slate-50 transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 px-4 py-2.5 bg-teal-600 text-white rounded-lg font-semibold hover:bg-teal-700 transition shadow-md shadow-teal-600/10"
                >
                  {serviceModal.isEdit ? 'Save Changes' : 'Create Service'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default HospitalInventoryTab;
