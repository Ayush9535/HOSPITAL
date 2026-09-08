import { describe, expect, it } from 'vitest';
import { normalizePlanModules } from './PlansTab';

describe('normalizePlanModules', () => {
  it('adds OPD when APPOINTMENTS is selected', () => {
    expect(normalizePlanModules(['APPOINTMENTS'])).toEqual(['APPOINTMENTS', 'OPD']);
  });

  it('keeps OPD independent when appointments are not selected', () => {
    expect(normalizePlanModules(['OPD', 'BILLING'])).toEqual(['OPD', 'BILLING']);
  });
});
