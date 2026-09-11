import React, { useState, useEffect } from 'react';
import hospitalService from '../services/hospitalService';

/**
 * Dashboard banner listing hospital-inventory items at/below their min stock
 * level. Rendered only where the caller decides (role/tenant-gated). Silent
 * (renders nothing) when there are no low-stock items or the module is off.
 */
const LowStockBanner = () => {
  const [items, setItems] = useState([]);

  useEffect(() => {
    let active = true;
    hospitalService
      .getLowStockItems()
      .then((data) => {
        if (active) setItems(data || []);
      })
      .catch(() => {
        if (active) setItems([]);
      });
    return () => {
      active = false;
    };
  }, []);

  if (!items || items.length === 0) return null;

  return (
    <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
      <div className="text-sm font-semibold text-amber-800 mb-1">Low stock alert</div>
      <div className="text-xs text-amber-700">
        {items
          .map((i) => `${i.name} (${i.stockQuantity} left, min ${i.minStockLevel})`)
          .join(' · ')}
      </div>
    </div>
  );
};

export default LowStockBanner;
