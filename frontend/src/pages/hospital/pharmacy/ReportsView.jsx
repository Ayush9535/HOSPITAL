import React, { useState, useEffect } from 'react';
import { SkeletonDashboard } from '../../../components/Skeleton';
import { useToast } from '../../../context/ToastContext';
import reportsApi from '../../../services/pharmacy/reportsApi';

const ReportsView = () => {
  const { success, error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState({
    kpis: {
      totalSales: 0,
      totalRefunds: 0,
      netRevenue: 0,
      inventoryValue: 0,
      expiredValue: 0,
      grossProfit: 0,
      profitMargin: 0,
    },
    taxSummary: {
      inputGst: 0,
      outputGst: 0,
      netGstPayable: 0,
    },
    expiryRisk: {
      next30Days: 0,
      next60Days: 0,
      next90Days: 0,
    },
    fastMoving: [],
    categoryValuation: [],
    salesTrend: [],
  });

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await reportsApi.getDashboardData();
      if (res) {
        setData(res);
      }
    } catch (err) {
      console.error('Failed to load reports data:', err);
      toastError('Failed to load real-time analytics data');
    } finally {
      setLoading(false);
    }
  };

  const [exporting, setExporting] = useState(false);
  const handleExport = async () => {
    if (exporting) return;
    setExporting(true);
    try {
      const blob = await reportsApi.exportLedgerCsv();
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute(
        'download',
        `pharmacy_tax_ledger_${new Date().toISOString().slice(0, 10)}.csv`
      );
      document.body.appendChild(link);
      link.click();
      link.remove();
      success('Tax compliance ledger exported successfully!');
    } catch (err) {
      console.error('Failed to export reports:', err);
      toastError('Failed to export financial reports');
    } finally {
      setExporting(false);
    }
  };

  if (loading) {
    return (
      <SkeletonDashboard
        statCount={4}
        tableRows={5}
        tableCols={4}
        gridCols="md:grid-cols-2 lg:grid-cols-4"
      />
    );
  }

  // Helper to format values as INR Currency
  const formatINR = (value) => {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0,
    }).format(value || 0);
  };

  // Calculate maximum sales in trend to scale the SVG Chart heights
  const maxSales = Math.max(...data.salesTrend.map((d) => Number(d.sales || 1)), 1000);

  return (
    <div className="space-y-6">
      {/* Header toolbar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white border border-gray-200 p-4 rounded-lg shadow-sm">
        <div>
          <h1 className="text-xl font-bold text-gray-900">Reports & Analytics</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Real-time ledger audit, inventory capital valuations, and tax margins.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={loadData}
            className="p-2 border border-gray-300 rounded text-gray-600 hover:bg-gray-50 bg-white"
            title="Reload metrics"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 1121.21 8H18"
              />
            </svg>
          </button>
          <button
            onClick={handleExport}
            disabled={exporting}
            className={`px-4 py-2 text-white text-sm font-bold rounded flex items-center gap-2 transition-all duration-200 ${exporting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800 hover:shadow-md'}`}
          >
            {exporting ? (
              <svg
                className="animate-spin h-4 w-4 text-white"
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
            ) : (
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                />
              </svg>
            )}
            {exporting ? 'Exporting...' : 'Export Tax Ledger'}
          </button>
        </div>
      </div>

      {/* KPI Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {/* Gross POS Sales */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm relative overflow-hidden group hover:border-gray-400 transition-all duration-200">
          <div className="absolute top-0 left-0 bottom-0 w-1 bg-gray-900"></div>
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider block">
            Gross POS Sales
          </span>
          <span className="text-2xl font-black text-gray-900 mt-2 block">
            {formatINR(data.kpis.totalSales)}
          </span>
          <div className="mt-3 flex items-center justify-between text-xs border-t border-gray-100 pt-2.5">
            <span className="text-gray-500">Refunds deducted:</span>
            <span className="font-semibold text-red-600">-{formatINR(data.kpis.totalRefunds)}</span>
          </div>
        </div>

        {/* Net Operational Revenue */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm relative overflow-hidden group hover:border-gray-400 transition-all duration-200">
          <div className="absolute top-0 left-0 bottom-0 w-1 bg-blue-500"></div>
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider block">
            Net Revenue
          </span>
          <span className="text-2xl font-black text-blue-900 mt-2 block">
            {formatINR(data.kpis.netRevenue)}
          </span>
          <div className="mt-3 flex items-center justify-between text-xs border-t border-gray-100 pt-2.5">
            <span className="text-gray-500">Capital margins:</span>
            <span className="font-semibold text-blue-600">Live POS Tracker</span>
          </div>
        </div>

        {/* Capital Valuation */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm relative overflow-hidden group hover:border-gray-400 transition-all duration-200">
          <div className="absolute top-0 left-0 bottom-0 w-1 bg-green-500"></div>
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider block">
            Inventory Assets
          </span>
          <span className="text-2xl font-black text-green-900 mt-2 block">
            {formatINR(data.kpis.inventoryValue)}
          </span>
          <div className="mt-3 flex items-center justify-between text-xs border-t border-gray-100 pt-2.5">
            <span className="text-gray-500">Expired Stock Loss:</span>
            <span className="font-semibold text-red-500">{formatINR(data.kpis.expiredValue)}</span>
          </div>
        </div>

        {/* Net Profit & Margin */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 shadow-sm relative overflow-hidden group hover:border-gray-400 transition-all duration-200">
          <div className="absolute top-0 left-0 bottom-0 w-1 bg-emerald-600"></div>
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider block">
            Net Profit (Margin)
          </span>
          <div className="flex items-baseline gap-2 mt-2">
            <span className="text-2xl font-black text-emerald-700">
              {formatINR(data.kpis.grossProfit)}
            </span>
            <span className="text-xs font-bold text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-100">
              {data.kpis.profitMargin}%
            </span>
          </div>
          <div className="mt-3 flex items-center justify-between text-xs border-t border-gray-100 pt-2.5">
            <span className="text-gray-500">Adjusted Cost of Goods Sold</span>
          </div>
        </div>
      </div>

      {/* Visual Analytics Graphs */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Visual Chart 1: Sales & Reversals Trend */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 flex flex-col h-96 shadow-sm">
          <div>
            <h3 className="font-bold text-gray-900">7-Day Sales & Refunds Trend</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Real-time daily POS sales vs inward customer returns.
            </p>
          </div>
          <div className="flex-1 mt-6 flex items-end justify-between gap-3 h-full">
            {data.salesTrend.map((day, idx) => {
              // Calculate heights
              const salesPct = (Number(day.sales) / maxSales) * 100;
              const refundsPct = (Number(day.refunds) / maxSales) * 100;

              const formattedDate = new Date(day.date).toLocaleDateString('en-IN', {
                weekday: 'short',
                day: 'numeric',
              });

              return (
                <div
                  key={idx}
                  className="flex-1 flex flex-col items-center h-full justify-end group"
                >
                  <div className="w-full flex justify-center gap-1.5 h-[80%] items-end relative">
                    {/* Sales Bar */}
                    <div
                      className="w-4 bg-gray-900 rounded-t transition-all duration-300 relative group-hover:bg-gray-800"
                      style={{ height: `${Math.max(salesPct, 3)}%` }}
                      title={`Sales: ${formatINR(day.sales)}`}
                    >
                      <span className="absolute -top-6 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white font-bold text-[10px] px-1.5 py-0.5 rounded shadow opacity-0 group-hover:opacity-100 transition-opacity whitespace-nowrap pointer-events-none">
                        {formatINR(day.sales)}
                      </span>
                    </div>
                    {/* Refunds Bar */}
                    {Number(day.refunds) > 0 && (
                      <div
                        className="w-2 bg-red-500 rounded-t transition-all duration-300 relative"
                        style={{ height: `${Math.max(refundsPct, 3)}%` }}
                        title={`Refunds: ${formatINR(day.refunds)}`}
                      >
                        <span className="absolute -top-6 left-1/2 transform -translate-x-1/2 bg-red-600 text-white font-bold text-[10px] px-1.5 py-0.5 rounded shadow opacity-0 group-hover:opacity-100 transition-opacity whitespace-nowrap pointer-events-none">
                          {formatINR(day.refunds)}
                        </span>
                      </div>
                    )}
                  </div>
                  <span className="text-[10px] text-gray-500 font-semibold mt-3 text-center block w-full truncate border-t border-gray-100 pt-2">
                    {formattedDate}
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {/* Visual Chart 2: Stock Category Valuation Distribution */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 flex flex-col h-96 shadow-sm">
          <div>
            <h3 className="font-bold text-gray-900">Shelf Investment Breakdown</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Asset capital allocation partitioned by medicine category.
            </p>
          </div>
          {data.categoryValuation.length > 0 ? (
            <div className="flex-1 mt-6 flex flex-col justify-center space-y-4">
              {data.categoryValuation.map((cat, idx) => {
                // Calculate percentage of total valuation
                const totalVal = data.categoryValuation.reduce(
                  (acc, c) => acc + Number(c.value),
                  0
                );
                const percentage =
                  totalVal > 0 ? ((Number(cat.value) / totalVal) * 100).toFixed(1) : 0;
                const barColor = [
                  'bg-gray-900',
                  'bg-blue-500',
                  'bg-emerald-500',
                  'bg-purple-500',
                  'bg-amber-500',
                ][idx % 5];

                return (
                  <div key={idx} className="space-y-1">
                    <div className="flex justify-between text-xs font-semibold text-gray-700">
                      <span className="flex items-center gap-1.5">
                        <span className={`w-2 h-2 rounded-full ${barColor}`}></span>
                        {cat.category}
                        <span className="text-gray-400 font-normal">({cat.count} batches)</span>
                      </span>
                      <span className="text-gray-900">
                        {formatINR(cat.value)}{' '}
                        <span className="text-gray-400 font-normal">({percentage}%)</span>
                      </span>
                    </div>
                    <div className="w-full bg-gray-100 h-2.5 rounded-full overflow-hidden">
                      <div
                        className={`h-full ${barColor} rounded-full transition-all duration-500`}
                        style={{ width: `${percentage}%` }}
                      ></div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center text-gray-400">
              <svg
                className="w-12 h-12 mb-2 opacity-30"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"
                />
              </svg>
              <p className="text-sm font-medium">No stock assets active to chart.</p>
            </div>
          )}
        </div>
      </div>

      {/* In-Depth Ledgers Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* 1. Expiry Risk Forecast */}
        <div className="bg-white border border-gray-200 rounded-lg overflow-hidden shadow-sm hover:border-gray-300 transition-all duration-200">
          <div className="px-4 py-3 border-b border-gray-100 font-bold text-gray-900 bg-gray-50 flex items-center justify-between">
            <span>Expiry Proactive Risk</span>
            <span className="text-[10px] bg-red-50 text-red-700 px-2 py-0.5 rounded border border-red-100 font-bold uppercase">
              Critical
            </span>
          </div>
          <div className="p-4 space-y-4">
            <div className="flex justify-between items-center text-sm border-b border-gray-100 pb-2.5">
              <div className="flex flex-col">
                <span className="font-semibold text-gray-800">Next 30 Days</span>
                <span className="text-[10px] text-gray-500">Requires immediate vendor return</span>
              </div>
              <span className="font-black text-red-600">
                {formatINR(data.expiryRisk.next30Days)}
              </span>
            </div>
            <div className="flex justify-between items-center text-sm border-b border-gray-100 pb-2.5">
              <div className="flex flex-col">
                <span className="font-semibold text-gray-800">Next 60 Days</span>
                <span className="text-[10px] text-gray-500">Forecasted return buffer</span>
              </div>
              <span className="font-bold text-amber-600">
                {formatINR(data.expiryRisk.next60Days)}
              </span>
            </div>
            <div className="flex justify-between items-center text-sm">
              <div className="flex flex-col">
                <span className="font-semibold text-gray-800">Next 90 Days</span>
                <span className="text-[10px] text-gray-500">
                  General monitoring batch threshold
                </span>
              </div>
              <span className="font-bold text-blue-600">
                {formatINR(data.expiryRisk.next90Days)}
              </span>
            </div>
          </div>
        </div>

        {/* 2. Top Fast Moving Medicines */}
        <div className="bg-white border border-gray-200 rounded-lg overflow-hidden shadow-sm hover:border-gray-300 transition-all duration-200">
          <div className="px-4 py-3 border-b border-gray-100 font-bold text-gray-900 bg-gray-50 flex items-center justify-between">
            <span>⚡ Best-Selling Medicines</span>
            <span className="text-[10px] bg-blue-50 text-blue-700 px-2 py-0.5 rounded border border-blue-100 font-bold uppercase font-mono">
              Top 5
            </span>
          </div>
          <div className="p-4 space-y-3">
            {data.fastMoving.length > 0 ? (
              data.fastMoving.map((item, idx) => (
                <div
                  key={idx}
                  className="flex justify-between items-center text-xs pb-2 border-b border-gray-50 last:border-0 last:pb-0"
                >
                  <div className="flex flex-col gap-0.5">
                    <span className="font-semibold text-gray-900">{item.name}</span>
                    <span className="text-[10px] text-gray-500">
                      Rev: {formatINR(item.revenue)}
                    </span>
                  </div>
                  <span className="bg-blue-50 text-blue-700 font-bold px-2 py-0.5 rounded border border-blue-100">
                    Qty: {item.quantity}
                  </span>
                </div>
              ))
            ) : (
              <div className="text-center text-xs text-gray-400 py-8">
                No sales transactions completed yet.
              </div>
            )}
          </div>
        </div>

        {/* 3. Tax Summary (GST Audit) */}
        <div className="bg-white border border-gray-200 rounded-lg overflow-hidden shadow-sm hover:border-gray-300 transition-all duration-200">
          <div className="px-4 py-3 border-b border-gray-100 font-bold text-gray-900 bg-gray-50 flex items-center justify-between">
            <span>Tax Audit Summary (GST)</span>
            <span className="text-[10px] bg-green-50 text-green-700 px-2 py-0.5 rounded border border-green-100 font-bold uppercase">
              Audited
            </span>
          </div>
          <div className="p-4 space-y-4">
            <div className="flex justify-between items-center text-sm border-b border-gray-100 pb-2.5">
              <div className="flex flex-col">
                <span className="font-semibold text-gray-800">Input GST (Purchases)</span>
                <span className="text-[10px] text-gray-500">
                  Paid to suppliers during inward buy
                </span>
              </div>
              <span className="font-bold text-gray-900">{formatINR(data.taxSummary.inputGst)}</span>
            </div>
            <div className="flex justify-between items-center text-sm border-b border-gray-100 pb-2.5">
              <div className="flex flex-col">
                <span className="font-semibold text-gray-800">Output GST (Sales)</span>
                <span className="text-[10px] text-gray-500">
                  Collected from patient invoice payments
                </span>
              </div>
              <span className="font-bold text-gray-900">
                {formatINR(data.taxSummary.outputGst)}
              </span>
            </div>
            <div className="flex justify-between items-center text-sm">
              <div className="flex flex-col">
                <span className="font-bold text-gray-900">Net GST Payable</span>
                <span className="text-[10px] text-gray-500">Output GST minus Input GST claim</span>
              </div>
              <span
                className={`font-black ${Number(data.taxSummary.netGstPayable) >= 0 ? 'text-green-700' : 'text-red-600'}`}
              >
                {formatINR(data.taxSummary.netGstPayable)}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ReportsView;
