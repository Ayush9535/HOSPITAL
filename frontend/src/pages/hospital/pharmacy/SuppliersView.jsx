import React, { useState } from 'react';
import ActionMenu from '../../../components/ActionMenu';
import DataTable from '../../../components/DataTable';
import {
  ViewLayout,
  ViewToolbar,
  SearchInput,
} from '../../../components/pharmacy/shared/ViewComponents';
import SupplierForm from '../../../components/SupplierForm';
import { useToast } from '../../../context/ToastContext';
import { useViewManager } from '../../../hooks/pharmacy/useViewManager';
import authService from '../../../services/authService';
import suppliersApi from '../../../services/pharmacy/suppliersApi';

const SuppliersView = () => {
  const toast = useToast();
  const currentUser = authService.getCurrentUser();
  const isStandalonePharmacy =
    currentUser?.modules?.includes('PHARMACY') && !currentUser?.modules?.includes('OPD');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState('create');
  const [selectedSupplier, setSelectedSupplier] = useState(null);
  const [expandedRowIds, setExpandedRowIds] = useState([]);

  const toggleRow = (id) => {
    setExpandedRowIds((prev) =>
      prev.includes(id) ? prev.filter((rid) => rid !== id) : [...prev, id]
    );
  };

  const {
    data: suppliers,
    loading,
    error,
    search,
    page,
    pageSize,
    totalPages,
    totalElements,
    handleSearch,
    handlePageChange,
    refresh,
  } = useViewManager(suppliersApi.getAll);

  const handleDelete = async (supplier) => {
    if (!window.confirm(`Delete supplier "${supplier.supplierName}"? This cannot be undone.`))
      return;
    try {
      await suppliersApi.delete(supplier.id);
      toast.success('Supplier deleted');
      refresh();
    } catch (err) {
      toast.error(
        err.response?.data?.message || 'Failed to delete supplier (it may be linked to purchases)'
      );
    }
  };

  const handleSave = async (formData) => {
    try {
      if (modalMode === 'create') {
        await suppliersApi.create(formData);
        toast.success('Supplier added successfully');
      } else {
        await suppliersApi.update(selectedSupplier.id, formData);
        toast.success('Supplier updated successfully');
      }
      setIsModalOpen(false);
      refresh();
    } catch (err) {
      toast.error('Failed to save supplier');
    }
  };

  const columns = [
    {
      header: 'Supplier Details',
      cell: ({ row }) => (
        <div className="flex flex-col leading-tight">
          <span className="font-bold text-gray-900 text-sm">{row.original.supplierName}</span>
          <span className="text-[10px] text-gray-500 font-medium">
            Contact: {row.original.contactPerson || '-'}
          </span>
        </div>
      ),
    },
    { header: 'Phone', accessorKey: 'phone' },
    { header: 'Email', accessorKey: 'email' },
    {
      header: 'GST / License',
      cell: ({ row }) => (
        <div className="flex flex-col leading-tight">
          <span className="text-xs font-medium text-gray-700">
            GST: {row.original.gstNumber || '-'}
          </span>
          <span className="text-[10px] text-gray-400 font-bold">
            DL: {row.original.drugLicenseNumber || '-'}
          </span>
        </div>
      ),
    },
    {
      header: 'Terms',
      cell: ({ row }) => (
        <span className="text-xs font-medium text-gray-600">
          {row.original.creditDays || 0} Days
        </span>
      ),
    },
    {
      header: 'Status',
      accessorKey: 'isActive',
      cell: ({ getValue }) => (
        <span
          className={`px-2 py-0.5 rounded text-[10px] font-black uppercase border ${getValue() ? 'bg-green-50 text-green-700 border-green-100' : 'bg-gray-100 text-gray-600 border-gray-200'}`}
        >
          {getValue() ? 'Active' : 'Inactive'}
        </span>
      ),
    },
    {
      header: 'Actions',
      cell: ({ row }) =>
        isStandalonePharmacy ? (
          <ActionMenu
            actions={[
              {
                label: expandedRowIds.includes(row.original.id) ? 'Hide details' : 'View details',
                onClick: () => toggleRow(row.original.id),
              },
              {
                label: 'Edit details',
                onClick: () => {
                  setSelectedSupplier(row.original);
                  setModalMode('edit');
                  setIsModalOpen(true);
                },
              },
              {
                label: 'Delete',
                danger: true,
                onClick: () => handleDelete(row.original),
              },
            ]}
          />
        ) : (
          <div className="flex gap-2">
            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleRow(row.original.id);
              }}
              className="px-3 py-1 text-xs font-bold text-blue-600 bg-blue-50 border border-blue-200 rounded hover:bg-blue-100 transition-colors"
            >
              {expandedRowIds.includes(row.original.id) ? 'Collapse' : 'Details'}
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                setSelectedSupplier(row.original);
                setModalMode('edit');
                setIsModalOpen(true);
              }}
              className="px-3 py-1 text-xs font-bold text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50 transition-colors"
            >
              Edit
            </button>
          </div>
        ),
    },
  ];

  return (
    <ViewLayout
      header={
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">Suppliers & Vendors</h1>
          <p className="text-xs text-gray-500 font-medium mt-1">
            Manage procurement contacts and trade terms.
          </p>
        </div>
      }
      error={error}
      toolbar={
        <ViewToolbar
          left={
            <SearchInput
              placeholder="Search suppliers..."
              value={search}
              onChange={(e) => handleSearch(e.target.value)}
            />
          }
          right={
            <button
              onClick={() => {
                setSelectedSupplier(null);
                setModalMode('create');
                setIsModalOpen(true);
              }}
              className="px-4 py-2 bg-gray-900 text-white text-sm font-bold rounded-lg hover:bg-gray-800 transition-all shadow-md"
            >
              + Add Supplier
            </button>
          }
        />
      }
    >
      <DataTable
        data={suppliers}
        columns={columns}
        loading={loading}
        idAccessor="id"
        pagination={{
          pageIndex: page,
          pageSize: pageSize,
          totalItems: totalElements,
          pageCount: totalPages,
          onPageChange: handlePageChange,
        }}
        renderExpandedRow={({ original: supplier }) => (
          <div className="p-4 bg-gray-50 border-t border-gray-100 grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-1">
                Office Address
              </h4>
              <p className="text-sm text-gray-700">{supplier.address || 'No address provided'}</p>
            </div>
            <div>
              <h4 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-1">
                Financial Data
              </h4>
              <p className="text-xs text-gray-600 font-medium">
                Pan No: {supplier.panNumber || '-'}
              </p>
            </div>
          </div>
        )}
      />

      {isModalOpen && (
        <SupplierForm
          isOpen={isModalOpen}
          onClose={() => {
            setIsModalOpen(false);
            setSelectedSupplier(null);
          }}
          onSuccess={refresh}
          mode={modalMode}
          supplier={selectedSupplier}
        />
      )}
    </ViewLayout>
  );
};

export default SuppliersView;
