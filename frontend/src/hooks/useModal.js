import { useState, useCallback } from 'react';

// Usage:
// const modal = useModal();
// modal.open(rowData, 'edit')  → opens in edit mode with that data
// modal.open(null, 'create')   → opens for new record
// modal.close()                → resets and closes
// modal.isOpen, modal.data, modal.mode
export function useModal(initialData = null) {
  const [isOpen, setIsOpen] = useState(false);
  const [data, setData] = useState(initialData);
  const [mode, setMode] = useState('view'); // 'view' | 'edit' | 'create'

  const open = useCallback((newData = null, newMode = 'view') => {
    setData(newData);
    setMode(newMode);
    setIsOpen(true);
  }, []);

  const close = useCallback(() => {
    setIsOpen(false);
    setData(null);
    setMode('view');
  }, []);

  return { isOpen, data, mode, open, close };
}
