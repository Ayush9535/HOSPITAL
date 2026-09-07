import React from 'react';
import DateSelect from './DateSelect';

/**
 * DobPicker — Day / Month / Year dropdowns for date of birth.
 *
 * Thin wrapper over the shared DateSelect: date of birth is always in the past, so it offers
 * the last 120 years (newest first) and no future years. Value contract is a "YYYY-MM-DD" string
 * (or "" while incomplete), unchanged, so existing age calculation and validation keep working.
 */
const DobPicker = (props) => <DateSelect {...props} yearsBack={120} yearsAhead={0} />;

export default DobPicker;
