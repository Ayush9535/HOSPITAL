import apiClient from '../apiService';

const branchesApi = {
  getAll: async () => {
    const response = await apiClient.get('/pharmacy/branches');
    return response.data;
  },
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/branches', data);
    return response.data;
  },
  update: async (id, data) => {
    const response = await apiClient.put(`/pharmacy/branches/${id}`, data);
    return response.data;
  },
  resetPassword: async (id, password) => {
    const response = await apiClient.post(`/pharmacy/branches/${id}/reset-password`, { password });
    return response.data;
  },
  delete: async (id) => {
    const response = await apiClient.delete(`/pharmacy/branches/${id}`);
    return response.data;
  },
};

export default branchesApi;
