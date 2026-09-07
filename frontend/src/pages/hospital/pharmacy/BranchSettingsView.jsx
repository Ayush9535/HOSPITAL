import React, { useState, useEffect } from 'react';
import { useToast } from '../../../context/ToastContext';
import branchesApi from '../../../services/pharmacy/branchesApi';
import { extractApiError } from '../../../utils/apiError';

const BranchSettingsView = () => {
  const { success, error: toastError } = useToast();
  const selectedBranchId = sessionStorage.getItem('selectedBranchId');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [branchForm, setBranchForm] = useState({ name: '', address: '', phone: '' });

  useEffect(() => {
    const loadSettings = async () => {
      setLoading(true);
      try {
        // 1. Load branch details
        const branches = await branchesApi.getAll();
        const currentBranch = branches.find((b) => String(b.id) === String(selectedBranchId));
        if (currentBranch) {
          setBranchForm({
            name: currentBranch.name || '',
            address: currentBranch.address || '',
            phone: currentBranch.phone || '',
          });
        }
      } catch (err) {
        console.error('Failed to load branch settings:', err);
        toastError('Failed to load settings');
      } finally {
        setLoading(false);
      }
    };

    if (selectedBranchId) {
      loadSettings();
    }
  }, [selectedBranchId, toastError]);

  const handleSave = async (e) => {
    e.preventDefault();
    if (!branchForm.name.trim()) {
      toastError('Branch name is required');
      return;
    }

    setSaving(true);
    try {
      // Update branch details
      await branchesApi.update(selectedBranchId, {
        name: branchForm.name.trim(),
        address: branchForm.address.trim(),
        phone: branchForm.phone.trim(),
      });

      // Update sessionStorage branch name in case it changed
      sessionStorage.setItem('selectedBranchName', branchForm.name.trim());

      success('Branch settings updated successfully');
    } catch (err) {
      console.error('Failed to save settings:', err);
      toastError(extractApiError(err, 'Failed to save settings'));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="bg-white rounded-2xl border border-gray-200 p-8 shadow-sm text-center text-gray-400">
        Loading branch settings...
      </div>
    );
  }

  return (
    <div className="max-w-2xl bg-white rounded-2xl border border-gray-200/80 shadow-sm overflow-hidden">
      <div className="px-6 py-5 border-b border-gray-100 bg-gray-50/50">
        <h3 className="font-bold text-gray-900 text-base">Branch Configuration</h3>
        <p className="text-xs text-gray-500 mt-1">
          Configure profile and operational details for this outlet
        </p>
      </div>

      <form onSubmit={handleSave} className="p-6 space-y-6">
        {/* Branch Details */}
        <div className="space-y-4">
          <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wider">Branch Info</h4>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label
                htmlFor="fld-214"
                className="block text-xs font-bold text-gray-500 uppercase mb-1"
              >
                Branch Name
              </label>
              <input
                id="fld-214"
                type="text"
                value={branchForm.name}
                onChange={(e) => setBranchForm((f) => ({ ...f, name: e.target.value }))}
                className="w-full border border-gray-350 rounded-xl px-3 py-2 text-sm outline-none focus:border-gray-900 transition-all"
                placeholder="e.g. MG Road Branch"
                required
              />
            </div>

            <div>
              <label
                htmlFor="fld-213"
                className="block text-xs font-bold text-gray-500 uppercase mb-1"
              >
                Phone Number
              </label>
              <input
                id="fld-213"
                type="text"
                value={branchForm.phone}
                onChange={(e) => setBranchForm((f) => ({ ...f, phone: e.target.value }))}
                className="w-full border border-gray-350 rounded-xl px-3 py-2 text-sm outline-none focus:border-gray-900 transition-all"
                placeholder="e.g. +91 9876543210"
              />
            </div>
          </div>

          <div>
            <label
              htmlFor="fld-212"
              className="block text-xs font-bold text-gray-500 uppercase mb-1"
            >
              Address
            </label>
            <textarea
              id="fld-212"
              value={branchForm.address}
              onChange={(e) => setBranchForm((f) => ({ ...f, address: e.target.value }))}
              className="w-full border border-gray-355 rounded-xl px-3 py-2 text-sm outline-none focus:border-gray-900 transition-all h-20"
              placeholder="Full address of the outlet..."
            />
          </div>
        </div>

        {/* Footer Actions */}
        <div className="flex justify-end pt-4 border-t border-gray-100">
          <button
            type="submit"
            disabled={saving}
            className={`px-5 py-2.5 text-white text-xs font-bold rounded-xl transition-all shadow-md active:scale-95 ${
              saving
                ? 'bg-gray-400 cursor-not-allowed'
                : 'bg-gray-900 hover:bg-gray-800 cursor-pointer'
            }`}
          >
            {saving ? 'Saving changes...' : 'Save Settings'}
          </button>
        </div>
      </form>
    </div>
  );
};

export default BranchSettingsView;
