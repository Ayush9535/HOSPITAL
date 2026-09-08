import React, { useState, useEffect, useRef } from 'react';
import { useToast } from '../context/ToastContext';
import platformService from '../services/platformService';

// BUG-015: Single source of truth for medicines page size.
const PAGE_SIZE = 10;

// BUG-013: Local error extractor aligned with the PlatformDashboard pattern.
const extractError = (err, fallback) => {
  const d = err?.response?.data;
  if (!d) return fallback;
  if (typeof d === 'string') return d;
  if (d.errors && typeof d.errors === 'object') {
    return Object.values(d.errors).join(', ');
  }
  return d.message || d.error || d.detail || fallback;
};

export default function PlatformMedicinesTab({ hospitalType = null }) {
  const { success, error: toastError } = useToast();
  const [medicines, setMedicines] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Modal states
  const [showModal, setShowModal] = useState(false);
  const [editingMedicine, setEditingMedicine] = useState(null);
  const [name, setName] = useState('');
  const [type, setType] = useState('Tablet');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [csvImporting, setCsvImporting] = useState(false);

  const fileInputRef = useRef(null);

  // BUG-014: isMountedRef ensures the page-change effect does NOT fire on the
  // initial render, preventing a duplicate loadMedicines(0) call caused by
  // both this effect and the search-debounce effect running on mount.
  const isMountedRef = useRef(false);

  // Effect 1: fires on search change (with debounce). Also fires once on mount.
  useEffect(() => {
    const delayDebounce = setTimeout(() => {
      loadMedicines(0);
    }, 300);
    return () => clearTimeout(delayDebounce);
  }, [search, hospitalType]);

  // Effect 2: fires on explicit page navigation. Skips the initial mount to
  // avoid duplicating the call that Effect 1 already makes.
  useEffect(() => {
    if (!isMountedRef.current) {
      isMountedRef.current = true;
      return; // skip first render
    }
    loadMedicines(page);
  }, [page]);

  // BUG-015: use PAGE_SIZE constant
  const loadMedicines = async (pageNum) => {
    setLoading(true);
    try {
      const data = await platformService.getPlatformMedicines(
        search,
        pageNum,
        PAGE_SIZE,
        hospitalType
      );
      setMedicines(data.content || []);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
      setPage(pageNum);
    } catch (err) {
      // BUG-013: removed console.error; use toast with extracted message
      toastError(extractError(err, 'Failed to load medicines list.'));
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setEditingMedicine(null);
    setName('');
    setType('Tablet');
    setError('');
    setShowModal(true);
  };

  const openEdit = (med) => {
    setEditingMedicine(med);
    setName(med.name);
    setType(med.type);
    setError('');
    setShowModal(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!name.trim()) {
      setError('Medicine name is required');
      return;
    }

    setSubmitting(true);
    setError('');
    const payload = {
      name: name.trim(),
      type,
    };

    try {
      if (editingMedicine) {
        await platformService.updatePlatformMedicine(editingMedicine.id, payload, hospitalType);
        success('Medicine updated successfully');
      } else {
        await platformService.createPlatformMedicine(payload, hospitalType);
        success('Medicine registered successfully');
      }
      setShowModal(false);
      loadMedicines(editingMedicine ? page : 0);
    } catch (err) {
      // BUG-013: use extractError for consistent server message
      setError(extractError(err, 'Failed to save medicine'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (med) => {
    if (
      !window.confirm(
        `Are you sure you want to delete "${med.name}"? This will remove it from the global catalog.`
      )
    )
      return;
    try {
      await platformService.deletePlatformMedicine(med.id, hospitalType);
      success('Medicine deleted successfully');
      // If deleting the last item on the page, go to previous page
      const newPage = medicines.length === 1 && page > 0 ? page - 1 : page;
      loadMedicines(newPage);
    } catch (err) {
      // BUG-013: use extractError for consistent server message
      toastError(extractError(err, 'Failed to delete medicine'));
    }
  };

  const handleCsvUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    // Reset file input value
    e.target.value = '';

    setCsvImporting(true);
    try {
      const res = await platformService.importPlatformMedicinesCsv(file);
      const msg = `Bulk Import Complete: Imported ${res.imported || 0} new, updated ${res.updated || 0} existing medicines.`;
      if (res.errors && res.errors.length > 0) {
        toastError(`${msg} ${res.errors.length} rows had errors.`);
      } else {
        success(msg);
      }
      loadMedicines(0);
    } catch (err) {
      toastError(extractError(err, 'CSV import failed. Please verify format.'));
    } finally {
      setCsvImporting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Action Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 bg-white p-4 rounded-xl border border-gray-200">
        {/* Search Bar */}
        <div className="relative w-full sm:max-w-xs">
          <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
          </span>
          <input
            type="text"
            placeholder="Search medicines by name..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full border border-gray-300 pl-10 pr-4 py-2 text-sm rounded-lg focus:ring-2 focus:ring-gray-900 focus:border-transparent outline-none bg-gray-50/50"
          />
        </div>

        {/* Import and Add Buttons */}
        <div className="flex gap-2 w-full sm:w-auto">
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleCsvUpload}
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={csvImporting}
            className="flex-1 sm:flex-none px-4 py-2 border border-gray-300 text-gray-700 bg-white rounded-lg hover:bg-gray-50 transition text-sm font-semibold disabled:opacity-50 flex items-center justify-center gap-1.5"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
              />
            </svg>
            <span>{csvImporting ? 'Importing...' : 'Import CSV'}</span>
          </button>
          <button
            onClick={openCreate}
            className="flex-1 sm:flex-none px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition text-sm font-semibold flex items-center justify-center gap-1"
          >
            + Add Medicine
          </button>
        </div>
      </div>

      {/* Main Table */}
      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        {loading && medicines.length === 0 ? (
          <div className="p-8 text-center text-gray-500">
            <div className="animate-pulse space-y-4">
              <div className="h-4 bg-gray-200 rounded w-1/4 mx-auto"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
            </div>
          </div>
        ) : medicines.length === 0 ? (
          <div className="p-12 text-center text-gray-400">
            <svg
              className="w-12 h-12 mx-auto text-gray-300 mb-3"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"
              />
            </svg>
            <p className="text-gray-500 font-medium">No medicines found</p>
            <p className="text-sm text-gray-400 mt-1">
              Try refining your search or add a new medicine.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm text-left">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    S.No.
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    ID
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    Medicine Name
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider text-center">
                    Type
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {medicines.map((med, index) => (
                  <tr key={med.id} className="hover:bg-gray-50 transition-colors">
                    {/* BUG-015: derive row serial number from PAGE_SIZE constant */}
                    <td className="px-6 py-4 font-medium text-gray-500">
                      {page * PAGE_SIZE + index + 1}
                    </td>
                    <td className="px-6 py-4 text-gray-400 font-mono">#{med.id}</td>
                    <td className="px-6 py-4 font-bold text-gray-800">{med.name}</td>
                    <td className="px-6 py-4 text-center">
                      <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-gray-100 text-gray-700">
                        {med.type || 'Tablet'}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right space-x-3">
                      <button
                        onClick={() => openEdit(med)}
                        className="text-blue-600 hover:text-blue-800 font-semibold transition"
                      >
                        Edit
                      </button>
                      <button
                        onClick={() => handleDelete(med)}
                        className="text-red-500 hover:text-red-700 font-semibold transition"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Controls */}
        {totalPages > 1 && (
          <div className="bg-gray-50 px-6 py-4 border-t border-gray-200 flex items-center justify-between">
            <div className="text-sm text-gray-500">
              {/* BUG-015: derive offsets from PAGE_SIZE constant */}
              Showing <span className="font-semibold">{page * PAGE_SIZE + 1}</span> to{' '}
              <span className="font-semibold">
                {Math.min((page + 1) * PAGE_SIZE, totalElements)}
              </span>{' '}
              of <span className="font-semibold">{totalElements}</span> medicines
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1.5 border border-gray-300 text-gray-600 bg-white rounded-lg hover:bg-gray-50 transition text-sm font-semibold disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Previous
              </button>
              <span className="px-3 py-1.5 text-sm font-semibold text-gray-700 bg-white border border-gray-300 rounded-lg">
                Page {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="px-3 py-1.5 border border-gray-300 text-gray-600 bg-white rounded-lg hover:bg-gray-50 transition text-sm font-semibold disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-md max-h-[90vh] overflow-y-auto animate-fade-in-up">
            <div className="px-6 py-4 border-b border-gray-100 flex justify-between items-center bg-gray-50">
              <h3 className="text-lg font-bold text-gray-800">
                {editingMedicine ? 'Edit Medicine details' : 'Register global medicine'}
              </h3>
              <button
                onClick={() => setShowModal(false)}
                className="text-gray-400 hover:text-gray-600 transition text-xl"
              >
                &times;
              </button>
            </div>

            <form onSubmit={handleSubmit} className="p-6 space-y-4">
              {error && (
                <div className="p-3 bg-red-50 border border-red-200 text-red-700 text-xs rounded-lg font-medium">
                  {error}
                </div>
              )}

              <div>
                <label htmlFor="fld-28" className="block text-sm font-semibold text-gray-700 mb-1">
                  Medicine Name <span className="text-red-600">*</span>
                </label>
                <input
                  id="fld-28"
                  type="text"
                  placeholder="e.g. Paracetamol 650"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  className="w-full border border-gray-300 rounded-lg px-3.5 py-2 text-sm focus:ring-2 focus:ring-gray-900 focus:border-transparent outline-none bg-white font-medium"
                />
              </div>

              <div>
                <label htmlFor="fld-27" className="block text-sm font-semibold text-gray-700 mb-1">
                  Type <span className="text-red-600">*</span>
                </label>
                <select
                  id="fld-27"
                  value={type}
                  onChange={(e) => setType(e.target.value)}
                  required
                  className="w-full border border-gray-300 rounded-lg px-3.5 py-2 text-sm focus:ring-2 focus:ring-gray-900 focus:border-transparent outline-none bg-white font-medium"
                >
                  <option value="Tablet">Tablet</option>
                  <option value="Capsule">Capsule</option>
                  <option value="Syrup">Syrup</option>
                  <option value="Injection">Injection</option>
                  <option value="Saline">Saline</option>
                  <option value="Cream">Cream</option>
                  <option value="Ointment">Ointment</option>
                  <option value="Drops">Drops</option>
                  <option value="Inhaler">Inhaler</option>
                  <option value="Other">Other</option>
                </select>
              </div>

              <div className="flex justify-end gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 transition text-sm font-semibold"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition text-sm font-semibold disabled:opacity-50 flex items-center gap-1.5"
                >
                  {submitting && (
                    <svg
                      className="animate-spin h-4 w-4 text-white"
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
                  )}
                  <span>{editingMedicine ? 'Update Medicine' : 'Register Medicine'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
