import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useToast } from '../../../context/ToastContext';
import hospitalService from '../../../services/hospitalService';
import { extractApiError } from '../../../utils/apiError';

/**
 * Hand medicine over against one prescription — the only action that takes stock off the shelf.
 *
 * <p>Two things it deliberately will not do. It will not pick the medicine: an order written as
 * free text has to be reconciled to a real inventory row by the person dispensing, because two
 * rows can share a name and the previous behaviour of taking whichever one sorted first was how
 * stock came off the wrong medicine. And it will not decide the quantity: a prescription carries
 * its dosage as free text ("500mg", "1-0-1", "5 Days"), there is no rule that turns that into a
 * number of units, and the old code answered the question by always removing exactly one.
 *
 * <p>It closes only after the server has confirmed. A failure leaves everything typed where it is
 * with the server's own message, so the user can correct and retry rather than being told it
 * worked and finding later that it did not.
 */
const DispenseModal = ({ prescription, onClose, onDispensed }) => {
  const { success, error: toastError } = useToast();

  const [medicines, setMedicines] = useState([]);
  const [medicinesError, setMedicinesError] = useState(null);
  const [loadingMedicines, setLoadingMedicines] = useState(false);
  const [search, setSearch] = useState('');

  const [selectedMedicineId, setSelectedMedicineId] = useState(prescription?.medicineId ?? '');
  const [quantity, setQuantity] = useState('');
  const [remarks, setRemarks] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState(null);

  // One key per act of dispensing, held for the life of the modal. A double-click, or a retry
  // after a timeout that actually succeeded, reaches the server under the same key and posts
  // stock once. A fresh key is only minted when the modal is opened again for a new issue.
  const idempotencyKey = useRef(
    `dispense-${prescription?.id ?? 'x'}-${
      globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
    }`
  );

  const needsReconciliation = !prescription?.medicineId;

  useEffect(() => {
    if (!needsReconciliation) return undefined;
    let active = true;
    setLoadingMedicines(true);
    setMedicinesError(null);
    hospitalService
      .getDispensableMedicines(search)
      .then((list) => {
        if (active) setMedicines(Array.isArray(list) ? list : []);
      })
      .catch((err) => {
        // Distinguished from "this facility stocks nothing": an unreachable inventory must not
        // read as an empty one.
        if (active) setMedicinesError(extractApiError(err, 'Could not load the medicine list.'));
      })
      .finally(() => {
        if (active) setLoadingMedicines(false);
      });
    return () => {
      active = false;
    };
  }, [needsReconciliation, search]);

  const selected = useMemo(
    () => medicines.find((m) => String(m.medicineId) === String(selectedMedicineId)),
    [medicines, selectedMedicineId]
  );

  // Stock for the order's own linked medicine comes with the prescription; for a newly chosen one
  // it comes from the picker.
  const availableQuantity = needsReconciliation
    ? selected?.availableQuantity
    : prescription?.availableQuantity;
  const earliestExpiry = needsReconciliation
    ? selected?.earliestExpiry
    : prescription?.earliestExpiry;

  const quantityNumber = Number(quantity);
  const quantityInvalid =
    quantity === '' || !Number.isInteger(quantityNumber) || quantityNumber <= 0;
  const exceedsStock =
    typeof availableQuantity === 'number' && !quantityInvalid && quantityNumber > availableQuantity;

  const canSubmit = !submitting && !!selectedMedicineId && !quantityInvalid && !exceedsStock;

  const handleSubmit = async (e) => {
    e.preventDefault();
    setFormError(null);

    if (!selectedMedicineId) {
      setFormError('Select the medicine being dispensed.');
      return;
    }
    if (quantityInvalid) {
      setFormError('Enter how many units are being dispensed.');
      return;
    }
    if (exceedsStock) {
      setFormError(`Only ${availableQuantity} units are in usable stock.`);
      return;
    }

    setSubmitting(true);
    try {
      await hospitalService.dispenseMedicine(prescription.id, {
        quantity: quantityNumber,
        medicineId: Number(selectedMedicineId),
        idempotencyKey: idempotencyKey.current,
        remarks: remarks || undefined,
      });
      success(`Dispensed ${quantityNumber} × ${prescription.name || prescription.medicineName}`);
      // Only now: the caller refetches the order and its stock, and the modal goes away.
      await onDispensed?.();
      onClose?.();
    } catch (err) {
      setFormError(extractApiError(err, 'Could not dispense this medicine.'));
      toastError(extractApiError(err, 'Could not dispense this medicine.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <button
        type="button"
        aria-label="Close"
        className="absolute inset-0 bg-gray-900/60 backdrop-blur-sm"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Dispense medicine"
        className="relative z-10 w-full max-w-md bg-white rounded-xl shadow-2xl border border-gray-200 flex flex-col max-h-[90vh]"
      >
        <div className="px-5 py-4 border-b border-gray-100">
          <h3 className="font-bold text-gray-900">Dispense medicine</h3>
          <p className="text-sm text-gray-600 mt-0.5">
            {prescription?.name || prescription?.medicineName}
          </p>
          <p className="text-xs text-gray-400">
            {[prescription?.dosage, prescription?.frequency, prescription?.duration]
              .filter((v) => v && v !== '-')
              .join(' · ') || 'No dosage recorded'}
          </p>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4 overflow-y-auto">
          {needsReconciliation ? (
            <div className="space-y-2">
              <label
                htmlFor="dispense-medicine"
                className="block text-xs font-semibold text-gray-700"
              >
                Inventory medicine
              </label>
              <p className="text-[11px] text-gray-500">
                This order was written as free text, so no inventory item is attached to it yet.
                Choose the medicine actually being handed over.
              </p>
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search this facility's medicines"
                aria-label="Search medicines"
                className="w-full border border-gray-300 rounded px-3 py-2 text-sm"
              />
              {medicinesError ? (
                <p role="alert" className="text-xs text-red-600">
                  {medicinesError}
                </p>
              ) : (
                <select
                  id="dispense-medicine"
                  value={selectedMedicineId}
                  onChange={(e) => setSelectedMedicineId(e.target.value)}
                  className="w-full border border-gray-300 rounded px-3 py-2 text-sm"
                >
                  <option value="">
                    {loadingMedicines ? 'Loading…' : 'Select a medicine'}
                  </option>
                  {medicines.map((m) => (
                    <option key={m.medicineId} value={m.medicineId}>
                      {m.name}
                      {m.type ? ` (${m.type})` : ''} — {m.availableQuantity ?? 0} usable
                    </option>
                  ))}
                </select>
              )}
            </div>
          ) : null}

          <div className="bg-gray-50 border border-gray-200 rounded p-3 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-600">Usable stock</span>
              <span className="font-semibold text-gray-900">
                {typeof availableQuantity === 'number' ? availableQuantity : '—'}
              </span>
            </div>
            <div className="flex justify-between mt-1">
              <span className="text-gray-600">Earliest expiry</span>
              <span className="font-semibold text-gray-900">{earliestExpiry || '—'}</span>
            </div>
            {typeof prescription?.quantityDispensed === 'number' &&
            prescription.quantityDispensed > 0 ? (
              <div className="flex justify-between mt-1">
                <span className="text-gray-600">Already dispensed</span>
                <span className="font-semibold text-gray-900">
                  {prescription.quantityDispensed}
                </span>
              </div>
            ) : null}
            <p className="text-[11px] text-gray-500 mt-2">
              Stock goes out earliest-expiry-first, across as many batches as the quantity needs.
            </p>
          </div>

          <div>
            <label htmlFor="dispense-qty" className="block text-xs font-semibold text-gray-700">
              Quantity to dispense
            </label>
            <input
              id="dispense-qty"
              type="number"
              min="1"
              step="1"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              className="mt-1 w-full border border-gray-300 rounded px-3 py-2 text-sm"
            />
            {exceedsStock ? (
              <p className="mt-1 text-xs text-red-600">
                Only {availableQuantity} units are in usable stock.
              </p>
            ) : null}
          </div>

          <div>
            <label htmlFor="dispense-remarks" className="block text-xs font-semibold text-gray-700">
              Remarks <span className="font-normal text-gray-400">(optional)</span>
            </label>
            <input
              id="dispense-remarks"
              type="text"
              maxLength={255}
              value={remarks}
              onChange={(e) => setRemarks(e.target.value)}
              className="mt-1 w-full border border-gray-300 rounded px-3 py-2 text-sm"
            />
          </div>

          {formError ? (
            <p role="alert" className="text-sm text-red-600">
              {formError}
            </p>
          ) : null}

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-4 py-2 text-sm rounded border border-gray-300 text-gray-700 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!canSubmit}
              className="px-4 py-2 text-sm rounded bg-gray-900 text-white font-semibold disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {submitting ? 'Dispensing…' : 'Dispense'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default DispenseModal;
