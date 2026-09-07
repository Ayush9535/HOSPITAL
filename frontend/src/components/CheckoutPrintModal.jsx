import React, { useState } from 'react';

/**
 * CheckoutPrintModal - Premium, modern print action center prompted after OPD Consultation
 *
 * Features:
 * - Stunning glassmorphism overlay and clean micro-animations
 * - Interactive action cards for each document type (Case Paper, Prescription, Bill)
 * - "Print All" bulk action to output all materials sequentially
 * - Cross-platform compatible design
 */
const CheckoutPrintModal = ({
  isOpen,
  onClose,
  opdId,
  billId,
  appointmentId,
  patientName,
  patientId,
  onPrintCasePaper,
  onPrintPrescription,
  onPrintBill,
}) => {
  const [actionState, setActionState] = useState({
    casePaper: false,
    prescription: false,
    bill: false,
    all: false,
  });

  if (!isOpen) return null;

  const handlePrint = async (type, handler) => {
    setActionState((prev) => ({ ...prev, [type]: true }));
    try {
      await handler();
    } catch (err) {
      console.error(`Failed to print ${type}`, err);
    } finally {
      setActionState((prev) => ({ ...prev, [type]: false }));
    }
  };

  const handlePrintAll = async () => {
    setActionState((prev) => ({ ...prev, all: true }));
    try {
      // Sequential printing trigger
      if (onPrintCasePaper) await onPrintCasePaper();

      // Short delay between window openings to prevent browser popup blockers from choking
      await new Promise((resolve) => setTimeout(resolve, 800));
      if (onPrintPrescription) await onPrintPrescription();

      await new Promise((resolve) => setTimeout(resolve, 800));
      if (onPrintBill && billId) await onPrintBill();
    } catch (err) {
      console.error('Failed to print all documents', err);
    } finally {
      setActionState((prev) => ({ ...prev, all: false }));
    }
  };

  return (
    <div className="fixed inset-0 bg-slate-900/60 backdrop-blur-md flex items-center justify-center z-50 p-4 transition-all duration-300">
      <div className="bg-white rounded-3xl shadow-2xl w-full max-w-lg overflow-hidden border border-slate-100 transform scale-100 transition-all duration-300 animate-fade-in-up">
        {/* Header Decoration */}
        <div className="bg-gradient-to-r from-indigo-600 to-sky-600 h-2 w-full"></div>

        {/* Body Content */}
        <div className="p-8">
          {/* Circle Icon Indicator */}
          <div className="w-16 h-16 bg-emerald-50 rounded-full flex items-center justify-center mx-auto mb-5 shadow-inner border border-emerald-100">
            <svg
              className="w-8 h-8 text-emerald-500 animate-pulse"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
          </div>

          <h3 className="text-2xl font-bold text-center text-slate-800 leading-tight">
            OPD Consultation Completed!
          </h3>
          <p className="text-center text-slate-500 text-sm mt-2 max-w-sm mx-auto">
            Please generate the checkout prints for{' '}
            <span className="font-semibold text-slate-800">{patientName || 'Patient'}</span> (ID:{' '}
            {patientId || '-'}).
          </p>

          {/* Dynamic Action List */}
          <div className="space-y-3.5 mt-8">
            {/* 1. Case Paper / Clinical Summary Card */}
            <button
              type="button"
              onClick={() => handlePrint('casePaper', onPrintCasePaper)}
              disabled={actionState.casePaper || actionState.all}
              className="w-full flex items-center justify-between p-4 bg-slate-50 hover:bg-sky-50/70 border border-slate-200/80 hover:border-sky-300 rounded-2xl transition-all duration-200 group text-left active:scale-[0.99]"
            >
              <div className="flex items-center gap-3.5">
                <div className="p-2.5 bg-sky-100 text-sky-600 rounded-xl group-hover:bg-sky-200/60 transition-colors">
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                  </svg>
                </div>
                <div>
                  <h4 className="font-bold text-slate-700 text-sm">Consultation Print</h4>
                  <p className="text-[11px] text-slate-400 mt-0.5">
                    Vitals, symptoms, diagnosis & clinical notes
                  </p>
                </div>
              </div>
              <span className="text-xs font-semibold text-sky-600 bg-sky-100/60 px-3 py-1 rounded-lg group-hover:bg-sky-200/70 transition-colors">
                {actionState.casePaper ? 'Opening...' : 'Print'}
              </span>
            </button>

            {/* 2. Prescription Card */}
            <button
              type="button"
              onClick={() => handlePrint('prescription', onPrintPrescription)}
              disabled={actionState.prescription || actionState.all}
              className="w-full flex items-center justify-between p-4 bg-slate-50 hover:bg-indigo-50/70 border border-slate-200/80 hover:border-indigo-300 rounded-2xl transition-all duration-200 group text-left active:scale-[0.99]"
            >
              <div className="flex items-center gap-3.5">
                <div className="p-2.5 bg-indigo-100 text-indigo-600 rounded-xl group-hover:bg-indigo-200/60 transition-colors">
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h10a2 2 0 012 2v1m2 13a2 2 0 01-2-2V7m2 13a2 2 0 002-2V9a2 2 0 00-2-2h-2m-4-3H9M7 16h6M7 8h6v4H7V8z"
                    />
                  </svg>
                </div>
                <div>
                  <h4 className="font-bold text-slate-700 text-sm">Prescription Print</h4>
                  <p className="text-[11px] text-slate-400 mt-0.5">
                    Rx meds & administered in-clinic medicines
                  </p>
                </div>
              </div>
              <span className="text-xs font-semibold text-indigo-600 bg-indigo-100/60 px-3 py-1 rounded-lg group-hover:bg-indigo-200/70 transition-colors">
                {actionState.prescription ? 'Opening...' : 'Print'}
              </span>
            </button>

            {/* 3. Bill / Invoice Card */}
            <button
              type="button"
              onClick={() => handlePrint('bill', onPrintBill)}
              disabled={!billId || actionState.bill || actionState.all}
              className={`w-full flex items-center justify-between p-4 rounded-2xl transition-all duration-200 group text-left border active:scale-[0.99] ${
                billId
                  ? 'bg-slate-50 hover:bg-emerald-50/70 border-slate-200/80 hover:border-emerald-300'
                  : 'bg-slate-50/40 border-slate-100 opacity-60 cursor-not-allowed'
              }`}
            >
              <div className="flex items-center gap-3.5">
                <div
                  className={`p-2.5 rounded-xl transition-colors ${billId ? 'bg-emerald-100 text-emerald-600 group-hover:bg-emerald-200/60' : 'bg-slate-100 text-slate-400'}`}
                >
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M9 8h6m-6 4h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                  </svg>
                </div>
                <div>
                  <h4
                    className={`font-bold text-sm ${billId ? 'text-slate-700' : 'text-slate-400'}`}
                  >
                    Bill / Invoice Print
                  </h4>
                  <p className="text-[11px] text-slate-400 mt-0.5">
                    {billId
                      ? 'Itemized clinical, medicine & procedure fees'
                      : 'No bill generated for this OPD'}
                  </p>
                </div>
              </div>
              {billId && (
                <span className="text-xs font-semibold text-emerald-600 bg-emerald-100/60 px-3 py-1 rounded-lg group-hover:bg-emerald-200/70 transition-colors">
                  {actionState.bill ? 'Opening...' : 'Print'}
                </span>
              )}
            </button>
          </div>

          {/* bulk actions */}
          <div className="grid grid-cols-1 gap-3.5 mt-8 border-t border-slate-100 pt-6">
            <button
              type="button"
              onClick={handlePrintAll}
              disabled={actionState.all || Object.values(actionState).some((x) => x)}
              className="w-full bg-slate-900 hover:bg-slate-800 text-white py-3.5 px-4 rounded-2xl transition font-bold text-sm flex items-center justify-center gap-2 shadow-lg shadow-slate-900/10 active:scale-95"
            >
              {actionState.all ? (
                <>
                  <svg
                    className="animate-spin h-4 w-4 text-white"
                    xmlns="http://www.w3.org/2000/svg"
                    fill="none"
                    viewBox="0 0 24 24"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    ></circle>
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    ></path>
                  </svg>
                  Opening Documents...
                </>
              ) : (
                <>
                  <svg
                    className="w-4.5 h-4.5"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2.5}
                      d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                    />
                  </svg>
                  Print All Documents (3-in-1)
                </>
              )}
            </button>

            <button
              type="button"
              onClick={onClose}
              className="w-full bg-slate-100 hover:bg-slate-200/80 text-slate-700 py-3 px-4 rounded-2xl transition font-bold text-sm active:scale-95"
            >
              Done & Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CheckoutPrintModal;
