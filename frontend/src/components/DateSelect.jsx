import React, { useState, useEffect } from 'react';

/**
 * DateSelect — Day / Month / Year dropdowns, the same control patient registration uses for
 * date of birth, generalised for any date. Replaces the native <input type="date"> so picking a
 * year/month is instant (plain <select>s) instead of paging the native calendar.
 *
 * Value contract matches the native date input: reads and emits a "YYYY-MM-DD" string (or "" while
 * incomplete), so existing state, validation and formatting keep working unchanged.
 *
 * Year range is configurable: `yearsBack`/`yearsAhead` frame the window around the current year
 * (newest first). Defaults suit general dates (appointments, expiry, scheduling); date-of-birth
 * uses yearsAhead=0 via DobPicker.
 */
const MONTHS = [
  ['01', 'Jan'],
  ['02', 'Feb'],
  ['03', 'Mar'],
  ['04', 'Apr'],
  ['05', 'May'],
  ['06', 'Jun'],
  ['07', 'Jul'],
  ['08', 'Aug'],
  ['09', 'Sep'],
  ['10', 'Oct'],
  ['11', 'Nov'],
  ['12', 'Dec'],
];

const pad2 = (n) => String(n).padStart(2, '0');

const daysInMonth = (year, month) => {
  const y = year ? Number(year) : 2000; // blank year -> leap year so 29 stays selectable
  const m = month ? Number(month) : 1;
  return new Date(y, m, 0).getDate();
};

const splitParts = (value) => {
  const [y = '', m = '', d = ''] = (value || '').split('-');
  return { y, m, d };
};

const DateSelect = ({
  value,
  onChange,
  hasError = false,
  disabled = false,
  yearsBack = 100,
  yearsAhead = 15,
  min, // optional "YYYY-MM-DD" floor: no earlier date can be picked (e.g. appointments = today onward)
  compact = false, // slim padding so the three dropdowns show their values inside narrow table cells
}) => {
  // Internal state so partial selections (e.g. Year picked, Month/Day not yet) persist.
  const [parts, setParts] = useState(() => splitParts(value));

  // Adopt an externally-driven value change (edit mode / form reset) without clobbering an
  // in-progress partial selection while the parent simply holds "" for "incomplete".
  useEffect(() => {
    setParts((prev) => {
      const prevComplete = prev.y && prev.m && prev.d ? `${prev.y}-${prev.m}-${prev.d}` : '';
      if (prevComplete === (value || '')) return prev; // already in sync
      if (!value && !prevComplete && (prev.y || prev.m || prev.d)) return prev; // keep partial
      return splitParts(value);
    });
  }, [value]);

  const currentYear = new Date().getFullYear();
  const minParts = min ? splitParts(min) : null;
  const lowYear = minParts ? Number(minParts.y) : currentYear - yearsBack;
  const highYear = currentYear + yearsAhead;
  const years = [];
  if (minParts) {
    // Floored picker (e.g. appointments): nearest allowed year first.
    for (let yr = lowYear; yr <= highYear; yr++) years.push(yr);
  } else {
    for (let yr = highYear; yr >= lowYear; yr--) years.push(yr);
  }

  // When a min is set, hide months/days earlier than the floor within the floor's own year/month.
  const monthOptions = MONTHS.filter(([mv]) => {
    if (!minParts || !parts.y) return true;
    if (Number(parts.y) > Number(minParts.y)) return true;
    return Number(mv) >= Number(minParts.m);
  });

  const maxDay = daysInMonth(parts.y, parts.m);
  const days = [];
  for (let i = 1; i <= maxDay; i++) {
    const dd = pad2(i);
    if (
      minParts &&
      parts.y &&
      parts.m &&
      Number(parts.y) === Number(minParts.y) &&
      Number(parts.m) === Number(minParts.m) &&
      Number(dd) < Number(minParts.d)
    ) {
      continue;
    }
    days.push(dd);
  }

  const setPart = (key, val) => {
    const next = { ...parts, [key]: val };
    if (next.d) {
      const dim = daysInMonth(next.y, next.m);
      if (Number(next.d) > dim) next.d = pad2(dim); // keep day within the (year, month)
    }
    setParts(next);
    onChange(next.y && next.m && next.d ? `${next.y}-${next.m}-${next.d}` : '');
  };

  const selCls = compact
    ? `w-full px-0.5 py-1.5 text-xs text-center border rounded-lg bg-white text-gray-800 cursor-pointer appearance-none focus:outline-none focus:ring-1 focus:ring-gray-400 ${
        hasError ? 'border-error-300' : 'border-gray-300'
      }`
    : `input-field cursor-pointer ${hasError ? 'border-error-300 focus:ring-error-500' : ''}`;

  return (
    <div className={`grid grid-cols-3 gap-1 ${compact ? 'min-w-[250px]' : ''}`}>
      <select
        aria-label="Day"
        value={parts.d}
        disabled={disabled}
        onChange={(e) => setPart('d', e.target.value)}
        className={selCls}
      >
        <option value="">Day</option>
        {days.map((dd) => (
          <option key={dd} value={dd}>
            {Number(dd)}
          </option>
        ))}
      </select>
      <select
        aria-label="Month"
        value={parts.m}
        disabled={disabled}
        onChange={(e) => setPart('m', e.target.value)}
        className={selCls}
      >
        <option value="">Month</option>
        {monthOptions.map(([mv, ml]) => (
          <option key={mv} value={mv}>
            {ml}
          </option>
        ))}
      </select>
      <select
        aria-label="Year"
        value={parts.y}
        disabled={disabled}
        onChange={(e) => setPart('y', e.target.value)}
        className={selCls}
      >
        <option value="">Year</option>
        {years.map((yr) => (
          <option key={yr} value={String(yr)}>
            {yr}
          </option>
        ))}
      </select>
    </div>
  );
};

export default DateSelect;
