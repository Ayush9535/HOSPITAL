import apiClient from '../apiService';

const medicinesApi = {
  // Get medicines with pagination + search
  getAll: async (search = '', page = 0, size = 10) => {
    const query = `?search=${encodeURIComponent(search)}&page=${page}&size=${size}`;

    const response = await apiClient.get(`/pharmacy/medicines${query}`);

    return response.data;
  },

  // Get medicine by ID
  getById: async (id) => {
    const response = await apiClient.get(`/pharmacy/medicines/${id}`);

    return response.data;
  },

  // Create medicine
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/medicines', data);

    return response.data;
  },

  // Update medicine
  update: async (id, data) => {
    const response = await apiClient.put(`/pharmacy/medicines/${id}`, data);

    return response.data;
  },

  // Toggle active/inactive status
  toggleStatus: async (id, isActive) => {
    const response = await apiClient.patch(`/pharmacy/medicines/${id}/status`, { isActive });

    return response.data;
  },

  // Get medicine categories
  getCategories: async () => {
    const response = await apiClient.get('/pharmacy/categories?page=0&size=1000');
    return response.data;
  },

  // Get manufacturers
  getManufacturers: async () => {
    const response = await apiClient.get('/pharmacy/manufacturers?page=0&size=1000');
    return response.data;
  },

  // Create new category
  createCategory: async (payload) => {
    const response = await apiClient.post('/pharmacy/categories', payload);
    return response.data;
  },

  // Create new manufacturer
  createManufacturer: async (payload) => {
    const response = await apiClient.post('/pharmacy/manufacturers', payload);
    return response.data;
  },

  // High-speed optimized search
  search: async (query, page = 0, size = 10) => {
    const q = `?q=${encodeURIComponent(query)}&page=${page}&size=${size}`;

    const response = await apiClient.get(`/pharmacy/search/medicines${q}`);

    return response.data;
  },

  // Dropdown autocomplete
  autocomplete: async (query) => {
    const response = await apiClient.get(
      `/pharmacy/autocomplete/medicines?q=${encodeURIComponent(query)}`
    );

    return response.data;
  },

  // Search the platform medicine catalog (name + type) for the purchase form
  searchCatalog: async (query) => {
    const response = await apiClient.get(`/pharmacy/catalog/search?q=${encodeURIComponent(query)}`);
    return response.data;
  },
};

export default medicinesApi;
