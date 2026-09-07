import React, { useEffect, useState } from 'react';
import otService from '../../../services/otService';

/**
 * OtAnalyticsStrip - the four numbers an owner asks for, above the OT board.
 *
 * Every figure is a server query over the transition table; nothing is computed here.
 * Utilisation is shown as booked theatre-hours (an honest estimate until the occupancy
 * timeline exists) rather than a fabricated percentage.
 */
const Tile = ({ label, value, tone = 'gray' }) => {
  const tones = {
    gray: 'text-gray-900',
    green: 'text-green-700',
    amber: 'text-amber-700',
    red: 'text-red-600',
  };
  return (
    <div className="flex-1 min-w-[120px] bg-white border border-gray-200 rounded-xl px-4 py-3">
      <div className="text-[11px] uppercase tracking-wide text-gray-400">{label}</div>
      <div className={`text-2xl font-bold ${tones[tone]}`}>{value}</div>
    </div>
  );
};

const OtAnalyticsStrip = () => {
  const [s, setS] = useState(null);

  useEffect(() => {
    let active = true;
    otService
      .getOtAnalytics()
      .then((d) => {
        if (active) setS(d);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  if (!s) return null;

  const hours = s.bookedTheatreMinutes ? (s.bookedTheatreMinutes / 60).toFixed(1) : '0';

  return (
    <div className="flex flex-wrap gap-3 mb-4">
      <Tile label="Scheduled today" value={s.scheduledToday ?? 0} />
      <Tile label="In progress" value={s.inProgress ?? 0} tone="green" />
      <Tile label="Completed" value={s.completedToday ?? 0} />
      <Tile
        label="Cancelled"
        value={s.cancelledToday ?? 0}
        tone={s.cancelledToday ? 'red' : 'gray'}
      />
      <Tile label="Booked theatre-hrs" value={hours} tone="amber" />
    </div>
  );
};

export default OtAnalyticsStrip;
