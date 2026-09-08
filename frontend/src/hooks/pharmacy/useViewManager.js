import { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';

/**
 * useViewManager - Standardizes data fetching, searching, and pagination logic
 * for pharmacy dashboard views.
 *
 * @param {Function} apiCall - The API function to call (must accept query, page, size)
 * @param {Object} options - Additional options like initial pageSize or dependencies
 */
export const useViewManager = (apiCall, options = {}) => {
  const { initialPageSize = 10, dependencies = [] } = options;
  const toast = useToast();

  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize] = useState(initialPageSize);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await apiCall(search, page, pageSize);
      setData(response.content || []);
      setTotalPages(response.totalPages || 0);
      setTotalElements(response.totalElements || 0);
    } catch (err) {
      const errMsg = err.response?.data?.message || 'Failed to load data. Please try again.';
      setError(errMsg);
      toast.error(errMsg);
    } finally {
      setLoading(false);
    }
  }, [apiCall, search, page, pageSize, ...dependencies]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleSearch = (query) => {
    setSearch(query);
    setPage(0); // Reset to first page on search
  };

  const handlePageChange = (newPage) => {
    setPage(newPage);
  };

  const refresh = () => fetchData();

  return {
    data,
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
    setPage,
  };
};
