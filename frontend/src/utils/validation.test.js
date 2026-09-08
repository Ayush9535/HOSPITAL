import { describe, it, expect } from 'vitest';
import { validators, validate, validateForm } from './validation';

describe('validators.required', () => {
  it('rejects null, undefined, and blank strings', () => {
    expect(validators.required(null)).toBe('This field is required');
    expect(validators.required(undefined)).toBe('This field is required');
    expect(validators.required('   ')).toBe('This field is required');
  });
  it('accepts non-empty values', () => {
    expect(validators.required('x')).toBeNull();
    expect(validators.required(0)).toBeNull();
  });
});

describe('validators.email', () => {
  it('allows empty (chained with required)', () => {
    expect(validators.email('')).toBeNull();
  });
  it('accepts a valid address and rejects an invalid one', () => {
    expect(validators.email('a@b.com')).toBeNull();
    expect(validators.email('not-an-email')).toBe('Invalid email address');
  });
});

describe('validators.phone', () => {
  it('requires exactly 10 digits', () => {
    expect(validators.phone('1234567890')).toBeNull();
    expect(validators.phone('123')).toBe('Phone number must be exactly 10 digits');
    expect(validators.phone('')).toBeNull();
  });
});

describe('validators.password', () => {
  it('requires at least 6 characters', () => {
    expect(validators.password('123456')).toBeNull();
    expect(validators.password('123')).toBe('Password must be at least 6 characters');
    expect(validators.password('')).toBeNull();
  });
});

describe('validators.age', () => {
  it('rejects non-numbers and out-of-range values', () => {
    expect(validators.age('abc')).toBe('Age must be a number');
    expect(validators.age('130')).toBe('Age must be between 0 and 120');
    expect(validators.age('30')).toBeNull();
    expect(validators.age('')).toBeNull();
  });
});

describe('validators.dob', () => {
  it('accepts a reasonable past date', () => {
    expect(validators.dob('1990-01-01')).toBeNull();
  });
  it('rejects invalid, future, and too-old dates', () => {
    expect(validators.dob('not-a-date')).toBe('Invalid date of birth');
    expect(validators.dob('3000-01-01')).toBe('Date of birth cannot be in the future');
    expect(validators.dob('1800-01-01')).toBe('Date of birth cannot be more than 120 years ago');
    expect(validators.dob('')).toBeNull();
  });
});

describe('validators.number & positiveNumber', () => {
  it('validates numeric strings', () => {
    expect(validators.number('42')).toBeNull();
    expect(validators.number('abc')).toBe('Must be a valid number');
    expect(validators.positiveNumber('5')).toBeNull();
    expect(validators.positiveNumber('-1')).toBe('Must be a positive number');
    expect(validators.positiveNumber('')).toBeNull();
  });
});

describe('validators.name & text', () => {
  it('name allows letters/spaces, rejects digits and too-short', () => {
    expect(validators.name('John Doe')).toBeNull();
    expect(validators.name('John3')).toBe('Name must contain only letters and spaces');
    expect(validators.name('J')).toBe('Name must be at least 2 characters long');
    expect(validators.name('')).toBeNull();
  });
  it('text allows common punctuation, rejects symbols', () => {
    expect(validators.text('A-1.b')).toBeNull();
    expect(validators.text('bad@char')).toBe('Field contains invalid characters');
    expect(validators.text('')).toBeNull();
  });
});

describe('validators.minLength / maxLength', () => {
  it('enforce bounds', () => {
    expect(validators.minLength(3)('ab')).toBe('Minimum 3 characters');
    expect(validators.minLength(3)('abc')).toBeNull();
    expect(validators.maxLength(3)('abcd')).toBe('Maximum 3 characters');
    expect(validators.maxLength(3)('abc')).toBeNull();
  });
});

describe('validateForm / validate', () => {
  it('collects the first error per field and supports string + function rules', () => {
    const errors = validateForm(
      { email: 'bad', name: 'Jo' },
      { email: ['required', 'email'], name: [(v) => (v === 'Jo' ? 'nope' : null)] }
    );
    expect(errors.email).toBe('Invalid email address');
    expect(errors.name).toBe('nope');
  });
  it('returns no errors for valid data and validate() aliases validateForm', () => {
    expect(validate({ email: 'a@b.com' }, { email: ['email'] })).toEqual({});
  });
});
