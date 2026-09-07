import React, { useState, useEffect, useRef } from 'react';
import { useToast } from '../context/ToastContext';
import platformService from '../services/platformService';

const PAGE_SIZE = 10;

const extractError = (err, fallback) => {
  const d = err?.response?.data;
  if (!d) return fallback;
  if (typeof d === 'string') return d;
  if (d.errors && typeof d.errors === 'object') {
    return Object.values(d.errors).join(', ');
  }
  return d.message || d.error || d.detail || fallback;
};

export default function PlatformInventoryItemsTab({ hospitalType = null }) {
  const { success, error: toastError } = useToast();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Modal states
  const [showModal, setShowModal] = useState(false);
  const [editingItem, setEditingItem] = useState(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [csvImporting, setCsvImporting] = useState(false);

  const fileInputRef = useRef(null);
  const isMountedRef = useRef(false);

  useEffect(() => {
    const delayDebounce = setTimeout(() => {
      loadItems(0);
    }, 300);
    return () => clearTimeout(delayDebounce);
  }, [search, hospitalType]);

  useEffect(() => {
    if (!isMountedRef.current) {
      isMountedRef.current = true;
      return;
    }
    loadItems(page);
  }, [page]);

  const loadItems = async (pageNum) => {
    setLoading(true);
    try {
      const data = await platformService.getMasterItems(search, pageNum, PAGE_SIZE, hospitalType);
      setItems(data.content || []);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
      setPage(pageNum);
    } catch (err) {
      toastError(extractError(err, 'Failed to load master items list.'));
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setEditingItem(null);
    setName('');
    setDescription('');
    setError('');
    setShowModal(true);
  };

  const openEdit = (item) => {
    setEditingItem(item);
    setName(item.name);
    setDescription(item.description || '');
    setError('');
    setShowModal(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!name.trim()) {
      setError('Item name is required');
      return;
    }

    setSubmitting(true);
    setError('');
    try {
      const payload = {
        name: name.trim(),
        description: description.trim() || null,
      };

      if (editingItem) {
        await platformService.updateMasterItem(editingItem.id, payload, hospitalType);
        success('Master item updated successfully');
      } else {
        await platformService.createMasterItem(payload, hospitalType);
        success('Master item added successfully');
      }
      setShowModal(false);
      loadItems(page);
    } catch (err) {
      setError(extractError(err, 'Failed to save master item.'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (item) => {
    if (
      !window.confirm(
        `Are you sure you want to delete ${item.name}? This will prevent new hospital service mappings, but won't delete existing records.`
      )
    ) {
      return;
    }

    try {
      await platformService.deleteMasterItem(item.id, hospitalType);
      success('Master item deleted successfully');
      loadItems(page);
    } catch (err) {
      toastError(extractError(err, 'Failed to delete master item.'));
    }
  };

  const handleImportCsv = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setCsvImporting(true);
    try {
      await platformService.importMasterItemsCsv(file);
      success('CSV imported successfully');
      loadItems(0);
    } catch (err) {
      toastError(
        extractError(err, 'Failed to import CSV file. Make sure file formatting is correct.')
      );
    } finally {
      setCsvImporting(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row gap-4 items-center justify-between">
        <div className="w-full sm:max-w-xs relative">
          <input
            type="text"
            placeholder="Search master items..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-1 focus:ring-gray-900 focus:border-gray-900 text-sm"
          />
          <svg
            className="w-4 h-4 text-gray-400 absolute left-3 top-3"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
        </div>

        <div className="w-full sm:w-auto flex gap-2 flex-wrap items-center">
          <input
            type="file"
            accept=".csv"
            onChange={handleImportCsv}
            className="hidden"
            ref={fileInputRef}
            disabled={csvImporting}
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={csvImporting}
            className="flex-1 sm:flex-none px-4 py-2 border border-gray-300 bg-white hover:bg-gray-50 text-gray-700 rounded-lg font-semibold text-sm transition flex items-center justify-center gap-1.5 disabled:opacity-50"
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
            type="button"
            onClick={openCreate}
            className="flex-1 sm:flex-none px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition text-sm font-semibold flex items-center justify-center gap-1"
          >
            + Add Master Item
          </button>
        </div>
      </div>

      {/* Main Table */}
      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        {loading && items.length === 0 ? (
          <div className="p-8 text-center text-gray-500">
            <div className="animate-pulse space-y-4">
              <div className="h-4 bg-gray-200 rounded w-1/4 mx-auto"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
              <div className="h-10 bg-gray-100 rounded"></div>
            </div>
          </div>
        ) : items.length === 0 ? (
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
            <p className="text-gray-500 font-medium">No master items found</p>
            <p className="text-sm text-gray-400 mt-1">
              Try refining your search or add a new master item.
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
                    Item Name
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    Description
                  </th>
                  <th className="px-6 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {items.map((item, index) => (
                  <tr key={item.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-6 py-4 font-medium text-gray-500">
                      {page * PAGE_SIZE + index + 1}
                    </td>
                    <td className="px-6 py-4 text-gray-400 font-mono">#{item.id}</td>
                    <td className="px-6 py-4 font-bold text-gray-800">{item.name}</td>
                    <td className="px-6 py-4 text-gray-500">{item.description || '—'}</td>
                    <td className="px-6 py-4 text-right">
                      <button
                        type="button"
                        onClick={() => handleDelete(item)}
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
              Showing <span className="font-semibold">{page * PAGE_SIZE + 1}</span> to{' '}
              <span className="font-semibold">
                {Math.min((page + 1) * PAGE_SIZE, totalElements)}
              </span>{' '}
              of <span className="font-semibold">{totalElements}</span> master items
            </div>
            <div className="flex gap-2">
              <button
                type="button"
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
                type="button"
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page === totalPages - 1}
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
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50 animate-fadeIn">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md max-h-[90vh] overflow-y-auto transform transition-all duration-300 scale-100">
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center justify-between">
              <h3 className="text-base font-bold text-gray-900">
                {editingItem ? 'Edit Master Item' : 'Add Master Item'}
              </h3>
              <button
                type="button"
                onClick={() => setShowModal(false)}
                className="text-gray-400 hover:text-gray-600 transition"
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

            <form onSubmit={handleSubmit} className="p-6 space-y-4">
              {error && (
                <div className="p-3 bg-red-50 text-red-700 text-xs rounded-lg font-medium">
                  {error}
                </div>
              )}

              <div>
                <label
                  htmlFor="fld-10008"
                  className="block text-xs font-semibold text-gray-700 mb-1"
                >
                  Item Name <span className="text-red-500">*</span>
                </label>
                <input
                  id="fld-10008"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Cotton, Bandage, Adhesive Tape"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-1 focus:ring-gray-900 focus:border-gray-900"
                />
              </div>

              <div>
                <label
                  htmlFor="fld-10007"
                  className="block text-xs font-semibold text-gray-700 mb-1"
                >
                  Description
                </label>
                <textarea
                  id="fld-10007"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Item usage instructions or specs"
                  rows="3"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-1 focus:ring-gray-900 focus:border-gray-900"
                />
              </div>

              <div className="flex gap-3 justify-end pt-2 border-t border-gray-100">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50 transition text-sm font-semibold"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="px-4 py-2 bg-gray-900 hover:bg-gray-800 text-white rounded-lg transition text-sm font-semibold disabled:opacity-50"
                >
                  {submitting ? 'Saving...' : 'Save Item'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
