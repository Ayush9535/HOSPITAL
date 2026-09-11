import React, { useState, useEffect, useCallback } from 'react';
import DataTable from '../../../components/DataTable';
import { useToast } from '../../../context/ToastContext';
import manufacturersApi from '../../../services/pharmacy/manufacturersApi';
import { extractApiError } from '../../../utils/apiError';
import ManufacturerForm from './ManufacturerForm';

/**
 * ManufacturerMasterView – parent view for managing medicine manufacturers.
 * Owns all async state, API calls, and toast notifications.
 */
const ManufacturerMasterView = () => {
  const [manufacturers, setManufacturers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Modal state
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState('create');
  const [selectedManufacturer, setSelectedManufacturer] = useState(null);

  // Pagination state
  const [pageInfo, setPageInfo] = useState({
    pageIndex: 0,
    pageSize: 10,
    totalItems: 0,
    pageCount: 0,
  });

  const toast = useToast();

  const fetchManufacturers = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await manufacturersApi.getAll(
        searchTerm,
        pageInfo.pageIndex,
        pageInfo.pageSize
      );

      const { content = [], totalElements = 0, totalPages = 0 } = response || {};
      setManufacturers(content);
      setPageInfo((prev) => ({
        ...prev,
        totalItems: totalElements,
        pageCount: totalPages,
      }));
    } catch (err) {
      console.error('Failed to fetch manufacturers:', err);
      setError('Unable to load manufacturers. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [searchTerm, pageInfo.pageIndex, pageInfo.pageSize]);

  useEffect(() => {
    fetchManufacturers();
  }, [fetchManufacturers]);

  const handleSearch = (e) => {
    e.preventDefault();
    setPageInfo((prev) => ({ ...prev, pageIndex: 0 }));
    fetchManufacturers();
  };

  const handleAdd = () => {
    setSelectedManufacturer(null);
    setModalMode('create');
    setIsModalOpen(true);
  };

  const handleEdit = (manufacturer) => {
    setSelectedManufacturer(manufacturer);
    setModalMode('edit');
    setIsModalOpen(true);
  };

  const handleSave = async (formData) => {
    setIsSubmitting(true);
    try {
      if (modalMode === 'create') {
        await manufacturersApi.create(formData);
        toast.success('Manufacturer created successfully');
      } else {
        await manufacturersApi.update(selectedManufacturer.id, formData);
        toast.success('Manufacturer updated successfully');
      }
      setIsModalOpen(false);
      fetchManufacturers();
    } catch (err) {
      // Surface the actual reason (e.g. a validation message like "Phone must be exactly 10 digits")
      // instead of a generic failure — validation 400s come back as { errors: {field: msg} }.
      toast.error(extractApiError(err, 'Failed to save manufacturer'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleToggleStatus = async (manufacturer) => {
    try {
      await manufacturersApi.toggleStatus(manufacturer.id);
      toast.success(
        `Manufacturer ${manufacturer.isActive ? 'deactivated' : 'activated'} successfully`
      );
      fetchManufacturers();
    } catch (err) {
      console.error('Toggle status error:', err);
      toast.error('Failed to update manufacturer status');
    }
  };

  const columns = [
    {
      header: 'Manufacturer Name',
      accessorKey: 'manufacturerName',
      cell: ({ getValue }) => <span className="font-semibold text-gray-900">{getValue()}</span>,
    },
    {
      header: 'Contact Person',
      accessorKey: 'contactPerson',
      cell: ({ getValue }) => <span className="text-gray-700">{getValue() || '-'}</span>,
    },
    {
      header: 'Phone',
      accessorKey: 'phone',
      cell: ({ getValue }) => <span className="text-gray-600 text-sm">{getValue() || '-'}</span>,
    },
    {
      header: 'Email',
      accessorKey: 'email',
      cell: ({ getValue }) => (
        <span className="text-gray-600 text-sm truncate max-w-[150px] inline-block">
          {getValue() || '-'}
        </span>
      ),
    },
    {
      header: 'License No.',
      accessorKey: 'licenseNumber',
      cell: ({ getValue }) => (
        <span className="text-gray-500 font-mono text-xs">{getValue() || '-'}</span>
      ),
    },
    {
      header: 'Status',
      accessorKey: 'isActive',
      cell: ({ getValue }) => {
        const isActive = getValue();
        return (
          <span
            className={`px-2 py-1 rounded text-xs font-bold ${
              isActive
                ? 'bg-green-50 text-green-700 border border-green-100'
                : 'bg-amber-50 text-amber-700 border border-amber-100'
            }`}
          >
            {isActive ? 'Active' : 'Inactive'}
          </span>
        );
      },
    },
    {
      header: 'Actions',
      cell: ({ row }) => {
        const m = row.original;
        return (
          <div className="flex items-center gap-2">
            <button
              onClick={() => handleEdit(m)}
              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-semibold border border-gray-200 text-gray-700 bg-white hover:bg-gray-50 transition-all"
              title="Edit Manufacturer"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                />
              </svg>
              Edit
            </button>
            <button
              onClick={() => handleToggleStatus(m)}
              className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-semibold border transition-all ${
                m.isActive
                  ? 'text-amber-700 border-amber-200 bg-amber-50 hover:bg-amber-100'
                  : 'text-green-700 border-green-200 bg-green-50 hover:bg-green-100'
              }`}
              title={m.isActive ? 'Deactivate manufacturer' : 'Activate manufacturer'}
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d={
                    m.isActive
                      ? 'M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728L5.636 5.636'
                      : 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z'
                  }
                />
              </svg>
              {m.isActive ? 'Deactivate' : 'Activate'}
            </button>
          </div>
        );
      },
    },
  ];

  const pagination = {
    pageIndex: pageInfo.pageIndex,
    pageSize: pageInfo.pageSize,
    totalItems: pageInfo.totalItems,
    pageCount: pageInfo.pageCount,
    onPageChange: (newPage) => {
      setPageInfo((prev) => ({ ...prev, pageIndex: newPage }));
    },
  };

  const emptyState = (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mb-4">
        <svg
          className="w-8 h-8 text-gray-400"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
          />
        </svg>
      </div>
      <h3 className="text-lg font-bold text-gray-900">No Manufacturers Found</h3>
      <p className="text-gray-500 max-w-xs mx-auto mt-1">
        Try adjusting your search or add a new manufacturer to get started.
      </p>
    </div>
  );

  return (
    <div className="h-full flex flex-col gap-4">
      {/* Toolbar */}
      <div className="bg-white border border-gray-200 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-4 shadow-sm">
        <div className="flex flex-wrap gap-3 items-center flex-1">
          <form onSubmit={handleSearch} className="relative w-full max-w-md">
            <input
              type="text"
              placeholder="Search by manufacturer name..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-4 pr-10 py-2.5 border border-gray-200 rounded-xl w-full text-sm outline-none focus:border-gray-900 focus:ring-4 focus:ring-gray-100 transition-all"
            />
            <button
              type="submit"
              className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-900 transition-colors"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
            </button>
          </form>

          <button
            onClick={handleAdd}
            className="px-5 py-2.5 bg-gray-900 text-white text-sm font-bold rounded-xl hover:bg-gray-800 transition-all shadow-sm flex items-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            Add Manufacturer
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 bg-white border border-gray-200 rounded-2xl flex flex-col overflow-hidden shadow-sm">
        <DataTable
          data={manufacturers}
          columns={columns}
          pagination={pagination}
          loading={loading}
          emptyState={emptyState}
          idAccessor="id"
        />
      </div>

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-xl text-sm font-medium flex items-center gap-2 animate-pulse">
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          {error}
        </div>
      )}

      {/* Modal */}
      <ManufacturerForm
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleSave}
        isSubmitting={isSubmitting}
        mode={modalMode}
        initialData={selectedManufacturer}
      />
    </div>
  );
};

export default ManufacturerMasterView;
