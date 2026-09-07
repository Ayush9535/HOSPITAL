import React, { useState, useEffect } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import escapeHtml from '../../../utils/escapeHtml';
import { backdropProps } from '../../../utils/modalA11y';
import { printHtml } from '../../../utils/printHtml';
import { titleCase } from '../../../utils/text';

/**
 * AdmissionFormModal - the nurse fills the IPD admission form to complete a
 * patient's admission (Phase 1 Nurse module). Known values arrive pre-filled;
 * the rest are editable. Save persists it; Print opens a printable copy for the
 * relative's offline signature; Mark as Admitted confirms the admission once the
 * signed form is collected.
 */

const Text = ({ label, value, onChange, wide }) => (
  <div className={wide ? 'sm:col-span-2' : ''}>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    <input
      type="text"
      value={value || ''}
      onChange={(e) => onChange(e.target.value)}
      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
    />
  </div>
);

const Select = ({ label, value, onChange, options }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    <select
      value={value || ''}
      onChange={(e) => onChange(e.target.value)}
      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
    >
      <option value="">—</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  </div>
);

export const buildPrintHtml = (f, hospital) => {
  const esc = escapeHtml;
  // A labelled field with the value sitting on an underline that fills the row.
  const fld = (label, value, flex = 1) =>
    `<span class="fld" style="flex:${flex}"><span class="lbl">${label}:</span><span class="val">${esc(value)}</span></span>`;
  // A checkbox that is ticked when the stored category matches.
  const box = (label, checked) =>
    `<span class="cbx"><b>${label}</b><span class="sq">${checked ? '✕' : ''}</span></span>`;
  const cat = f.patientCategory || '';
  const mc = (f.mediclaim || '').toLowerCase();
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" alt="logo" onerror="this.style.display='none'" style="height:58px;width:auto;object-fit:contain"/>`
    : '';

  return `<!doctype html><html><head><meta charset="utf-8"><title>Admission Form</title>
    <style>
      @page { size: A4; margin: 7mm; }
      * { box-sizing: border-box; }
      html, body { height:100%; margin:0; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11.5px; }
      /* Fixed to one printable A4 page (297 - 14mm margins), never spill to page 2. */
      .sheet { border:1px solid #111; padding:10px 14px; height:279mm; display:flex; flex-direction:column; overflow:hidden; }
      .head { margin-bottom:6px; }
      .top { display:flex; align-items:center; justify-content:space-between; gap:12px; }
      .brand { display:flex; gap:14px; align-items:center; }
      .hname { font-size:24px; font-weight:800; color:#1d4ed8; margin:0; letter-spacing:.4px; line-height:1.1; }
      .haddr { font-size:12px; color:#7c3aed; font-weight:600; margin-top:5px; }
      .title { background:#111; color:#fff; font-weight:800; letter-spacing:1px; padding:6px 14px; white-space:nowrap; }
      .rule { border:0; border-top:2px solid #111; margin:7px 0; }
      .row { display:flex; gap:16px; align-items:flex-end; margin:7px 0; }
      .fld { display:flex; align-items:flex-end; gap:4px; min-width:0; }
      .lbl { font-weight:700; white-space:nowrap; }
      .val { border-bottom:1px solid #555; flex:1; min-height:17px; padding:0 4px; }
      .cbx { display:inline-flex; align-items:center; gap:6px; margin-right:14px; }
      .sq { display:inline-block; width:16px; height:16px; border:1px solid #111; text-align:center; line-height:15px; font-weight:700; }
      .two { display:flex; gap:0; }
      .two > div { flex:1; padding:0 12px; }
      .two > div:first-child { border-right:1px solid #999; padding-left:0; }
      .sec { font-weight:700; margin:7px 0 3px; }
      .foot { border:1px solid #111; text-align:center; font-weight:700; padding:8px; margin-top:8px; }
      .subs { display:flex; gap:40px; font-size:10px; color:#444; padding-left:110px; }
      .grow { flex:1 1 auto; }
    </style></head><body>
    <div class="sheet">
      <div class="head">
        <div class="top">
          <div class="brand">${logo}<h1 class="hname">${esc(titleCase(hospital.name)) || 'Hospital'}</h1></div>
          <div class="title">ADMISSION FORM</div>
        </div>
        <div class="haddr">${esc(hospital.address)}</div>
      </div>
      <hr class="rule"/>

      <div class="row" style="justify-content:flex-end">${fld('PRN NO', f.prnNo, 0.5)}${fld('BED NO', f.bedNo, 0.5)}</div>
      <div class="row">${fld('CATEGORY', f.category)}</div>

      <div class="row">${fld("PATIENT'S NAME", [f.patientSurname, f.patientFirstName, f.husbandFatherName].filter(Boolean).join('  '))}</div>
      <div class="subs"><span>Surname</span><span>First Name</span><span>Husband's / Father Name</span></div>
      <div class="row">${fld("PATIENT'S ADDRESS", f.patientAddress)}</div>
      <div class="row">${fld('AGE', f.age, 0.5)}${fld('SEX', f.sex, 0.5)}${fld('OCCUPATION', f.occupation)}</div>
      <hr class="rule"/>

      <div class="two">
        <div>
          <div class="sec">Category:</div>
          <div style="margin:6px 0">${box('Hospital Patient', /hospital/i.test(cat))}</div>
          <div style="margin:6px 0">${box('Private Patient', /private/i.test(cat))}</div>
          <div style="margin:6px 0">${box('PMC / PMT', /pmc|pmt/i.test(cat))}</div>
          <div style="margin:6px 0"><b>Mediclaim:</b> ${box('Cashless', mc.includes('cashless'))} ${box('Reimburse', mc.includes('reimburse'))}</div>
        </div>
        <div>
          <div class="row">${fld('Name of Relative', f.relativeName)}</div>
          <div class="row">${fld('E-mail', f.email)}</div>
          <div class="row">${fld('Telephone No.', f.telephone)}</div>
          <div class="row">${fld('Receptionist Name', f.receptionistName)}</div>
          <div class="row">${fld('Ref. Dr.', f.refDr)}</div>
        </div>
      </div>
      <div class="row">${fld('Name of TPA / Insurance Co.', f.tpaName)}</div>
      <div class="row">${fld('IPD Registration No.', f.ipdRegistrationNo)}</div>
      <hr class="rule"/>

      <div class="two">
        <div>
          <div class="row">${fld('Department', f.department)}</div>
          <div class="row">${fld('Admitted Date', f.admittedDate, 0.6)}${fld('Time', f.admittedTime, 0.4)}</div>
          <div class="row">${fld('Prov. Diagnosis : 1', f.provDiagnosis1)}</div>
          <div class="row">${fld('2', f.provDiagnosis2)}</div>
        </div>
        <div>
          <div class="row">${fld('Under care of Dr.', f.underCareOfDr)}</div>
          <div class="row">${fld('Discharge Date', '', 0.6)}${fld('Time', '', 0.4)}</div>
          <div class="row">${fld('Final Diagnosis : 1', '')}</div>
          <div class="row">${fld('2', '')}</div>
        </div>
      </div>
      <div class="row">${fld('Operative &amp; Diagnostic Procedures Carried out', '')}</div>
      <hr class="rule"/>
      <div class="row">${fld('Mode of Discharge : Home (AMA/At Request)', '')}${fld('Shifted to', '')}</div>
      <div class="row">${fld('Death', '', 0.4)}${fld('Date', '', 0.3)}${fld('Time', '', 0.3)}${fld('Cert. No.', '', 0.4)}</div>
      <div class="row">${fld('Causes of death as Certified : 1', '')}</div>
      <div class="row">${fld('Hypersensitivity History', f.hypersensitivityHistory)}</div>
      <hr class="rule"/>

      <div class="sec">of Patient Relatives</div>
      <div class="row">${fld('Name of Relative', f.relativeName)}</div>
      <div class="row">${fld('Address', f.relativeAddress)}${fld('Phone', f.relativePhone, 0.5)}</div>
      <div class="row">${fld('Relative Signature', '')}</div>

      <div class="grow"></div>
      <div class="foot">पेशंटच्या अंगावर / जवळ मौल्यवान दागिने / सामान ठेवू नये. ते हरवल्यास हॉस्पिटलची जबाबदारी राहणार नाही.</div>
    </div>
    <script>
      // Print only after the page (incl. the logo image) has fully loaded, so the
      // logo is not missing from the printout. Small delay as a safety net.
      (function () {
        function go() { setTimeout(function () { window.print(); }, 250); }
        if (document.readyState === 'complete') { go(); }
        else { window.addEventListener('load', go); }
      })();
    </script>
    </body></html>`;
};

export const buildConsentHtml = (f, hospital) => {
  const esc = escapeHtml;
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = [f.patientSurname, f.patientFirstName, f.husbandFatherName]
    .filter(Boolean)
    .join(' ');
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M');
  const isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" alt="logo" onerror="this.style.display='none'" style="height:60px;width:auto;object-fit:contain"/>`
    : '';
  const fld = (label, value) =>
    `<span class="fld"><b>${label}:</b>&nbsp;<span class="val">${esc(value)}</span></span>`;
  return `<!doctype html><html><head><meta charset="utf-8"><title>General Consent Form</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      html, body { height:100%; margin:0; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:13px; line-height:1.55; }
      .sheet { border:1px solid #111; padding:18px 22px; min-height:281mm; display:flex; flex-direction:column; }
      .head { text-align:center; border-bottom:2px solid #111; padding-bottom:10px; }
      .hname { font-size:24px; font-weight:800; color:#1d4ed8; letter-spacing:.4px; margin:6px 0 2px; }
      .haddr { font-size:12.5px; font-weight:600; color:#7c3aed; }
      .title { font-size:17px; font-weight:800; margin:12px 0 10px; letter-spacing:.5px; }
      .idrow { display:flex; flex-wrap:wrap; gap:10px 26px; margin:6px 0; }
      .fld { display:inline-flex; align-items:flex-end; }
      .val { border-bottom:1px solid #555; min-width:90px; padding:0 6px; display:inline-block; min-height:16px; }
      .sex b { border:1px solid #111; padding:1px 7px; margin:0 3px; }
      .sex .on { background:#111; color:#fff; }
      p.consent { margin:16px 0; text-align:justify; }
      .grow { flex:1 1 auto; }
      .sign { display:flex; gap:40px; }
      .sign > div { flex:1; }
      .sline { margin:14px 0; }
      .sline .u { border-bottom:1px dotted #555; display:inline-block; min-width:150px; }
      .note { font-size:11px; color:#444; margin-top:6px; }
    </style></head><body>
    <div class="sheet">
      <div class="head">
        ${logo}
        <div class="hname">${hname}</div>
        <div class="haddr">${esc(hospital.address)}</div>
        <div class="title">GENERAL CONSENT FORM</div>
      </div>

      <div class="idrow" style="margin-top:12px">
        ${fld('UHID No', hospital.customId)}
        ${fld('IPD No', f.ipdRegistrationNo)}
        ${fld('MLC No', '')}
        ${fld('Bed No', f.bedNo)}
      </div>
      <div class="idrow">${fld('Patient Name', patientName)}</div>
      <div class="idrow">
        ${fld('Age', f.age)} <span style="align-self:flex-end">Month / Year</span>
        <span class="fld sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span>
        ${fld('Date', f.admittedDate)}
        ${fld('Time', f.admittedTime)}
      </div>

      <p class="consent">I hereby provide my consent for being admitted to <b>${hname}</b> and for undergoing necessary examinations, investigations and treatment prescribed by the doctors during my stay at the hospital.</p>
      <p class="consent">I also undertake that I shall obey all the rules and regulations of hospital and shall make the payment of necessary fees and charges when requested by the hospital, for which the approximate cost has been explained to me. It has also been explained to me that the cost may vary if there is a change in disease condition or if I opt for a different class of accommodation.</p>
      <p class="consent">I, along with my family, has been explained our rights and responsibilities as a patient at <b>${hname}</b>.</p>

      <div class="grow"></div>

      <div class="sign">
        <div>
          <div class="sline">Signature of Patient (Legal Guardian) : <span class="u"></span></div>
          <div class="sline">Date : <span class="u"></span></div>
          <div class="sline">Patient Name : <span class="u">${esc(patientName)}</span></div>
          <div class="sline">Hospital No. : <span class="u"></span></div>
          <div class="sline">Name &amp; Relationship : <span class="u"></span></div>
          <div class="note">(In cases where Consent is provided by him)</div>
        </div>
        <div>
          <div class="sline">Signature of Hospital Staff : <span class="u"></span></div>
          <div class="sline">Date : <span class="u"></span></div>
          <div class="sline">Name : <span class="u"></span></div>
          <div class="sline">Designation : <span class="u"></span></div>
        </div>
      </div>
    </div>
    <script>(function(){function go(){setTimeout(function(){window.print();},250);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const AdmissionFormModal = ({ admissionId, onClose, onConfirmed }) => {
  const { success, error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const [loading, setLoading] = useState(true);
  const [f, setF] = useState({});
  const [saving, setSaving] = useState(false);
  const [savedOnce, setSavedOnce] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    let active = true;
    nurseService
      .getAdmissionForm(admissionId)
      .then((data) => {
        if (active) {
          setF(data || {});
          setSavedOnce(!!data?.id);
        }
      })
      .catch(() => {
        if (active) toastError('Failed to load admission form');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [admissionId, toastError]);

  const set = (k) => (v) => setF((prev) => ({ ...prev, [k]: v }));

  const handleSave = async () => {
    setSaving(true);
    try {
      const saved = await nurseService.saveAdmissionForm(admissionId, f);
      setF(saved);
      setSavedOnce(true);
      success('Admission form saved');
    } catch (err) {
      const data = err.response?.data;
      toastError(data?.error || data?.message || 'Failed to save admission form');
    } finally {
      setSaving(false);
    }
  };

  const handlePrint = () => {
    // Prefer live branding from the backend (like the prescription/bill PDFs);
    // fall back to the session user object.
    printHtml(
      buildPrintHtml(f, {
        name: f.hospitalName || user?.hospitalName,
        address: f.hospitalAddress || user?.hospitalAddress,
        logo: f.hospitalLogoUrl || user?.logoUrl,
        nurse: user?.name,
      })
    );
  };

  const handlePrintConsent = () => {
    printHtml(
      buildConsentHtml(f, {
        name: f.hospitalName || user?.hospitalName,
        address: f.hospitalAddress || user?.hospitalAddress,
        logo: f.hospitalLogoUrl || user?.logoUrl,
        customId: f.hospitalCustomId,
        nurse: user?.name,
      })
    );
  };

  const handleConfirm = async () => {
    setConfirming(true);
    try {
      await nurseService.confirmAdmission(admissionId);
      success('Patient marked as admitted');
      onConfirmed && onConfirmed();
      onClose();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to mark admitted';
      toastError(typeof msg === 'string' ? msg : 'Failed to mark admitted');
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-3xl max-h-[92vh] overflow-y-auto">
        <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between sticky top-0 bg-white">
          <h2 className="text-lg font-bold text-gray-900">Admission Form</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700">
            ✕
          </button>
        </div>

        {loading ? (
          <LoadingSpinner />
        ) : (
          <div className="p-6 space-y-6">
            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Header</h3>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <Text label="PRN No" value={f.prnNo} onChange={set('prnNo')} />
                <Text label="Bed No" value={f.bedNo} onChange={set('bedNo')} />
                <Text label="Category (Ward)" value={f.category} onChange={set('category')} />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Patient</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Text
                  label="First Name"
                  value={f.patientFirstName}
                  onChange={set('patientFirstName')}
                />
                <Text label="Surname" value={f.patientSurname} onChange={set('patientSurname')} />
                <Text
                  label="Husband's / Father's Name"
                  value={f.husbandFatherName}
                  onChange={set('husbandFatherName')}
                  wide
                />
                <Text
                  label="Address"
                  value={f.patientAddress}
                  onChange={set('patientAddress')}
                  wide
                />
                <Text label="Age" value={f.age} onChange={set('age')} />
                <Text label="Sex" value={f.sex} onChange={set('sex')} />
                <Text label="Occupation" value={f.occupation} onChange={set('occupation')} />
                <Select
                  label="Patient Category"
                  value={f.patientCategory}
                  onChange={set('patientCategory')}
                  options={['Hospital Patient', 'Private Patient', 'PMC / PMT']}
                />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Payer / Insurance</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Select
                  label="Mediclaim"
                  value={f.mediclaim}
                  onChange={set('mediclaim')}
                  options={['Cashless', 'Reimburse']}
                />
                <Text label="TPA / Insurance Co." value={f.tpaName} onChange={set('tpaName')} />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Contacts</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Text label="Relative Name" value={f.relativeName} onChange={set('relativeName')} />
                <Text label="E-mail" value={f.email} onChange={set('email')} />
                <Text label="Telephone" value={f.telephone} onChange={set('telephone')} />
                <Text
                  label="Receptionist Name"
                  value={f.receptionistName}
                  onChange={set('receptionistName')}
                />
                <Text label="Ref. Dr" value={f.refDr} onChange={set('refDr')} />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Admission Details</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Text
                  label="IPD Registration No"
                  value={f.ipdRegistrationNo}
                  onChange={set('ipdRegistrationNo')}
                />
                <Text label="Department" value={f.department} onChange={set('department')} />
                <Text
                  label="Under care of Dr"
                  value={f.underCareOfDr}
                  onChange={set('underCareOfDr')}
                />
                <Text label="Admitted Date" value={f.admittedDate} onChange={set('admittedDate')} />
                <Text label="Admitted Time" value={f.admittedTime} onChange={set('admittedTime')} />
                <Text
                  label="Prov. Diagnosis 1"
                  value={f.provDiagnosis1}
                  onChange={set('provDiagnosis1')}
                  wide
                />
                <Text
                  label="Prov. Diagnosis 2"
                  value={f.provDiagnosis2}
                  onChange={set('provDiagnosis2')}
                  wide
                />
                <Text
                  label="Hypersensitivity History"
                  value={f.hypersensitivityHistory}
                  onChange={set('hypersensitivityHistory')}
                  wide
                />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-bold text-gray-800 mb-3">Relative (for signature)</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Text
                  label="Address"
                  value={f.relativeAddress}
                  onChange={set('relativeAddress')}
                  wide
                />
                <Text label="Phone" value={f.relativePhone} onChange={set('relativePhone')} />
              </div>
            </section>
          </div>
        )}

        <div className="px-6 py-4 border-t border-gray-200 flex flex-wrap gap-3 justify-end sticky bottom-0 bg-white">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg border border-gray-300 text-gray-700 font-semibold hover:bg-gray-100"
          >
            Close
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className={`px-4 py-2 rounded-lg text-white font-semibold ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            onClick={handlePrint}
            disabled={!savedOnce}
            title={savedOnce ? '' : 'Save the form first'}
            className={`px-4 py-2 rounded-lg font-semibold ${savedOnce ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}
          >
            Print Admission Form
          </button>
          <button
            onClick={handlePrintConsent}
            disabled={!savedOnce}
            title={savedOnce ? '' : 'Save the form first'}
            className={`px-4 py-2 rounded-lg font-semibold ${savedOnce ? 'bg-indigo-600 text-white hover:bg-indigo-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}
          >
            Print Consent Form
          </button>
          <button
            onClick={handleConfirm}
            disabled={!savedOnce || confirming}
            title={savedOnce ? '' : 'Save the form first'}
            className={`px-4 py-2 rounded-lg font-semibold ${savedOnce && !confirming ? 'bg-green-600 text-white hover:bg-green-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}
          >
            {confirming ? 'Marking…' : 'Mark as Admitted'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default AdmissionFormModal;
