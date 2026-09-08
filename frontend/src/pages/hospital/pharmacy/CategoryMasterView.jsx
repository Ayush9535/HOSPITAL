import React, { useState, useEffect, useCallback } from 'react';
import DataTable from '../../../components/DataTable';
import { useToast } from '../../../context/ToastContext';
import categoriesApi from '../../../services/pharmacy/categoriesApi';
import CategoryForm from './CategoryForm';
import { extractApiError } from '../../../utils/apiError';

/**
 * CategoryMasterView – parent view for managing medicine categories.
 * Owns all async state, API calls, and toast notifications.
 */
const CategoryMasterView = () => {
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Modal state
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState('create');
  const [selectedCategory, setSelectedCategory] = useState(null);

  // Pagination state
  const [pageInfo, setPageInfo] = useState({
    pageIndex: 0,
    pageSize: 10,
    totalItems: 0,
    pageCount: 0,
  });

  const toast = useToast();

  const fetchCategories = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await categoriesApi.getAll(
        searchTerm,
        pageInfo.pageIndex,
        pageInfo.pageSize
      );

      const { content = [], totalElements = 0, totalPages = 0 } = response || {};
      setCategories(content);
      setPageInfo((prev) => ({
        ...prev,
        totalItems: totalElements,
        pageCount: totalPages,
      }));
    } catch (err) {
      console.error('Failed to fetch categories:', err);
      setError('Unable to load categories. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [searchTerm, pageInfo.pageIndex, pageInfo.pageSize]);

  useEffect(() => {
    fetchCategories();
  }, [fetchCategories]);

  const handleSearch = (e) => {
    e.preventDefault();
    setPageInfo((prev) => ({ ...prev, pageIndex: 0 }));
    fetchCategories();
  };

  const handleAdd = () => {
    setSelectedCategory(null);
    setModalMode('create');
    setIsModalOpen(true);
  };

  const handleEdit = (category) => {
    setSelectedCategory(category);
    setModalMode('edit');
    setIsModalOpen(true);
  };

  const handleSave = async (formData) => {
    setIsSubmitting(true);
    try {
      if (modalMode === 'create') {
        await categoriesApi.create(formData);
        toast.success('Category created successfully');
      } else {
        await categoriesApi.update(selectedCategory.id, formData);
        toast.success('Category updated successfully');
      }
      setIsModalOpen(false);
      fetchCategories();
    } catch (err) {
      console.error('Save category error:', err);
      toast.error(extractApiError(err, 'Failed to save category'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleToggleStatus = async (category) => {
    try {
      await categoriesApi.toggleStatus(category.id);
      toast.success(`Category ${category.isActive ? 'deactivated' : 'activated'} successfully`);
      fetchCategories();
    } catch (err) {
      console.error('Toggle status error:', err);
      toast.error('Failed to update category status');
    }
  };

  const columns = [
    {
      header: 'Category Name',
      accessorKey: 'categoryName',
      cell: ({ getValue }) => <span className="font-semibold text-gray-900">{getValue()}</span>,
    },
    {
      header: 'Description',
      accessorKey: 'description',
      cell: ({ getValue }) => (
        <span className="text-gray-600 line-clamp-1">{getValue() || '-'}</span>
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
        const cat = row.original;
        return (
          <div className="flex items-center gap-2">
            <button
              onClick={() => handleEdit(cat)}
              className="p-1.5 text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-all"
              title="Edit Category"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                />
              </svg>
            </button>
            <button
              onClick={() => handleToggleStatus(cat)}
              className={`p-1.5 rounded-md transition-all ${
                cat.isActive
                  ? 'text-amber-600 hover:text-amber-700 hover:bg-amber-50'
                  : 'text-green-600 hover:text-green-700 hover:bg-green-50'
              }`}
              title={cat.isActive ? 'Deactivate' : 'Activate'}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d={
                    cat.isActive
                      ? 'M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728L5.636 5.636'
                      : 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z'
                  }
                />
              </svg>
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
            d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 002-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
          />
        </svg>
      </div>
      <h3 className="text-lg font-bold text-gray-900">No Categories Found</h3>
      <p className="text-gray-500 max-w-xs mx-auto mt-1">
        Try adjusting your search or add a new medicine category to get started.
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
              placeholder="Search by category name..."
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
            Add Category
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 bg-white border border-gray-200 rounded-2xl flex flex-col overflow-hidden shadow-sm">
        <DataTable
          data={categories}
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
      <CategoryForm
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleSave}
        isSubmitting={isSubmitting}
        mode={modalMode}
        initialData={selectedCategory}
      />
    </div>
  );
};

export default CategoryMasterView;
