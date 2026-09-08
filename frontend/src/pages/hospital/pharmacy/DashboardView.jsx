import React, { useState, useEffect, useCallback } from 'react';
import { SkeletonTableRow } from '../../../components/Skeleton';
import authService from '../../../services/authService';
import inventoryApi from '../../../services/pharmacy/inventoryApi';
import salesApi from '../../../services/pharmacy/salesApi';

const DashboardView = ({ onNavigate, refreshKey = 0 }) => {
  const user = authService.getCurrentUser();
  const isStandalonePharmacy =
    user?.modules?.includes('PHARMACY') && !user?.modules?.includes('OPD');

  const [loading, setLoading] = useState(true);
  const [lowStockCount, setLowStockCount] = useState(0);
  const [lowStockItems, setLowStockItems] = useState([]);
  const [nearExpiryCount, setNearExpiryCount] = useState(0);
  const [salesStats, setSalesStats] = useState({ todaySalesTotal: 0, todaySalesCount: 0 });

  const fetchLiveStats = useCallback(async (showSpinner = true) => {
    if (showSpinner) setLoading(true);
    try {
      const [stockResponse, expiryResponse, salesData] = await Promise.all([
        inventoryApi.getLowStock(0, 10),
        inventoryApi.getExpiring(30, 0, 1),
        salesApi.getStats(),
      ]);

      if (stockResponse?.content) {
        setLowStockCount(stockResponse.totalElements || stockResponse.content.length);
        setLowStockItems(
          stockResponse.content.map((b) => ({
            id: b.id,
            item: b.medicine?.medicineName || 'Unknown Med',
            generic: b.medicine?.genericName || 'N/A',
            category: b.medicine?.category?.categoryName || 'General',
            available: b.currentQuantity,
            unit: b.medicine?.unitOfMeasure || 'Units',
            threshold: b.medicine?.minStockLevel || 0,
          }))
        );
      }

      setNearExpiryCount(expiryResponse?.totalElements || 0);
      setSalesStats(salesData);
    } catch (err) {
      // silent
    } finally {
      if (showSpinner) setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchLiveStats(true);
  }, [fetchLiveStats, refreshKey]);

  // Dynamic stats mapping
  const stats = [
    {
      title: 'Today Sales',
      value: `₹${salesStats.todaySalesTotal.toLocaleString()}`,
      subtitle: `${salesStats.todaySalesCount} Bills Generated`,
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
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
      ),
    },
    {
      title: 'Bills Generated',
      value: String(salesStats.todaySalesCount),
      subtitle: "Today's invoice count",
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
          />
        </svg>
      ),
    },
    {
      title: 'Low Stock Items',
      value: loading ? '...' : String(lowStockCount),
      subtitle: 'Items below threshold',
      isAlert: lowStockCount > 0,
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      ),
    },
    {
      title: 'Near Expiry',
      value: loading ? '...' : String(nearExpiryCount),
      subtitle: nearExpiryCount > 0 ? 'Expiring within 30 days' : 'Stock safe',
      isAlert: nearExpiryCount > 0,
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      ),
    },
    {
      title: 'Pending Vendor Bills',
      value: '₹0',
      subtitle: 'Locked for V1',
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z"
          />
        </svg>
      ),
    },
    {
      title: 'System Status',
      value: 'Active',
      subtitle: 'All streams synced',
      icon: (
        <svg
          className="w-5 h-5 text-gray-600"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      {/* Header Section */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white p-4 border border-gray-200 rounded-lg">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Pharmacy Dashboard</h1>
          <p className="text-sm text-gray-500 mt-1">
            Real-time inventory levels & sales tracking overview.
          </p>
        </div>
        <div className="flex flex-wrap gap-3">
          <button
            onClick={() => onNavigate('billing')}
            className="px-4 py-2 bg-gray-900 text-white text-sm font-medium rounded hover:bg-gray-800 transition-colors inline-flex items-center gap-2"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            Open Billing Counter
          </button>
          <button
            onClick={() => onNavigate('inventory')}
            className="px-4 py-2 border border-gray-300 text-gray-700 text-sm font-medium rounded hover:bg-gray-50 transition-colors bg-white inline-flex items-center gap-2"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"
              />
            </svg>
            Manage Inventory
          </button>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        {stats.map((stat, idx) => (
          <div
            key={idx}
            className={`bg-white p-4 border border-gray-200 rounded-lg transition-all hover:shadow-sm ${stat.isAlert ? 'border-l-4 border-l-red-500' : ''}`}
          >
            <div className="flex items-center justify-between mb-2">
              <div className="p-2 bg-gray-50 rounded">{stat.icon}</div>
            </div>
            <p className="text-xs font-medium text-gray-500 uppercase tracking-wider">
              {stat.title}
            </p>
            <p className="text-2xl font-bold text-gray-900 mt-1">{stat.value}</p>
            {stat.subtitle && (
              <p className="text-xs text-gray-400 mt-1 truncate">{stat.subtitle}</p>
            )}
          </div>
        ))}
      </div>

      {/* Main Workspace Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left/Center Content (Span 2) */}
        <div className="lg:col-span-2 space-y-6">
          {/* Spacious Low Stock Panel */}
          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden flex flex-col shadow-sm">
            <div className="px-4 py-3 border-b border-gray-150 flex justify-between items-center bg-red-50/50">
              <h3 className="font-bold text-gray-800 text-sm flex items-center gap-2 text-red-800">
                <svg
                  className="w-4 h-4 text-red-600"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                  />
                </svg>
                Real-time Low Stock Inventory Alerts
              </h3>
              <span className="text-xs font-black bg-red-200 text-red-800 px-2 py-0.5 rounded-full">
                {lowStockCount} Items
              </span>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full text-xs text-left border-collapse">
                <thead className="bg-gray-50 text-gray-500 uppercase border-b border-gray-200">
                  <tr>
                    <th className="px-4 py-3 font-bold">Medicine</th>
                    <th className="px-4 py-3 font-bold">Category</th>
                    <th className="px-4 py-3 font-bold text-right">Available</th>
                    <th className="px-4 py-3 font-bold text-right">Threshold</th>
                    <th className="px-4 py-3 font-bold text-center">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {loading ? (
                    <>
                      {Array.from({ length: 3 }).map((_, i) => (
                        <SkeletonTableRow key={i} cols={5} delay={i} />
                      ))}
                    </>
                  ) : lowStockItems.length > 0 ? (
                    lowStockItems.map((stock) => (
                      <tr key={stock.id} className="hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3">
                          <div className="flex flex-col">
                            <span className="font-bold text-gray-900">{stock.item}</span>
                            <span className="text-[10px] text-gray-400 font-bold uppercase">
                              {stock.generic}
                            </span>
                          </div>
                        </td>
                        <td className="px-4 py-3 text-gray-600">{stock.category}</td>
                        <td className="px-4 py-3 text-right font-black text-red-600">
                          {stock.available} {stock.unit}
                        </td>
                        <td className="px-4 py-3 text-right text-gray-500">
                          {stock.threshold} {stock.unit}
                        </td>
                        <td className="px-4 py-3 text-center">
                          <span className="px-2 py-0.5 rounded text-[9px] font-black uppercase bg-red-50 text-red-700 border border-red-200">
                            Needs Refill
                          </span>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={5} className="p-8 text-center text-green-600 font-bold">
                        <div className="flex flex-col items-center gap-1.5">
                          <svg
                            className="w-8 h-8 text-green-500"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                            />
                          </svg>
                          All inventory stocks are normal!
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className="bg-gray-50 px-4 py-2 text-center border-t border-gray-150">
              <button
                onClick={() => onNavigate('inventory')}
                className="text-xs font-semibold text-gray-600 hover:text-gray-900"
              >
                Open Inventory View &rarr;
              </button>
            </div>
          </div>
        </div>

        {/* Right Column Sidebars */}
        <div className="space-y-6">
          {/* Operations Hub Shortcuts */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 shadow-sm space-y-3">
            <h4 className="font-bold text-gray-700 uppercase tracking-wide text-[10px]">
              Operations Shortcut Hub
            </h4>
            <div className="flex flex-col gap-2">
              <button
                onClick={() => onNavigate('billing')}
                className="w-full text-left px-3 py-2 bg-gray-50 border border-gray-200 hover:border-gray-900 rounded-lg text-xs font-bold text-gray-700 hover:bg-gray-900 hover:text-white transition-all flex items-center justify-between"
              >
                <span>🚶‍♂️ Walk-In & Prescription Billing</span>
                <span>&rarr;</span>
              </button>
              {!isStandalonePharmacy && (
                <button
                  onClick={() => onNavigate('prescriptions')}
                  className="w-full text-left px-3 py-2 bg-gray-50 border border-gray-200 hover:border-gray-900 rounded-lg text-xs font-bold text-gray-700 hover:bg-gray-900 hover:text-white transition-all flex items-center justify-between"
                >
                  <span>📋 Live Doctor Prescriptions</span>
                  <span>&rarr;</span>
                </button>
              )}
              <button
                onClick={() => onNavigate('purchase')}
                className="w-full text-left px-3 py-2 bg-gray-50 border border-gray-200 hover:border-gray-900 rounded-lg text-xs font-bold text-gray-700 hover:bg-gray-900 hover:text-white transition-all flex items-center justify-between"
              >
                <span>📦 Purchase Invoice Entry</span>
                <span>&rarr;</span>
              </button>
              <button
                onClick={() => onNavigate('reports')}
                className="w-full text-left px-3 py-2 bg-gray-50 border border-gray-200 hover:border-gray-900 rounded-lg text-xs font-bold text-gray-700 hover:bg-gray-900 hover:text-white transition-all flex items-center justify-between"
              >
                <span>📊 Sales & Stock Reports</span>
                <span>&rarr;</span>
              </button>
            </div>
          </div>

          {/* Quick System Alert */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 text-xs text-gray-500 shadow-sm">
            <h4 className="font-bold text-gray-700 uppercase mb-1 tracking-wide text-[10px]">
              System Status
            </h4>
            <p>
              Phase 1 Pharmacy Core is ACTIVE. All inventory data streams are isolated under
              multi-tenant rules.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DashboardView;
