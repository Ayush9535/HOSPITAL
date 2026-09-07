import apiClient from '../apiService';

const salesApi = {
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/sales', data);
    return response.data;
  },
  getHistory: async (page = 0, size = 10) => {
    const response = await apiClient.get(`/pharmacy/sales?page=${page}&size=${size}`);
    return response.data;
  },
  getDetails: async (id) => {
    const response = await apiClient.get(`/pharmacy/sales/${id}`);
    return response.data;
  },
  getStats: async () => {
    const response = await apiClient.get('/pharmacy/sales/stats');
    return response.data;
  },
  downloadPDF: async (id) => {
    const response = await apiClient.get(`/pharmacy/sales/${id}/pdf`, {
      responseType: 'blob',
      timeout: 60000,
    });
    return response.data;
  },
  searchByBillNumber: async (billNumber) => {
    const response = await apiClient.get(
      `/pharmacy/sales/search?billNumber=${encodeURIComponent(billNumber)}`
    );
    return response.data;
  },
  processReturn: async (id, returnItems) => {
    const response = await apiClient.post(`/pharmacy/sales/${id}/return`, returnItems);
    return response.data;
  },
};

export default salesApi;
