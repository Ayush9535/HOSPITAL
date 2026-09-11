import React from 'react';
import authService from '../services/authService';

/**
 * Payment capture at OPD entry — shown ONLY when the hospital's Bill Payment setting is "First"
 * (i.e. the consultation + case-paper fee is collected when the OPD case is created).
 *
 * Uses the same vocabulary as the mark-as-paid flow at the end of the normal ("Last") flow:
 * CASH or UPI, where UPI requires a UTR/transaction reference. The chosen method is stored on
 * the bill (payment_method / payment_reference) and mirrored onto the payment ledger row.
 */

/** True when this hospital collects the fee up front. */
export const isPayFirst = () => authService.getCurrentUser()?.billPaymentTiming === 'FIRST';

/** Returns an error message, or null when the payment fields are valid (or not required). */
export const validateOpdPayment = (method, reference) => {
  if (!isPayFirst()) return null;
  if (!method) return 'Select a payment method';
  if (method === 'UPI' && !String(reference || '').trim()) {
    return 'UTR / reference is required for Online/UPI payments';
  }
  return null;
};

const OpdPaymentFields = ({ method = 'CASH', reference = '', onChange }) => {
  if (!isPayFirst()) return null;

  return (
    <div className="sm:col-span-2 border border-amber-200 bg-amber-50/50 rounded-xl p-4">
      <p className="text-sm font-semibold text-gray-800">Payment</p>
      <p className="text-xs text-gray-500 mb-3">
        The consultation + case-paper fee is collected now, at OPD entry.
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <label htmlFor="fld-10002" className="block text-xs font-semibold text-gray-700 mb-1">
            Payment Method <span className="text-red-600">*</span>
          </label>
          <select
            id="fld-10002"
            value={method}
            onChange={(e) =>
              onChange({
                paymentMethod: e.target.value,
                // A UTR only belongs to a UPI payment; drop it when switching to cash.
                paymentReference: e.target.value === 'UPI' ? reference : '',
              })
            }
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white"
          >
            <option value="CASH">Cash</option>
            <option value="UPI">Online / UPI</option>
          </select>
        </div>

        {method === 'UPI' && (
          <div>
            <label htmlFor="fld-10001" className="block text-xs font-semibold text-gray-700 mb-1">
              UTR / Reference <span className="text-red-600">*</span>
            </label>
            <input
              id="fld-10001"
              type="text"
              value={reference}
              onChange={(e) =>
                onChange({ paymentMethod: method, paymentReference: e.target.value })
              }
              placeholder="Transaction reference"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </div>
        )}
      </div>
    </div>
  );
};

export default OpdPaymentFields;
