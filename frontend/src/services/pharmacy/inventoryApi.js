import apiClient from '../apiService';

const inventoryApi = {
  // Standard list and basic partial-match search
  getInventory: async (query = '', page = 0, size = 10, categoryId = null) => {
    let params = `?q=${encodeURIComponent(query)}&page=${page}&size=${size}`;
    if (categoryId) params += `&categoryId=${categoryId}`;
    const response = await apiClient.get(`/pharmacy/inventory${params}`);
    return response.data;
  },
  // FEFO available batch autocomplete search
  searchBatches: async (query = '') => {
    const response = await apiClient.get(
      `/pharmacy/inventory/search-batches?q=${encodeURIComponent(query)}`
    );
    return response.data;
  },
  // Low Stock specialized query
  getLowStock: async (page = 0, size = 10) => {
    const response = await apiClient.get(`/pharmacy/inventory/low-stock?page=${page}&size=${size}`);
    return response.data;
  },
  getExpiring: async (daysThreshold = 30, page = 0, size = 10) => {
    const response = await apiClient.get(
      `/pharmacy/inventory/expiring?days=${daysThreshold}&page=${page}&size=${size}`
    );
    return response.data;
  },
  // Stock Adjustments
  adjustStock: async (adjustmentData) => {
    const response = await apiClient.post('/pharmacy/inventory/adjust', adjustmentData);
    return response.data;
  },
  getTransactions: async (batchId, page = 0, size = 10) => {
    const response = await apiClient.get(
      `/pharmacy/inventory/transactions/${batchId}?page=${page}&size=${size}`
    );
    return response.data;
  },
  // Create manual batch (Opening Stock)
  createBatch: async (batchData) => {
    const response = await apiClient.post('/pharmacy/inventory', batchData);
    return response.data;
  },
  // Expiry Management Operations
  blockBatch: async (id) => {
    const response = await apiClient.post(`/pharmacy/inventory/batches/${id}/block`);
    return response.data;
  },
  disposeBatch: async (id, remarks) => {
    const response = await apiClient.post(`/pharmacy/inventory/batches/${id}/dispose`, { remarks });
    return response.data;
  },
  processSupplierReturn: async (supplierId, items) => {
    const response = await apiClient.post('/pharmacy/inventory/supplier-return', {
      supplierId,
      items,
    });
    return response.data;
  },
  getReturnsHistory: async (page = 0, size = 10) => {
    const response = await apiClient.get(
      `/pharmacy/inventory/returns-history?page=${page}&size=${size}`
    );
    return response.data;
  },
};

export default inventoryApi;
