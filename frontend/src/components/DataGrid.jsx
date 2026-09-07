import { ModuleRegistry, AllCommunityModule } from 'ag-grid-community';
import { AgGridReact } from 'ag-grid-react';
import React, { useMemo } from 'react';
import 'ag-grid-community/styles/ag-grid.css';
import 'ag-grid-community/styles/ag-theme-alpine.css';
import './DataGrid.css';

// Register AG Grid modules
ModuleRegistry.registerModules([AllCommunityModule]);

/**
 * DataGrid - Reusable AG Grid wrapper component
 *
 * Provides consistent table styling and functionality across all dashboards
 *
 * @param {Array} rowData - Array of data objects to display
 * @param {Array} columnDefs - AG Grid column definitions
 * @param {Object} gridOptions - Additional AG Grid options
 * @param {Function} onRowClicked - Callback when row is clicked
 * @param {Function} onSelectionChanged - Callback when selection changes
 * @param {boolean} loading - Show loading overlay
 * @param {string} emptyMessage - Message to show when no data
 *
 * @author HMS Team
 * @version Phase-1
 */
const DataGrid = ({
  rowData = [],
  columnDefs = [],
  gridOptions = {},
  onRowClicked,
  onSelectionChanged,
  loading = false,
  emptyMessage = 'No data available',
  pagination = true,
  paginationPageSize = 10,
  domLayout = 'autoHeight',
  ...props
}) => {
  // Default grid options
  const defaultOptions = useMemo(
    () => ({
      // Pagination
      pagination: pagination,
      paginationPageSize: paginationPageSize,
      paginationPageSizeSelector: [10, 25, 50, 100],

      // Sorting
      sortingOrder: ['asc', 'desc', null],

      // Selection
      rowSelection: 'single',
      suppressRowClickSelection: false,

      // Styling
      domLayout: domLayout,
      rowHeight: 50,
      headerHeight: 45,

      // Features
      animateRows: true,
      enableCellTextSelection: true,
      suppressCellFocus: false,

      // Loading overlay
      overlayLoadingTemplate: `
            <div class="ag-overlay-loading-center">
                <div class="spinner"></div>
                <div>Loading...</div>
            </div>
        `,

      // No rows overlay
      overlayNoRowsTemplate: `
            <div class="ag-overlay-no-rows-center">
                <div class="empty-message">${emptyMessage}</div>
            </div>
        `,

      // Default column definitions
      defaultColDef: {
        sortable: true,
        filter: true,
        resizable: true,
        flex: 1,
        minWidth: 100,
      },

      ...gridOptions,
    }),
    [pagination, paginationPageSize, domLayout, emptyMessage, gridOptions]
  );

  return (
    <div className="ag-theme-alpine data-grid-wrapper" style={{ width: '100%' }}>
      <AgGridReact
        rowData={rowData}
        columnDefs={columnDefs}
        gridOptions={defaultOptions}
        onRowClicked={onRowClicked}
        onSelectionChanged={onSelectionChanged}
        loading={loading}
        {...props}
      />
    </div>
  );
};

export default DataGrid;
