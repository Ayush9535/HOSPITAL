import { createColumnHelper } from '@tanstack/react-table';
import React, { useState } from 'react';
import DataTable from '../../components/DataTable';
import StatusBadge from '../../components/StatusBadge';

const BillingTable = ({
  billing,
  startIndex = 0,
  pagination,
  onUpdateStatus,
  onPrint,
  updatingBillId,
  printingBillId,
  onEditItems,
}) => {
  const [expandedIds, setExpandedIds] = useState([]);

  const toggleExpand = (id) => {
    setExpandedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  };
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'expand',
      header: '',
      cell: (info) => (
        <button
          onClick={() => toggleExpand(info.row.original.id)}
          className="p-1 rounded-md hover:bg-neutral-100"
          aria-label="Toggle items"
        >
          {expandedIds.includes(info.row.original.id) ? '▾' : '▸'}
        </button>
      ),
      enableSorting: false,
    }),
    columnHelper.display({
      id: 'sno',
      header: 'S.No.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.accessor((row) => row.customId || row.id, {
      id: 'id',
      header: 'Bill No',
      cell: (info) => <span title="Bill Number">{info.getValue()}</span>,
    }),
    columnHelper.accessor('patientName', {
      header: 'Patient Name',
      cell: (info) => info.getValue() || info.row.original.patientId || '-',
    }),
    columnHelper.accessor('amount', {
      header: 'Amount',
      cell: (info) => {
        const total = info.getValue() || 0;
        const paid = info.row.original.paidAmount || 0;
        const bal = info.row.original.balance ?? total - paid;
        if (paid > 0 && info.row.original.paymentStatus !== 'PAID') {
          return (
            <div className="text-xs leading-tight">
              <div className="text-gray-500">Total: ₹{total}</div>
              <div className="text-emerald-600">Paid: ₹{paid}</div>
              <div className="font-bold text-red-700 mt-0.5 border-t border-dashed border-gray-200 pt-0.5">
                Bal: ₹{bal}
              </div>
            </div>
          );
        }
        return `₹${total}`;
      },
    }),
    columnHelper.accessor('paymentStatus', {
      header: 'Status',
      cell: (info) => {
        const status = info.getValue() || 'PENDING';
        let type = 'warning';
        if (status === 'PAID') type = 'success';
        else if (status === 'CLOSED') type = 'neutral';
        return <StatusBadge status={status} type={type} />;
      },
    }),
    columnHelper.accessor('createdAt', {
      header: 'Date',
      cell: (info) => new Date(info.getValue()).toLocaleDateString(),
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">Actions</div>,
      cell: (info) => (
        <div className="flex justify-end gap-2">
          {(info.row.original.paymentStatus === 'PENDING' ||
            info.row.original.paymentStatus === 'PARTIAL') && (
            <>
              {onEditItems && (
                <button
                  onClick={() => onEditItems(info.row.original)}
                  className="px-3 py-1 rounded-md text-sm font-medium bg-gray-100 text-gray-700 hover:bg-gray-200 transition-colors"
                >
                  Edit Charges
                </button>
              )}
              <button
                onClick={() => onUpdateStatus(info.row.original.id, 'PAID', info.row.original)}
                disabled={!!updatingBillId}
                className={`px-3 py-1 rounded-md text-sm font-medium transition-colors ${updatingBillId === info.row.original.id ? 'bg-gray-200 text-gray-400 cursor-not-allowed' : updatingBillId ? 'opacity-50 cursor-not-allowed bg-gray-100 text-gray-700' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'}`}
              >
                {updatingBillId === info.row.original.id ? 'Processing...' : 'Mark Paid'}
              </button>
            </>
          )}
          {info.row.original.paymentStatus === 'PAID' && (
            <button
              onClick={() => onPrint(info.row.original.id)}
              disabled={!!printingBillId}
              className={`px-3 py-1 rounded-md text-sm font-medium transition-colors flex items-center gap-1 ${printingBillId === info.row.original.id ? 'bg-gray-200 text-gray-400 cursor-not-allowed' : printingBillId ? 'opacity-50 cursor-not-allowed bg-gray-100 text-gray-700' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'}`}
            >
              <span>{printingBillId === info.row.original.id ? 'Printing...' : 'Print'}</span>
            </button>
          )}
        </div>
      ),
    }),
  ];

  const renderExpandedRow = (row) => {
    const items = row.original.items || [];
    const medicines = row.original.medicines || [];
    if (!items.length && !medicines.length)
      return <div className="text-sm text-slate-600">No items or medicines billed</div>;
    return (
      <div className="space-y-4 p-2 bg-slate-50/50 rounded-lg border border-dashed border-gray-200">
        {items.length > 0 && (
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
              Clinic Charges & Services
            </div>
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-gray-200">
                  <th className="text-left text-xs text-slate-500 pb-1">Description</th>
                  <th className="text-right text-xs text-slate-500 pb-1">Amount</th>
                </tr>
              </thead>
              <tbody>
                {items.map((it) => (
                  <tr key={it.id} className="border-b border-gray-100 last:border-0">
                    <td className="py-1.5 text-sm text-slate-700">{it.description}</td>
                    <td className="py-1.5 text-sm text-right text-slate-700">₹{it.amount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {medicines.length > 0 && (
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-teal-600 mb-1">
              Administered In-Clinic Medicines
            </div>
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-gray-200">
                  <th className="text-left text-xs text-slate-500 pb-1">Medicine Name</th>
                  <th className="text-center text-xs text-slate-500 pb-1">Qty</th>
                  <th className="text-right text-xs text-slate-500 pb-1">Unit Price</th>
                  <th className="text-right text-xs text-slate-500 pb-1">Amount</th>
                </tr>
              </thead>
              <tbody>
                {medicines.map((med) => (
                  <tr key={med.id} className="border-b border-gray-100 last:border-0">
                    <td className="py-1.5 text-sm text-slate-700 font-medium">
                      {med.medicineName}
                    </td>
                    <td className="py-1.5 text-sm text-center text-slate-700">{med.quantity}</td>
                    <td className="py-1.5 text-sm text-right text-slate-700">₹{med.unitPrice}</td>
                    <td className="py-1.5 text-sm text-right text-slate-700 font-semibold text-teal-600">
                      ₹{med.amount}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {row.original.markedPaidBy && (
          <div className="mt-3 pt-2 border-t border-dashed border-gray-200 text-xs text-gray-500">
            Marked Paid By:{' '}
            <span className="text-gray-700 font-semibold">{row.original.markedPaidBy}</span>
          </div>
        )}
      </div>
    );
  };

  return (
    <DataTable
      data={billing}
      columns={columns}
      pagination={pagination}
      expandedRowIds={expandedIds}
      renderExpandedRow={renderExpandedRow}
      idAccessor={'id'}
    />
  );
};

export default BillingTable;
