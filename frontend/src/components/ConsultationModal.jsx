import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import authService from '../services/authService';
import hospitalService from '../services/hospitalService';
import vitalsService from '../services/vitalsService';
import { extractApiError } from '../utils/apiError';
import CharCountInput from './CharCountInput';
import DateSelect from './DateSelect';
import FrequencyInput, { isFrequencyValid } from './FrequencyInput';
import IpdAdmitModal from './IpdAdmitModal';
import ManageInClinicPresetsModal from './ManageInClinicPresetsModal';
import ManageNotePresetsModal from './ManageNotePresetsModal';
import ManagePrescriptionPresetsModal from './ManagePrescriptionPresetsModal';
import MedicineAutocomplete from './MedicineAutocomplete';

/** Built-in vital key -> the Opd field that stores it. */
const BUILT_IN_OPD_FIELD = {
  BP: 'bp',
  TEMPERATURE: 'temperature',
  PULSE: 'pulse',
  HEIGHT: 'height',
  WEIGHT: 'weight',
  SPO2: 'spo2',
};

const ConsultationModal = ({ isOpen, onClose, onSuccess, appointment, patient, opd }) => {
  // Only the vitals this hospital has switched on are shown at diagnosis.
  const [enabledVitals, setEnabledVitals] = useState([]);
  useEffect(() => {
    if (!isOpen) return;
    let active = true;
    vitalsService
      .enabled()
      .then((list) => {
        if (active) setEnabledVitals(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (active) setEnabledVitals([]);
      });
    return () => {
      active = false;
    };
  }, [isOpen]);

  // opd.customVitals is a JSON string of hospital-defined vitals.
  const opdCustomVitals = React.useMemo(() => {
    if (!opd?.customVitals) return {};
    try {
      return JSON.parse(opd.customVitals) || {};
    } catch {
      return {};
    }
  }, [opd?.customVitals]);

  console.log('ConsultationModal render:', { isOpen, appointment, patient, opd });
  const [activeTab, setActiveTab] = useState('clinical'); // 'clinical' or 'prescription'
  const [patientDetails, setPatientDetails] = useState(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [showIpdAdmitModal, setShowIpdAdmitModal] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [admitModalOpd, setAdmitModalOpd] = useState(null);
  const [notePresets, setNotePresets] = useState([]);
  const [showManagePresets, setShowManagePresets] = useState(false);
  const [symptomPresets, setSymptomPresets] = useState([]);
  const [showManageSymptomPresets, setShowManageSymptomPresets] = useState(false);
  const [diagnosisPresets, setDiagnosisPresets] = useState([]);
  const [showManageDiagnosisPresets, setShowManageDiagnosisPresets] = useState(false);
  const [prescriptionPresets, setPrescriptionPresets] = useState([]);
  const [showManagePrescriptionPresets, setShowManagePrescriptionPresets] = useState(false);
  const [showManageInClinicPresets, setShowManageInClinicPresets] = useState(false);
  const [editingMedicineIndex, setEditingMedicineIndex] = useState(null);

  const user = authService.getCurrentUser();
  const modules = user?.modules || [];
  const hasIPD = modules.includes('IPD');
  const hasBilling = modules.includes('BILLING');
  const hasMedicalInventory = modules.includes('MEDICAL_INVENTORY');
  const hasHospitalInventory = modules.includes('HOSPITAL_INVENTORY');
  const [inventory, setInventory] = useState([]);
  const [administeredList, setAdministeredList] = useState([]); // List of { medicineId, medicineName, quantity, maxStock }

  // Update a single field on one administered-medicine row (keeps the row-level handlers flat).
  const updateAdministeredField = (medicineId, field, val) =>
    setAdministeredList((prev) =>
      prev.map((x) => (x.medicineId === medicineId ? { ...x, [field]: val } : x))
    );
  const [inClinicPresets, setInClinicPresets] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [showSuggestions, setShowSuggestions] = useState(false);

  /**
   * Add every medicine in a preset to the administered list in one click. Quantities are
   * capped at what's actually in stock (and an already-added medicine is topped up rather
   * than duplicated), so a preset can never push the list past available stock.
   */
  const applyInClinicPreset = (preset) => {
    const skipped = [];
    setAdministeredList((prev) => {
      const next = [...prev];
      (preset.items || []).forEach((item) => {
        // Presets store the medicine by name (global catalog); resolve it to the clinic's
        // current stock by name so it can be dispensed & deducted at consultation completion.
        // Fall back to medicineId for presets saved before the name-based change.
        const wantedName = (item.medicineName || '').trim().toLowerCase();
        const stockItem = inventory.find(
          (m) =>
            (item.medicineId && m.id === item.medicineId) ||
            (wantedName && m.name && m.name.toLowerCase() === wantedName)
        );
        if (!stockItem) {
          skipped.push(item.medicineName);
          return;
        }

        const maxStock = stockItem.stockQuantity ?? 0;
        const wanted = Number(item.quantity) || 1;
        const existing = next.find((x) => x.medicineId === stockItem.id);
        const current = existing ? existing.quantity : 0;
        const allowed = Math.min(current + wanted, maxStock);

        if (allowed <= current) {
          skipped.push(stockItem.name);
          return;
        }

        // The preset's own dosage/frequency/duration/instructions win; fall back to the
        // medicine's defaults, exactly as picking it manually from the dropdown does.
        if (existing) {
          existing.quantity = allowed;
          existing.dosage = item.dosage || existing.dosage || stockItem.defaultDosage || '';
          existing.frequency =
            item.frequency || existing.frequency || stockItem.defaultFrequency || '';
          existing.duration = item.duration || existing.duration || stockItem.defaultDuration || '';
          existing.instructions = item.instructions || existing.instructions || '';
        } else {
          next.push({
            medicineId: stockItem.id,
            medicineName: stockItem.name,
            quantity: allowed,
            maxStock,
            dosage: item.dosage || stockItem.defaultDosage || '',
            frequency: item.frequency || stockItem.defaultFrequency || '',
            duration: item.duration || stockItem.defaultDuration || '',
            instructions: item.instructions || '',
          });
        }
      });
      return next;
    });

    if (skipped.length) {
      toastError(`Not added (out of stock or unavailable): ${[...new Set(skipped)].join(', ')}`);
    }
  };

  const [appliedCharges, setAppliedCharges] = useState([]);
  const [availableCustomFees, setAvailableCustomFees] = useState([]);
  const [standardFees, setStandardFees] = useState({ consultationFee: 0, casePaperFee: 0 });

  useEffect(() => {
    if (isOpen) {
      const fetchFees = async () => {
        if (!hasBilling) {
          setStandardFees({ consultationFee: 0, casePaperFee: 0 });
          setAvailableCustomFees([]);
          setAppliedCharges([]);
          return;
        }
        try {
          const [stdData, customData] = await Promise.all([
            hospitalService.getHospitalFees(),
            hospitalService.getCustomFees(),
          ]);
          const consultFee =
            stdData.consultationFee != null ? Number(stdData.consultationFee) : 500;
          const casePaperFee = stdData.casePaperFee != null ? Number(stdData.casePaperFee) : 100;
          setStandardFees({ consultationFee: consultFee, casePaperFee });
          setAvailableCustomFees(customData || []);

          // Initialize applied charges
          const initialCharges = [{ description: 'Consultation Fee', amount: consultFee }];
          if (opd && casePaperFee > 0) {
            initialCharges.push({ description: 'Case Paper Fee', amount: casePaperFee });
          }
          setAppliedCharges(initialCharges);
        } catch (e) {
          console.error('Failed to fetch fees', e);
        }
      };
      fetchFees();
    }
  }, [isOpen, opd, hasBilling]);

  useEffect(() => {
    if (isOpen) {
      hospitalService
        .getConsultationNotePresets('TREATMENT_NOTES')
        .then((data) => setNotePresets(data || []))
        .catch(() => setNotePresets([]));
      hospitalService
        .getConsultationNotePresets('SYMPTOMS')
        .then((data) => setSymptomPresets(data || []))
        .catch(() => setSymptomPresets([]));
      hospitalService
        .getConsultationNotePresets('DIAGNOSIS')
        .then((data) => setDiagnosisPresets(data || []))
        .catch(() => setDiagnosisPresets([]));
    }
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) {
      hospitalService
        .getPrescriptionPresets()
        .then((data) => setPrescriptionPresets(data || []))
        .catch(() => setPrescriptionPresets([]));
    }
  }, [isOpen]);

  // Real-time sync: refresh the preset dropdown/chips when a preset changes
  // anywhere in this hospital (e.g. admin assigns one to this doctor).
  useEffect(() => {
    if (!isOpen) return;
    const handler = () => {
      hospitalService
        .getConsultationNotePresets('TREATMENT_NOTES')
        .then((data) => setNotePresets(data || []))
        .catch(() => {
          /* non-critical refresh */
        });
      hospitalService
        .getConsultationNotePresets('SYMPTOMS')
        .then((data) => setSymptomPresets(data || []))
        .catch(() => {
          /* non-critical refresh */
        });
      hospitalService
        .getConsultationNotePresets('DIAGNOSIS')
        .then((data) => setDiagnosisPresets(data || []))
        .catch(() => {
          /* non-critical refresh */
        });
      hospitalService
        .getPrescriptionPresets()
        .then((data) => setPrescriptionPresets(data || []))
        .catch(() => {
          /* non-critical refresh */
        });
    };
    globalThis.addEventListener('hms:presets-updated', handler);
    return () => globalThis.removeEventListener('hms:presets-updated', handler);
  }, [isOpen]);

  const [hospitalInventory, setHospitalInventory] = useState([]);
  const [hospitalInventoryCatalog, setHospitalInventoryCatalog] = useState([]);
  const [hospitalInvSearch, setHospitalInvSearch] = useState('');
  const [hospitalInvDropdown, setHospitalInvDropdown] = useState(false);
  const [hospitalInvItems, setHospitalInvItems] = useState([]);
  // Extracted so the JSX handlers aren't deeply-nested arrows-in-arrows.
  const removeHospitalInvItem = (serviceId) =>
    setHospitalInvItems((prev) => prev.filter((x) => x.serviceId !== serviceId));

  // Returns the first dependency name that lacks `needed` units of stock, or
  // null. `blockOnMissing` preserves the original per-call semantics: some
  // paths treat an entirely-missing dependency as out of stock, one does not.
  const firstDepletedDependency = (itemNames, needed, blockOnMissing) => {
    for (const depName of itemNames || []) {
      const match = hospitalInventory.find(
        (x) => x.isActive !== false && x.name?.toLowerCase() === depName.toLowerCase()
      );
      if (!match) {
        if (blockOnMissing) return depName;
        continue;
      }
      if (match.stockQuantity < needed) return depName;
    }
    return null;
  };

  const decrementHospitalInvItem = (item) => {
    if (item.qty > 1) {
      setHospitalInvItems((prev) =>
        prev.map((x) => (x.serviceId === item.serviceId ? { ...x, qty: x.qty - 1 } : x))
      );
    }
  };

  const incrementHospitalInvItem = (item) => {
    const depleted = firstDepletedDependency(item.itemNames, item.qty + 1, true);
    if (depleted) {
      toastError(`Cannot perform ${item.name}: ${depleted} is out of stock in hospital inventory.`);
      return;
    }
    setHospitalInvItems((prev) =>
      prev.map((x) => (x.serviceId === item.serviceId ? { ...x, qty: x.qty + 1 } : x))
    );
  };

  const addOrIncrementHospitalInvItem = (catItem) => {
    const existing = hospitalInvItems.find((x) => x.serviceId === catItem.id);
    if (existing) {
      // Increment: original ignores a fully-missing dependency here.
      const depleted = firstDepletedDependency(catItem.itemNames, existing.qty + 1, false);
      if (depleted) {
        toastError(
          `Cannot perform ${catItem.name}: ${depleted} is out of stock in hospital inventory.`
        );
        return;
      }
      setHospitalInvItems((prev) =>
        prev.map((x) => (x === existing ? { ...x, qty: x.qty + 1 } : x))
      );
    } else {
      // First add: original blocks when a dependency is missing or below 1.
      const depleted = firstDepletedDependency(catItem.itemNames, 1, true);
      if (depleted) {
        toastError(
          `Cannot perform ${catItem.name}: ${depleted} is out of stock in hospital inventory.`
        );
        setHospitalInvSearch('');
        setHospitalInvDropdown(false);
        return;
      }
      setHospitalInvItems((prev) => [
        ...prev,
        {
          serviceId: catItem.id,
          name: catItem.name,
          qty: 1,
          feeAmount: catItem.charge || 0,
          feeName: catItem.name,
          itemNames: catItem.itemNames || [],
        },
      ]);
    }
    setHospitalInvSearch('');
    setHospitalInvDropdown(false);
  };

  useEffect(() => {
    if (isOpen) {
      const fetchInventory = async () => {
        if (!hasMedicalInventory) {
          setInventory([]);
          return;
        }
        try {
          const res = await hospitalService.getInventoryMedicines();
          const filtered = (res || []).filter((item) => {
            if (!item) return false;
            const activeVal = item.isActive !== undefined ? item.isActive : item.active;
            const isNotInactive = activeVal !== false && activeVal !== 0 && activeVal !== '0';
            const stock = item.stockQuantity !== undefined ? item.stockQuantity : 0;
            return isNotInactive && stock > 0;
          });
          setInventory(filtered);
        } catch (err) {
          console.error('Failed to load clinical stock inventory', err);
        }
      };
      const fetchHospitalInventory = async () => {
        if (!hasHospitalInventory) {
          setHospitalInventory([]);
          setHospitalInventoryCatalog([]);
          return;
        }
        try {
          const [invRes, catRes] = await Promise.all([
            hospitalService.getHospitalInventory(),
            hospitalService.getHospitalServices(),
          ]);
          setHospitalInventory(invRes || []);
          setHospitalInventoryCatalog(catRes || []);
        } catch (err) {
          console.error('Failed to load hospital services/inventory', err);
        }
      };
      // In-Clinic presets: named bundles of stock medicines, applied in one click below.
      const fetchInClinicPresets = async () => {
        try {
          setInClinicPresets((await hospitalService.getInClinicPresets()) || []);
        } catch (err) {
          console.error('Failed to load in-clinic presets', err);
        }
      };
      fetchInventory();
      fetchHospitalInventory();
      fetchInClinicPresets();
    }
    if (!isOpen) {
      setAdministeredList([]);
      setSearchQuery('');
      setShowSuggestions(false);
      setHospitalInvItems([]);
      setHospitalInvSearch('');
    }
  }, [isOpen, hasMedicalInventory, hasHospitalInventory]);

  const resolvedPatientId =
    appointment?.patientId ||
    patient?.publicId ||
    patient?.id ||
    (opd && opd.patient && (opd.patient.publicId || opd.patient.id)) ||
    null;

  const [formData, setFormData] = useState({
    appointmentId: appointment?.id || null,
    patientId: resolvedPatientId,
    opdId: opd?.id || null,
    symptoms: '',
    diagnosis: '',
    treatmentNotes: '',
    followUpDate: '',
    followUpRequired: false,
    labRequired: false,
    labTests: [],
    prescription: [],
  });

  const [newMedicine, setNewMedicine] = useState({
    medicineName: '',
    dosage: '',
    frequency: '',
    duration: '',
    instructions: '',
  });
  // Text typed into the catalogue field that has not been resolved to a medicine. Typing alone
  // names nothing, so this is the difference between an empty field and a doctor who believes
  // they have prescribed something.
  const [unresolvedMedicineText, setUnresolvedMedicineText] = useState('');

  const LAB_OPTIONS = [
    'CBC',
    'LFT',
    'RFT',
    'RBS',
    'Lipid Profile',
    'TSH',
    'HbA1c',
    'Urine Routine',
  ];

  useEffect(() => {
    if (isOpen) {
      const currentPid =
        appointment?.patientId ||
        patient?.publicId ||
        patient?.id ||
        (opd && opd.patient && (opd.patient.publicId || opd.patient.id)) ||
        null;
      setFormData({
        appointmentId: appointment?.id || null,
        patientId: currentPid,
        opdId: opd?.id || null,
        symptoms: '',
        diagnosis: '',
        treatmentNotes: '',
        followUpDate: '',
        followUpRequired: false,
        labRequired: false,
        labTests: [],
        prescription: [],
      });
      setAdministeredList([]);
      setHospitalInvItems([]);
      setNewMedicine({
        medicineName: '',
        dosage: '',
        frequency: '',
        duration: '',
        instructions: '',
      });
      setUnresolvedMedicineText('');
      setSearchQuery('');
      setHospitalInvSearch('');
      setActiveTab('clinical');
      setShowIpdAdmitModal(false);
      setSubmitting(false);
      setAdmitModalOpd(null);
      setHospitalInvDropdown(false);
      setShowSuggestions(false);
    }
  }, [isOpen, patient?.id, patient?.publicId, appointment?.id, appointment?.patientId, opd?.id]);

  const { success, error: toastError } = useToast();

  useEffect(() => {
    const fetchPatientDetails = async () => {
      const pid =
        appointment?.patientId ||
        patient?.publicId ||
        patient?.id ||
        (opd && opd.patient && (opd.patient.publicId || opd.patient.id));
      if (isOpen && pid) {
        setLoadingDetails(true);
        try {
          const details = await hospitalService.getPatientConsultationDetails(pid);
          setPatientDetails(details);
        } catch (err) {
          console.error('Failed to fetch patient details:', err);
          setPatientDetails(null);
        } finally {
          setLoadingDetails(false);
        }
      } else {
        setPatientDetails(null);
      }
    };
    fetchPatientDetails();
  }, [isOpen, appointment?.patientId, patient?.publicId, patient?.id, opd?.id]);

  if (!isOpen || (!appointment && !patient)) return null;

  const handleChange = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const handleInsertPreset = (text) => {
    setFormData((prev) => ({
      ...prev,
      treatmentNotes: prev.treatmentNotes ? `${prev.treatmentNotes}\n${text}` : text,
    }));
  };

  const handleInsertSymptomPreset = (text) => {
    setFormData((prev) => ({
      ...prev,
      symptoms: prev.symptoms ? `${prev.symptoms}\n${text}` : text,
    }));
  };

  const handleInsertDiagnosisPreset = (text) => {
    setFormData((prev) => ({
      ...prev,
      diagnosis: prev.diagnosis ? `${prev.diagnosis}\n${text}` : text,
    }));
  };

  const handleAddMedicine = () => {
    if (!isFrequencyValid(newMedicine.frequency)) {
      toastError(
        'Set a frequency: at least one of Morning / Afternoon / Night, or tick "As Per Required".'
      );
      return;
    }
    setFormData((prev) => {
      if (editingMedicineIndex !== null) {
        const updated = [...prev.prescription];
        updated[editingMedicineIndex] = newMedicine;
        return { ...prev, prescription: updated };
      }
      return { ...prev, prescription: [...prev.prescription, newMedicine] };
    });
    setNewMedicine({
      medicineName: '',
      dosage: '',
      frequency: '',
      duration: '',
      instructions: '',
    });
    setUnresolvedMedicineText('');
    setEditingMedicineIndex(null);
  };

  const handleEditMedicine = (index) => {
    setNewMedicine(formData.prescription[index]);
    setEditingMedicineIndex(index);
  };

  const handleCancelEditMedicine = () => {
    setNewMedicine({
      medicineName: '',
      dosage: '',
      frequency: '',
      duration: '',
      instructions: '',
    });
    setEditingMedicineIndex(null);
  };

  const handleApplyPrescriptionPreset = (presetId) => {
    if (!presetId) return;
    const preset = prescriptionPresets.find((p) => String(p.id) === String(presetId));
    if (!preset || !preset.items || preset.items.length === 0) return;
    const itemsToAdd = preset.items.map(
      ({ medicineName, dosage, frequency, duration, instructions }) => ({
        medicineName,
        dosage: dosage || '',
        frequency: frequency || '',
        duration: duration || '',
        instructions: instructions || '',
      })
    );
    setFormData((prev) => ({
      ...prev,
      prescription: [...prev.prescription, ...itemsToAdd],
    }));
  };

  const handleSaveCurrentAsPreset = async () => {
    if (formData.prescription.length === 0) {
      toastError('Add at least one medicine before saving a preset');
      return;
    }
    const name = window.prompt('Name this preset (e.g. "Fever Protocol"):');
    if (!name || !name.trim()) return;
    try {
      await hospitalService.createPrescriptionPreset({
        name: name.trim(),
        items: formData.prescription,
      });
      success('Preset saved');
      const data = await hospitalService.getPrescriptionPresets();
      setPrescriptionPresets(data || []);
    } catch (err) {
      toastError(extractApiError(err, 'Failed to save preset'));
    }
  };

  /** Turn the medicines currently being administered into a reusable in-clinic preset. */
  const handleSaveCurrentAsInClinicPreset = async () => {
    if (administeredList.length === 0) {
      toastError('Add at least one in-clinic medicine before saving a preset');
      return;
    }
    const name = window.prompt('Name this in-clinic preset (e.g. "Dressing Kit"):');
    if (!name || !name.trim()) return;
    try {
      await hospitalService.createInClinicPreset({
        name: name.trim(),
        items: administeredList.map((it) => ({
          medicineId: it.medicineId,
          medicineName: it.medicineName,
          quantity: it.quantity,
          dosage: it.dosage || '',
          frequency: it.frequency || '',
          duration: it.duration || '',
          instructions: it.instructions || '',
        })),
      });
      success('In-clinic preset saved');
      setInClinicPresets((await hospitalService.getInClinicPresets()) || []);
    } catch (err) {
      toastError(extractApiError(err, 'Failed to save preset'));
    }
  };

  const handleRemoveMedicine = (index) => {
    setFormData((prev) => ({
      ...prev,
      prescription: prev.prescription.filter((_, i) => i !== index),
    }));
    if (editingMedicineIndex === index) {
      // The row being edited was itself removed — clear the edit form.
      handleCancelEditMedicine();
    } else if (editingMedicineIndex !== null && index < editingMedicineIndex) {
      // A row before the one being edited was removed — the array
      // shifted, so the edit target's index must shift down too, or
      // "Save Changes" would write into the wrong (now-shifted) slot.
      setEditingMedicineIndex(editingMedicineIndex - 1);
    }
  };

  // Shared pre-submit check for both the normal submit and the admit-to-IPD submit:
  // in-clinic quantities must be > 0 and every medicine must carry a valid frequency
  // (at least one of Morning/Afternoon/Night, or "As Per Required").
  const validateMedicines = () => {
    for (const item of administeredList) {
      if (!(Number(item.quantity) > 0)) {
        return `Quantity for ${item.medicineName} must be greater than 0.`;
      }
      if (!isFrequencyValid(item.frequency)) {
        return `Set a frequency for ${item.medicineName}: at least one dose time, or "As Per Required".`;
      }
    }
    for (const med of formData.prescription || []) {
      if (!isFrequencyValid(med.frequency)) {
        return `Set a frequency for ${med.medicineName}: at least one dose time, or "As Per Required".`;
      }
    }
    return null;
  };

  const handleSubmit = async () => {
    if (submitting) return;
    // Typed but never picked: the catalogue field holds no medicine, so submitting here would
    // quietly record a consultation with no prescription the doctor believes they wrote.
    if (unresolvedMedicineText.trim() && !newMedicine.medicineName) {
      toastError(
        'Select the medicine from the suggestions, or clear the field, before submitting.'
      );
      return;
    }
    // Warning if user typed a medicine but didn't click Add
    if (newMedicine.medicineName) {
      toastError("Please click '+ Add Medicine' or clear the medicine fields before submitting.");
      return;
    }

    const medError = validateMedicines();
    if (medError) {
      toastError(medError);
      return;
    }

    // Prepare payload; include selected lab tests if any
    const payload = { ...formData };
    if (!payload.labRequired) payload.labTests = [];
    payload.administeredItems = administeredList.map((item) => ({
      medicineId: item.medicineId,
      medicineName: item.medicineName,
      quantity: item.quantity,
      dosage: item.dosage || '',
      frequency: item.frequency || '',
      duration: item.duration || '',
      instructions: item.instructions || '',
    }));
    payload.charges = [...appliedCharges].map((c) => ({
      description: c.description,
      amount: Number(c.amount || 0),
    }));
    // Include hospital inventory items used
    payload.hospitalInventoryItems = hospitalInvItems.map((item) => ({
      serviceId: item.serviceId,
      quantity: item.qty,
    }));

    console.log('Submitting Consultation Data:', JSON.stringify(payload, null, 2));

    setSubmitting(true);
    try {
      const res = await hospitalService.submitConsultation(payload);
      success('Consultation submitted successfully');
      onSuccess(res);
      onClose();
    } catch (err) {
      console.error('Consultation failed', err);
      const errorMsg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        (typeof err.response?.data === 'string' ? err.response.data : null) ||
        'Failed to submit consultation';
      toastError(errorMsg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleAdmitToIpdClick = async () => {
    if (submitting) return;
    if (unresolvedMedicineText.trim() && !newMedicine.medicineName) {
      toastError(
        'Select the medicine from the suggestions, or clear the field, before submitting.'
      );
      return;
    }
    // Warning if user typed a medicine but didn't click Add
    if (newMedicine.medicineName) {
      toastError("Please click '+ Add Medicine' or clear the medicine fields before submitting.");
      return;
    }

    const medError = validateMedicines();
    if (medError) {
      toastError(medError);
      return;
    }

    // Prepare payload; include selected lab tests if any
    const payload = { ...formData, ipdAdmitRecommended: true };
    if (!payload.labRequired) payload.labTests = [];
    payload.administeredItems = administeredList.map((item) => ({
      medicineId: item.medicineId,
      medicineName: item.medicineName,
      quantity: item.quantity,
      dosage: item.dosage || '',
      frequency: item.frequency || '',
      duration: item.duration || '',
      instructions: item.instructions || '',
    }));
    payload.charges = [...appliedCharges].map((c) => ({
      description: c.description,
      amount: Number(c.amount || 0),
    }));
    // Include hospital inventory items used
    payload.hospitalInventoryItems = hospitalInvItems.map((item) => ({
      serviceId: item.serviceId,
      quantity: item.qty,
    }));

    setSubmitting(true);
    try {
      await hospitalService.submitConsultation(payload);
      success('Consultation completed. Patient IPD admission request sent to reception.');
      onSuccess('Patient IPD admission recommended successfully!');
      onClose();
    } catch (err) {
      console.error('Consultation submit for IPD failed', err);
      const errorMsg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        (typeof err.response?.data === 'string' ? err.response.data : null) ||
        'Failed to submit consultation';
      toastError(errorMsg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-6xl h-[90vh] flex flex-col m-4 animate-fade-in-up">
        {/* Header */}
        <div className="p-6 border-b border-gray-200 flex justify-between items-center bg-white rounded-t-lg">
          <div>
            <h3 className="text-xl font-bold text-gray-800">Patient Consultation</h3>
            <p className="text-sm text-gray-600 mt-1">
              <span className="font-semibold text-gray-800">
                {patientDetails?.patient?.name || 'Unknown'}
              </span>
              <span className="mx-2">•</span>
              <span className="text-gray-500">
                ID:{' '}
                {patientDetails?.patient?.customId ||
                  patient?.customId ||
                  opd?.patient?.customId ||
                  patientDetails?.patient?.id ||
                  patient?.id ||
                  opd?.patient?.id ||
                  ''}
              </span>
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition"
          >
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* What was captured at OPD entry: the reason the patient came, and the vitals
                    (only the ones this hospital has switched on). The reason was being recorded
                    at OPD entry but never surfaced to the doctor — it shows here, beside the
                    vitals, so it's read before the consultation starts. */}
        {opd && (opd.problem || enabledVitals.length > 0) && (
          <div className="px-6 py-3 border-b border-gray-200 bg-gray-50/70 space-y-3">
            {opd.problem && (
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-gray-400 mb-1">
                  Reason for visit
                </p>
                <p className="text-sm font-semibold text-gray-900 whitespace-pre-wrap break-words">
                  {opd.problem}
                </p>
              </div>
            )}

            {enabledVitals.length > 0 && (
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-gray-400 mb-2">
                  Vitals at OPD entry
                </p>
                <div className="flex flex-wrap gap-x-6 gap-y-2">
                  {enabledVitals.map((v) => {
                    const value = v.isCustom
                      ? opdCustomVitals[v.key]
                      : BUILT_IN_OPD_FIELD[v.key] && opd[BUILT_IN_OPD_FIELD[v.key]];
                    const has = value !== null && value !== undefined && value !== '';
                    return (
                      <div key={v.key} className="min-w-[86px]">
                        <p className="text-[11px] text-gray-500">{v.label}</p>
                        <p className="text-sm font-semibold text-gray-900">
                          {has ? `${value}${v.unit ? ` ${v.unit}` : ''}` : '—'}
                        </p>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {/* 2-Column Layout */}
        <div className="flex flex-1 overflow-hidden">
          {/* LEFT PANEL - Patient Profile */}
          <div className="w-1/3 border-r border-gray-200 bg-gray-50 overflow-y-auto">
            {loadingDetails ? (
              <div className="p-6 space-y-4">
                <div className="animate-pulse">
                  <div className="h-4 bg-gray-300 rounded w-3/4 mb-2"></div>
                  <div className="h-4 bg-gray-300 rounded w-1/2"></div>
                </div>
              </div>
            ) : patientDetails ? (
              <div className="p-6 space-y-6">
                {/* Demographics */}
                <div>
                  <h4 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3 flex items-center">
                    Patient Information
                  </h4>
                  <div className="space-y-2 text-sm">
                    <div className="flex justify-between py-2 border-b border-gray-200">
                      <span className="text-gray-500">Age</span>
                      <span className="font-semibold text-gray-800">
                        {patientDetails.patient.age} years
                      </span>
                    </div>
                    <div className="flex justify-between py-2 border-b border-gray-200">
                      <span className="text-gray-500">Date of Birth</span>
                      <span className="font-semibold text-gray-800">
                        {patientDetails.patient.dateOfBirth
                          ? new Date(
                              patientDetails.patient.dateOfBirth + 'T00:00:00'
                            ).toLocaleDateString('en-IN', {
                              day: '2-digit',
                              month: 'short',
                              year: 'numeric',
                            })
                          : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between py-2 border-b border-gray-200">
                      <span className="text-gray-500">Gender</span>
                      <span className="font-semibold text-gray-800">
                        {patientDetails.patient.gender}
                      </span>
                    </div>
                    <div className="flex justify-between py-2 border-b border-gray-200">
                      <span className="text-gray-500">Phone</span>
                      <span className="font-semibold text-gray-800">
                        {patientDetails.patient.phone}
                      </span>
                    </div>
                    <div className="flex justify-between py-2 border-b border-gray-200">
                      <span className="text-gray-500">Status</span>
                      <span className="px-2 py-1 rounded text-xs font-semibold bg-gray-100 text-gray-800">
                        {patientDetails.patient.status}
                      </span>
                    </div>
                    {patientDetails.patient.address && (
                      <div className="pt-2">
                        <span className="text-gray-500 block mb-1">Address</span>
                        <span className="text-gray-800 text-xs">
                          {patientDetails.patient.address}
                        </span>
                      </div>
                    )}
                  </div>
                </div>

                {/* Medical History */}
                <div>
                  <h4 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3 flex items-center">
                    Medical History
                  </h4>
                  {patientDetails.medicalHistory && patientDetails.medicalHistory.length > 0 ? (
                    <div className="space-y-3">
                      {patientDetails.medicalHistory.map((record, index) => (
                        <div
                          key={index}
                          className="bg-white rounded-lg p-3 shadow-sm border border-gray-200"
                        >
                          <div className="text-xs text-gray-500 mb-1">
                            {new Date(record.date).toLocaleDateString('en-US', {
                              year: 'numeric',
                              month: 'short',
                              day: 'numeric',
                            })}
                          </div>
                          {record.symptoms && (
                            <div className="text-sm mb-1">
                              <span className="text-gray-500">Symptoms:</span>
                              <span className="text-gray-700 ml-1">{record.symptoms}</span>
                            </div>
                          )}
                          {record.diagnosis && (
                            <div className="text-sm font-semibold text-gray-800">
                              {record.diagnosis}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="text-center py-6 text-gray-400 text-sm">
                      <p>No previous consultations</p>
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div className="p-6 text-center text-gray-400">
                <p>Loading patient details...</p>
              </div>
            )}
          </div>

          {/* RIGHT PANEL - Clinical Notes & Prescription */}
          <div className="flex-1 flex flex-col overflow-hidden">
            {/* Tabs */}
            <div className="flex border-b border-gray-200 bg-white">
              <button
                type="button"
                className={`flex-1 py-4 text-sm font-semibold transition-colors ${activeTab === 'clinical' ? 'text-gray-900 border-b-2 border-gray-900 bg-gray-50' : 'text-gray-500 hover:bg-gray-50'}`}
                onClick={() => setActiveTab('clinical')}
              >
                Clinical Notes
              </button>
              <button
                type="button"
                className={`flex-1 py-4 text-sm font-semibold transition-colors ${activeTab === 'prescription' ? 'text-gray-900 border-b-2 border-gray-900 bg-gray-50' : 'text-gray-500 hover:bg-gray-50'}`}
                onClick={() => setActiveTab('prescription')}
              >
                Prescription ({formData.prescription.length})
              </button>
            </div>

            {/* Tab Content */}
            <div className="flex-1 overflow-y-auto p-6 bg-white">
              {activeTab === 'clinical' ? (
                <div className="space-y-4">
                  <CharCountInput
                    label="Symptoms"
                    textarea
                    rows={3}
                    value={formData.symptoms}
                    onChange={(e) => handleChange('symptoms', e.target.value)}
                    maxLength={500}
                    placeholder="Enter patient's symptoms..."
                  />

                  <div className="flex flex-wrap items-center gap-2 -mt-2">
                    {symptomPresets.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleInsertSymptomPreset(preset.text)}
                        className="inline-flex items-center px-3 py-1 text-xs font-medium bg-teal-50 text-teal-700 border border-teal-200 rounded-full hover:bg-teal-100 transition"
                      >
                        {preset.text}
                      </button>
                    ))}
                    {symptomPresets.length === 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManageSymptomPresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline"
                      >
                        Add your first symptom preset
                      </button>
                    )}
                    {symptomPresets.length > 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManageSymptomPresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline ml-1"
                      >
                        Manage
                      </button>
                    )}
                  </div>

                  <CharCountInput
                    label="Diagnosis"
                    textarea
                    rows={3}
                    value={formData.diagnosis}
                    onChange={(e) => handleChange('diagnosis', e.target.value)}
                    maxLength={500}
                    placeholder="Enter diagnosis..."
                  />

                  <div className="flex flex-wrap items-center gap-2 -mt-2">
                    {diagnosisPresets.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleInsertDiagnosisPreset(preset.text)}
                        className="inline-flex items-center px-3 py-1 text-xs font-medium bg-teal-50 text-teal-700 border border-teal-200 rounded-full hover:bg-teal-100 transition"
                      >
                        {preset.text}
                      </button>
                    ))}
                    {diagnosisPresets.length === 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManageDiagnosisPresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline"
                      >
                        Add your first diagnosis preset
                      </button>
                    )}
                    {diagnosisPresets.length > 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManageDiagnosisPresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline ml-1"
                      >
                        Manage
                      </button>
                    )}
                  </div>

                  {/* Hospital Inventory Items Used Section */}
                  {(hasBilling || hasHospitalInventory) && (
                    <div className="bg-slate-50 p-4 rounded-xl border border-gray-200 mt-4 space-y-3">
                      <div className="flex items-start justify-between">
                        <div>
                          <h4 className="text-sm font-bold text-gray-800">Items Used / Charges</h4>
                          <p className="text-xs text-gray-500 mt-0.5">
                            {hasHospitalInventory
                              ? 'Search hospital inventory items — linked fees are auto-applied to billing.'
                              : 'Applicable standard consultation charges.'}
                          </p>
                        </div>
                      </div>

                      {/* Standard fee summary (read-only) */}
                      {hasBilling && appliedCharges.length > 0 && (
                        <div className="flex flex-wrap gap-2">
                          {appliedCharges.map((c, i) => (
                            <span
                              key={i}
                              className="inline-flex items-center gap-1 px-2 py-1 text-xs font-semibold bg-blue-50 text-blue-700 border border-blue-100 rounded-full"
                            >
                              {c.description}: ₹{c.amount}
                            </span>
                          ))}
                        </div>
                      )}

                      {/* Hospital Inventory Search — chargeable catalog items only */}
                      {hasHospitalInventory && (
                        <div className="relative">
                          <input
                            type="text"
                            placeholder="Search services (nebulization, dressing...)..."
                            value={hospitalInvSearch}
                            onChange={(e) => {
                              setHospitalInvSearch(e.target.value);
                              setHospitalInvDropdown(true);
                            }}
                            onFocus={() => setHospitalInvDropdown(true)}
                            onBlur={() => setTimeout(() => setHospitalInvDropdown(false), 200)}
                            className="w-full border border-gray-300 px-3 py-2 text-sm rounded-lg focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                          />
                          {hospitalInvDropdown && hospitalInvSearch.trim().length >= 1 && (
                            <div className="absolute left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-xl z-50 max-h-48 overflow-y-auto divide-y divide-gray-100">
                              {hospitalInventoryCatalog
                                .filter((catItem) =>
                                  catItem.name
                                    ?.toLowerCase()
                                    .includes(hospitalInvSearch.toLowerCase())
                                )
                                .map((catItem) => {
                                  return (
                                    <button
                                      key={catItem.id}
                                      type="button"
                                      onMouseDown={() => addOrIncrementHospitalInvItem(catItem)}
                                      className="w-full text-left px-3 py-2 flex justify-between items-center text-xs hover:bg-slate-50"
                                    >
                                      <span className="font-semibold text-gray-800">
                                        {catItem.name}
                                      </span>
                                      <span className="text-gray-455 font-bold">
                                        ₹{catItem.charge?.toFixed(2)}
                                      </span>
                                    </button>
                                  );
                                })}
                              {hospitalInventoryCatalog.filter((catItem) =>
                                catItem.name
                                  ?.toLowerCase()
                                  .includes(hospitalInvSearch.toLowerCase())
                              ).length === 0 && (
                                <div className="p-2 text-center text-xs text-gray-400">
                                  No matching services found.
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      )}

                      {/* Selected Items List */}
                      {hasHospitalInventory && hospitalInvItems.length > 0 && (
                        <div className="border border-gray-200 rounded-lg overflow-hidden bg-white">
                          <table className="min-w-full text-xs">
                            <thead className="bg-slate-50 text-gray-500 font-medium border-b border-gray-200">
                              <tr>
                                <th className="px-3 py-2 text-left">Service</th>
                                <th className="px-3 py-2 text-center">Qty</th>
                                <th className="px-3 py-2 text-right">Charge</th>
                                <th className="px-3 py-2 text-right">Action</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-100">
                              {hospitalInvItems.map((item) => (
                                <tr key={item.serviceId} className="hover:bg-slate-50/50">
                                  <td className="px-3 py-2 font-semibold text-gray-800">
                                    {item.name}
                                  </td>
                                  <td className="px-3 py-2 text-center">
                                    <div className="inline-flex items-center gap-1">
                                      <button
                                        type="button"
                                        onClick={() => decrementHospitalInvItem(item)}
                                        className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100"
                                      >
                                        -
                                      </button>
                                      <span className="font-bold w-4 text-center">{item.qty}</span>
                                      <button
                                        type="button"
                                        onClick={() => incrementHospitalInvItem(item)}
                                        className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100"
                                      >
                                        +
                                      </button>
                                    </div>
                                  </td>
                                  <td className="px-3 py-2 text-right text-teal-700 font-semibold">
                                    ₹{(item.feeAmount * item.qty).toFixed(2)}
                                  </td>
                                  <td className="px-3 py-2 text-right">
                                    <button
                                      type="button"
                                      onClick={() => removeHospitalInvItem(item.serviceId)}
                                      className="text-red-500 hover:text-red-700 font-semibold"
                                    >
                                      Remove
                                    </button>
                                  </td>
                                </tr>
                              ))}
                              <tr className="bg-slate-50 font-semibold border-t border-gray-200">
                                <td colSpan={2} className="px-3 py-2 text-left text-gray-700">
                                  Total Added Charges
                                </td>
                                <td className="px-3 py-2 text-right text-teal-600 font-bold">
                                  ₹
                                  {hospitalInvItems
                                    .reduce((s, x) => s + (x.feeAmount || 0) * x.qty, 0)
                                    .toFixed(2)}
                                </td>
                                <td />
                              </tr>
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  )}

                  {hasMedicalInventory && user?.inClinic !== false && (
                    <div className="bg-slate-50 p-4 rounded-xl border border-gray-200 mt-4 space-y-4">
                      <div>
                        <h4 className="text-sm font-bold text-gray-800">Diagnosis medicines</h4>
                        <p className="text-xs text-gray-550 mt-0.5">
                          Administer clinical items and medicines in-clinic during consultation.
                        </p>
                      </div>

                      {/* In-Clinic presets — one click adds the whole bundle. The doctor can
                                                build them here too, exactly like prescription/note presets. */}
                      <div className="mb-3">
                        <div className="flex flex-wrap items-center gap-2 mb-1.5">
                          <p className="text-[11px] font-bold text-gray-400 uppercase tracking-wide">
                            Quick Add
                          </p>
                          <span className="flex-1" />
                          <button
                            type="button"
                            onClick={handleSaveCurrentAsInClinicPreset}
                            className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap"
                          >
                            Save current as preset
                          </button>
                          <button
                            type="button"
                            onClick={() => setShowManageInClinicPresets(true)}
                            className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap"
                          >
                            Manage Presets
                          </button>
                        </div>

                        {inClinicPresets.length > 0 ? (
                          <div className="flex flex-wrap gap-2">
                            {inClinicPresets.map((preset) => (
                              <button
                                key={preset.id}
                                type="button"
                                onClick={() => applyInClinicPreset(preset)}
                                title={(preset.items || [])
                                  .map((it) => {
                                    const detail = [it.dosage, it.frequency, it.duration]
                                      .filter(Boolean)
                                      .join(' · ');
                                    return `${it.medicineName} ×${it.quantity ?? 1}${detail ? ` — ${detail}` : ''}`;
                                  })
                                  .join('\n')}
                                className="px-3 py-1.5 text-xs font-semibold bg-teal-50 text-teal-700 border border-teal-200 rounded-full hover:bg-teal-100 transition-colors"
                              >
                                + {preset.name}
                              </button>
                            ))}
                          </div>
                        ) : (
                          <p className="text-xs text-gray-400">
                            No in-clinic presets yet — build one with “Manage Presets”.
                          </p>
                        )}
                      </div>

                      {/* Search and Auto-Complete Input */}
                      <div className="relative">
                        <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
                          <svg
                            className="w-5 h-5"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                            />
                          </svg>
                        </span>
                        <input
                          type="text"
                          placeholder="Search active clinical stock (saline, injection, etc. from medicine table)..."
                          value={searchQuery}
                          onChange={(e) => {
                            setSearchQuery(e.target.value);
                            setShowSuggestions(true);
                          }}
                          onFocus={() => setShowSuggestions(true)}
                          className="w-full border border-gray-300 pl-10 pr-4 py-2 text-sm rounded-lg focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none bg-white"
                        />

                        {/* Suggestions Dropdown */}
                        {showSuggestions && searchQuery.trim().length >= 1 && (
                          <div className="absolute left-0 right-0 mt-1 max-h-60 overflow-auto bg-white rounded-lg border border-gray-200 shadow-lg z-50 divide-y divide-gray-100">
                            {inventory
                              .filter(
                                (item) =>
                                  item &&
                                  item.name &&
                                  item.name.toLowerCase().includes(searchQuery.toLowerCase().trim())
                              )
                              .map((item) => (
                                <button
                                  key={item.id}
                                  type="button"
                                  onMouseDown={() => {
                                    const existing = administeredList.find(
                                      (x) => x.medicineId === item.id
                                    );
                                    if (existing) {
                                      if (existing.quantity < item.stockQuantity) {
                                        setAdministeredList((prev) =>
                                          prev.map((x) =>
                                            x.medicineId === item.id
                                              ? { ...x, quantity: x.quantity + 1 }
                                              : x
                                          )
                                        );
                                      } else {
                                        toastError(
                                          `Cannot add more. Only ${item.stockQuantity} units available in stock.`
                                        );
                                      }
                                    } else {
                                      setAdministeredList((prev) => [
                                        ...prev,
                                        {
                                          medicineId: item.id,
                                          medicineName: item.name,
                                          quantity: 1,
                                          maxStock: item.stockQuantity,
                                          dosage: item.defaultDosage || '',
                                          frequency: item.defaultFrequency || '',
                                          duration: item.defaultDuration || '',
                                          instructions: '',
                                        },
                                      ]);
                                    }
                                    setSearchQuery('');
                                    setShowSuggestions(false);
                                  }}
                                  className="w-full text-left px-4 py-2.5 hover:bg-slate-50 flex justify-between items-center text-sm"
                                >
                                  <div>
                                    <span className="font-semibold text-gray-800">{item.name}</span>
                                    <span className="ml-2 text-xs bg-slate-100 px-2 py-0.5 rounded-full text-gray-600">
                                      {item.type}
                                    </span>
                                  </div>
                                  <span
                                    className={`text-xs font-bold ${item.stockQuantity <= item.minStockLevel ? 'text-amber-600' : 'text-gray-500'}`}
                                  >
                                    Stock: {item.stockQuantity} available
                                  </span>
                                </button>
                              ))}
                            {inventory.filter(
                              (item) =>
                                item &&
                                item.name &&
                                item.name.toLowerCase().includes(searchQuery.toLowerCase().trim())
                            ).length === 0 && (
                              <div className="p-3 text-center text-xs text-gray-400">
                                No active stock item matches.
                              </div>
                            )}
                          </div>
                        )}
                      </div>

                      {/* Administered List Table */}
                      {administeredList.length > 0 && (
                        <div className="border border-gray-200 rounded-lg overflow-hidden bg-white">
                          <table className="min-w-full text-sm">
                            <thead className="bg-slate-50 text-gray-500 font-medium border-b border-gray-200">
                              <tr>
                                <th className="px-4 py-2.5 text-left">Medicine Name</th>
                                <th className="px-4 py-2.5 text-center">Qty</th>
                                <th className="px-4 py-2.5 text-right">Stock Status</th>
                                <th className="px-4 py-2.5 text-right">Actions</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-100">
                              {administeredList.map((item) => (
                                <tr key={item.medicineId} className="hover:bg-slate-50/50">
                                  <td className="px-4 py-3 text-left">
                                    <div className="font-semibold text-gray-800">
                                      {item.medicineName}
                                    </div>
                                    <div className="grid grid-cols-2 gap-1.5 mt-2">
                                      <input
                                        type="text"
                                        placeholder="Dosage"
                                        value={item.dosage || ''}
                                        onChange={(e) =>
                                          updateAdministeredField(
                                            item.medicineId,
                                            'dosage',
                                            e.target.value
                                          )
                                        }
                                        className="border border-gray-300 rounded px-1.5 py-0.5 text-xs outline-none focus:ring-1 focus:ring-teal-500 font-normal"
                                      />
                                      <input
                                        type="text"
                                        placeholder="Duration"
                                        value={item.duration || ''}
                                        onChange={(e) =>
                                          updateAdministeredField(
                                            item.medicineId,
                                            'duration',
                                            e.target.value
                                          )
                                        }
                                        className="border border-gray-300 rounded px-1.5 py-0.5 text-xs outline-none focus:ring-1 focus:ring-teal-500 font-normal"
                                      />
                                      <div className="col-span-2 flex items-center gap-2">
                                        <span className="text-xs text-gray-500">Freq</span>
                                        <FrequencyInput
                                          value={item.frequency || ''}
                                          onChange={(val) =>
                                            updateAdministeredField(
                                              item.medicineId,
                                              'frequency',
                                              val
                                            )
                                          }
                                        />
                                      </div>
                                      <input
                                        type="text"
                                        placeholder="Instruction"
                                        value={item.instructions || ''}
                                        onChange={(e) =>
                                          updateAdministeredField(
                                            item.medicineId,
                                            'instructions',
                                            e.target.value
                                          )
                                        }
                                        className="col-span-2 border border-gray-300 rounded px-1.5 py-0.5 text-xs outline-none focus:ring-1 focus:ring-teal-500 font-normal"
                                      />
                                    </div>
                                  </td>
                                  <td className="px-4 py-3 text-center">
                                    <div className="inline-flex items-center gap-2">
                                      <button
                                        type="button"
                                        onClick={() => {
                                          if (item.quantity > 1)
                                            updateAdministeredField(
                                              item.medicineId,
                                              'quantity',
                                              item.quantity - 1
                                            );
                                        }}
                                        disabled={item.quantity <= 1}
                                        className="w-6 h-6 flex items-center justify-center border border-gray-300 rounded-md text-gray-500 hover:bg-slate-100 disabled:opacity-50"
                                      >
                                        -
                                      </button>
                                      <span className="font-bold w-6 text-center">
                                        {item.quantity}
                                      </span>
                                      <button
                                        type="button"
                                        onClick={() => {
                                          if (item.quantity < item.maxStock)
                                            updateAdministeredField(
                                              item.medicineId,
                                              'quantity',
                                              item.quantity + 1
                                            );
                                          else
                                            toastError(
                                              `Cannot exceed available stock of ${item.maxStock} units.`
                                            );
                                        }}
                                        className="w-6 h-6 flex items-center justify-center border border-gray-300 rounded-md text-gray-500 hover:bg-slate-100"
                                      >
                                        +
                                      </button>
                                    </div>
                                  </td>
                                  <td className="px-4 py-3 text-right text-xs">
                                    <span className="text-gray-500">
                                      Max available: {item.maxStock}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3 text-right">
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setAdministeredList((prev) =>
                                          prev.filter((x) => x.medicineId !== item.medicineId)
                                        );
                                      }}
                                      className="text-red-500 hover:text-red-700 font-semibold text-xs"
                                    >
                                      Remove
                                    </button>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  )}

                  <CharCountInput
                    label="Treatment Notes"
                    textarea
                    rows={4}
                    value={formData.treatmentNotes}
                    onChange={(e) => handleChange('treatmentNotes', e.target.value)}
                    maxLength={500}
                    placeholder="Enter treatment plan and notes..."
                  />

                  <div className="flex flex-wrap items-center gap-2 -mt-2">
                    {notePresets.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleInsertPreset(preset.text)}
                        className="inline-flex items-center px-3 py-1 text-xs font-medium bg-teal-50 text-teal-700 border border-teal-200 rounded-full hover:bg-teal-100 transition"
                      >
                        {preset.text}
                      </button>
                    ))}
                    {notePresets.length === 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManagePresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline"
                      >
                        Add your first quick note
                      </button>
                    )}
                    {notePresets.length > 0 && (
                      <button
                        type="button"
                        onClick={() => setShowManagePresets(true)}
                        className="text-xs text-gray-500 hover:text-gray-700 underline ml-1"
                      >
                        Manage
                      </button>
                    )}
                  </div>

                  <div>
                    <div className="flex items-center gap-3">
                      <input
                        id="followup-checkbox"
                        type="checkbox"
                        checked={formData.followUpRequired}
                        onChange={(e) =>
                          setFormData((prev) => ({ ...prev, followUpRequired: e.target.checked }))
                        }
                        className="h-4 w-4"
                      />
                      <label
                        htmlFor="followup-checkbox"
                        className="text-sm font-medium text-gray-700"
                      >
                        Schedule Follow-up
                      </label>
                    </div>
                    {formData.followUpRequired && (
                      <div className="mt-3">
                        <label
                          htmlFor="fld-1"
                          className="block text-sm font-semibold text-gray-700 mb-2"
                        >
                          Follow-up Date
                        </label>
                        <DateSelect
                          value={formData.followUpDate}
                          onChange={(v) => handleChange('followUpDate', v)}
                        />
                      </div>
                    )}

                    <div className="mt-4">
                      <div className="flex items-center gap-3">
                        <input
                          id="lab-checkbox"
                          type="checkbox"
                          checked={formData.labRequired}
                          onChange={(e) =>
                            setFormData((prev) => ({ ...prev, labRequired: e.target.checked }))
                          }
                          className="h-4 w-4"
                        />
                        <label htmlFor="lab-checkbox" className="text-sm font-medium text-gray-700">
                          Order Lab Tests
                        </label>
                      </div>

                      {formData.labRequired && (
                        <div className="mt-3 grid grid-cols-2 gap-2">
                          {LAB_OPTIONS.map((opt) => (
                            <label key={opt} className="inline-flex items-center gap-2">
                              <input
                                type="checkbox"
                                checked={formData.labTests.includes(opt)}
                                onChange={(e) => {
                                  if (e.target.checked) {
                                    setFormData((prev) => ({
                                      ...prev,
                                      labTests: [...prev.labTests, opt],
                                    }));
                                  } else {
                                    setFormData((prev) => ({
                                      ...prev,
                                      labTests: prev.labTests.filter((t) => t !== opt),
                                    }));
                                  }
                                }}
                                className="h-4 w-4"
                              />
                              <span className="text-sm">{opt}</span>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              ) : (
                <div className="space-y-4">
                  {/* Prescription Presets */}
                  <div className="flex flex-wrap items-center gap-2 bg-slate-50 p-3 rounded-lg border border-gray-200">
                    <select
                      onChange={(e) => {
                        handleApplyPrescriptionPreset(e.target.value);
                        e.target.value = '';
                      }}
                      defaultValue=""
                      className="flex-1 min-w-[200px] border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                    >
                      <option value="" disabled>
                        Apply a prescription preset...
                      </option>
                      {prescriptionPresets.map((preset) => (
                        <option key={preset.id} value={preset.id}>
                          {preset.name} ({(preset.items || []).length} medicine
                          {(preset.items || []).length === 1 ? '' : 's'})
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={handleSaveCurrentAsPreset}
                      className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap"
                    >
                      Save current as preset
                    </button>
                    <button
                      type="button"
                      onClick={() => setShowManagePrescriptionPresets(true)}
                      className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap"
                    >
                      Manage Presets
                    </button>
                  </div>

                  {/* Prescription List */}
                  {formData.prescription.length > 0 && (
                    <div className="mb-6">
                      <h4 className="text-sm font-semibold text-gray-700 mb-3">
                        Prescribed Medicines ({formData.prescription.length})
                      </h4>
                      <div className="space-y-2">
                        {formData.prescription.map((med, index) => (
                          <div
                            key={index}
                            className={`flex items-center justify-between p-3 rounded-lg border ${editingMedicineIndex === index ? 'bg-primary-50 border-primary-300' : 'bg-gray-50 border-gray-200'}`}
                          >
                            <div className="flex-1">
                              <div className="font-semibold text-gray-800">{med.medicineName}</div>
                              <div className="text-xs text-gray-500 mt-1">
                                {med.dosage} • {med.frequency} • {med.duration}
                                {med.instructions && ` • ${med.instructions}`}
                              </div>
                            </div>
                            <button
                              type="button"
                              onClick={() => handleEditMedicine(index)}
                              className="ml-3 text-indigo-600 hover:text-indigo-800 text-xs font-semibold"
                            >
                              Edit
                            </button>
                            <button
                              type="button"
                              onClick={() => handleRemoveMedicine(index)}
                              className="ml-3 text-gray-500 hover:text-gray-700"
                            >
                              <svg
                                className="w-5 h-5"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M6 18L18 6M6 6l12 12"
                                />
                              </svg>
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="bg-gray-50 p-4 rounded-lg border border-gray-200">
                    <h4 className="text-sm font-semibold text-gray-700 mb-3">
                      {editingMedicineIndex !== null ? 'Edit Medicine' : 'Add Medicine'}
                    </h4>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="col-span-2">
                        <MedicineAutocomplete
                          value={newMedicine.medicineName}
                          onUnresolvedTextChange={setUnresolvedMedicineText}
                          onChange={(name) =>
                            setNewMedicine((prev) => ({ ...prev, medicineName: name }))
                          }
                          onSelect={(med) => {
                            setUnresolvedMedicineText('');
                            setNewMedicine((prev) => ({
                              ...prev,
                              medicineName: med.name,
                              dosage: med.defaultDosage || prev.dosage,
                              frequency: med.defaultFrequency || prev.frequency,
                              duration: med.defaultDuration || prev.duration,
                              // manufacturer: med.manufacturer // Optional if we want to show it
                            }));
                          }}
                        />
                        {unresolvedMedicineText.trim() && !newMedicine.medicineName && (
                          <p role="alert" className="mt-1 text-xs font-semibold text-amber-700">
                            Select a medicine from the suggestions — typing alone does not add it.
                          </p>
                        )}
                      </div>
                      <input
                        type="text"
                        placeholder="Dosage (e.g., 500mg)"
                        value={newMedicine.dosage}
                        onChange={(e) => setNewMedicine({ ...newMedicine, dosage: e.target.value })}
                        className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500"
                      />
                      <div className="flex items-center gap-2 border border-gray-300 rounded-lg px-3 py-2">
                        <span className="text-xs text-gray-500 shrink-0">Freq</span>
                        <FrequencyInput
                          value={newMedicine.frequency}
                          onChange={(val) => setNewMedicine({ ...newMedicine, frequency: val })}
                        />
                      </div>
                      <input
                        type="text"
                        placeholder="Duration (e.g., 5 Days)"
                        value={newMedicine.duration}
                        onChange={(e) =>
                          setNewMedicine({ ...newMedicine, duration: e.target.value })
                        }
                        className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500"
                      />
                      <input
                        type="text"
                        placeholder="Instructions (e.g., After food)"
                        value={newMedicine.instructions}
                        onChange={(e) =>
                          setNewMedicine({ ...newMedicine, instructions: e.target.value })
                        }
                        className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500"
                      />
                    </div>
                    <div className="mt-3 flex gap-2">
                      <button
                        type="button"
                        onClick={handleAddMedicine}
                        disabled={!newMedicine.medicineName}
                        className="flex-1 bg-primary-600 text-white py-2 rounded-lg hover:bg-primary-700 transition disabled:bg-gray-300 disabled:cursor-not-allowed text-sm font-semibold"
                      >
                        {editingMedicineIndex !== null ? 'Save Changes' : '+ Add Medicine'}
                      </button>
                      {editingMedicineIndex !== null && (
                        <button
                          type="button"
                          onClick={handleCancelEditMedicine}
                          className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800"
                        >
                          Cancel
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Footer Actions */}
            <div className="p-6 border-t border-gray-200 bg-gray-50 flex justify-end space-x-3">
              <button
                type="button"
                onClick={onClose}
                disabled={submitting}
                className={`px-6 py-2.5 border border-gray-300 text-gray-700 rounded-lg transition font-semibold ${submitting ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-100'}`}
              >
                Cancel
              </button>
              {(opd || appointment) && hasIPD && (
                <button
                  type="button"
                  onClick={handleAdmitToIpdClick}
                  disabled={submitting}
                  className={`px-6 py-2.5 bg-blue-600 text-white rounded-lg transition font-semibold shadow-md ${submitting ? 'opacity-50 cursor-not-allowed' : 'hover:bg-blue-700'}`}
                >
                  Admit to IPD
                </button>
              )}
              <button
                type="button"
                onClick={handleSubmit}
                disabled={submitting}
                className={`px-6 py-2.5 text-white rounded-lg transition font-semibold shadow-md flex items-center gap-2 ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-green-600 hover:bg-green-700'}`}
              >
                {submitting && (
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
                )}
                {submitting ? 'Submitting...' : 'Complete Consultation'}
              </button>
            </div>
          </div>
        </div>
      </div>
      {showIpdAdmitModal && (
        <IpdAdmitModal
          isOpen={showIpdAdmitModal}
          opd={admitModalOpd || opd}
          initialDiagnosis={
            formData.diagnosis || formData.symptoms || (admitModalOpd || opd)?.problem || ''
          }
          onClose={() => setShowIpdAdmitModal(false)}
          onSuccess={() => {
            setShowIpdAdmitModal(false);
            onSuccess('Patient admitted to IPD successfully!');
            onClose();
          }}
        />
      )}
      <ManageNotePresetsModal
        isOpen={showManagePresets}
        onClose={() => {
          setShowManagePresets(false);
          hospitalService
            .getConsultationNotePresets('TREATMENT_NOTES')
            .then((data) => setNotePresets(data || []))
            .catch(() => {});
        }}
        fieldType="TREATMENT_NOTES"
      />
      <ManageNotePresetsModal
        isOpen={showManageSymptomPresets}
        onClose={() => {
          setShowManageSymptomPresets(false);
          hospitalService
            .getConsultationNotePresets('SYMPTOMS')
            .then((data) => setSymptomPresets(data || []))
            .catch(() => {});
        }}
        fieldType="SYMPTOMS"
        title="Manage Symptom Presets"
        managerProps={{
          title: 'Symptom Presets',
          noun: 'symptom preset',
          placeholder: 'e.g. Fever x 3 days',
          description:
            'Common complaints that appear as one-click buttons under Symptoms during a consultation.',
        }}
      />
      <ManageNotePresetsModal
        isOpen={showManageDiagnosisPresets}
        onClose={() => {
          setShowManageDiagnosisPresets(false);
          hospitalService
            .getConsultationNotePresets('DIAGNOSIS')
            .then((data) => setDiagnosisPresets(data || []))
            .catch(() => {});
        }}
        fieldType="DIAGNOSIS"
        title="Manage Diagnosis Presets"
        managerProps={{
          title: 'Diagnosis Presets',
          noun: 'diagnosis preset',
          placeholder: 'e.g. Essential Hypertension',
          description:
            'Common diagnoses that appear as one-click buttons under Diagnosis during a consultation.',
        }}
      />
      <ManagePrescriptionPresetsModal
        isOpen={showManagePrescriptionPresets}
        onClose={() => {
          setShowManagePrescriptionPresets(false);
          hospitalService
            .getPrescriptionPresets()
            .then((data) => setPrescriptionPresets(data || []))
            .catch(() => {});
        }}
      />
      <ManageInClinicPresetsModal
        isOpen={showManageInClinicPresets}
        onClose={() => {
          setShowManageInClinicPresets(false);
          hospitalService
            .getInClinicPresets()
            .then((data) => setInClinicPresets(data || []))
            .catch(() => {});
        }}
      />
    </div>
  );
};

export default ConsultationModal;
