/**
 * Shared presentation constants for the ICU dashboard and bed board (ICU Phase 2).
 *
 * The bed states mirror BedStatus one-for-one — ICU renders the four states the system already
 * has and introduces none of its own. The palette matches WardBedsView's STATUS_META so a bed
 * reads the same colour on the nursing board and on the ICU board.
 */
export const BED_STATUS_META = {
  available: { label: 'Available', badge: 'bg-green-100 text-green-700', dot: 'bg-green-500' },
  occupied: { label: 'Occupied', badge: 'bg-blue-100 text-blue-700', dot: 'bg-blue-500' },
  cleaning: {
    label: 'Cleaning Required',
    badge: 'bg-amber-100 text-amber-700',
    dot: 'bg-amber-500',
  },
  maintenance: {
    label: 'Under Maintenance',
    badge: 'bg-gray-200 text-gray-600',
    dot: 'bg-gray-400',
  },
};

export const bedStatusMeta = (status) =>
  BED_STATUS_META[status] || {
    label: status || 'Unknown',
    badge: 'bg-gray-100 text-gray-600',
    dot: 'bg-gray-400',
  };

export const fmtDateTime = (v) => {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString('en-IN', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return String(v);
  }
};

/** "3h 20m ago", for how long a patient has been in the unit. */
export const since = (v) => {
  if (!v) return '—';
  const then = new Date(v).getTime();
  if (Number.isNaN(then)) return '—';
  const mins = Math.max(0, Math.floor((Date.now() - then) / 60000));
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ${mins % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
};

/**
 * The recorded respiratory observation for a bed, or null when nothing has been recorded.
 * Values only — the board never derives a severity or risk judgement from them.
 */
export const respiratorySummary = (bed) => {
  if (bed?.latestSpo2 == null && bed?.latestRespiratoryRate == null) return null;
  const parts = [];
  if (bed.latestSpo2 != null) parts.push(`SpO₂ ${bed.latestSpo2}%`);
  if (bed.latestRespiratoryRate != null) parts.push(`RR ${bed.latestRespiratoryRate}`);
  return parts.join(' · ');
};
