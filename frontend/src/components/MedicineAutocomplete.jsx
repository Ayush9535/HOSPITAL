import { Combobox } from '@headlessui/react';
import { safeLoadMessage } from '../utils/apiError';
import { CheckIcon, ChevronUpDownIcon } from '@heroicons/react/20/solid';
import React, { useState, useEffect } from 'react';
import hospitalService from '../services/hospitalService';

/**
 * @param {(text: string) => void} [onUnresolvedTextChange] told what the prescriber has typed
 *   while no catalogue medicine is selected. Typing here does not name a medicine -- only
 *   picking one from the list does -- so a caller that can submit needs to know the difference
 *   between an empty field and a half-finished one.
 */
export default function MedicineAutocomplete({
  value,
  onChange,
  onSelect,
  onUnresolvedTextChange,
}) {
  const [query, setQuery] = useState('');
  const [selectedMedicine, setSelectedMedicine] = useState(null);
  const [medicines, setMedicines] = useState([]);
  const [loading, setLoading] = useState(false);
  // "No medicines found" and "the lookup failed" must not look the same here: the first tells a
  // prescriber the drug is not in the catalogue, and acting on that when the search merely broke
  // is a clinical decision made on a false premise.
  const [searchError, setSearchError] = useState(null);

  useEffect(() => {
    const timeoutId = setTimeout(async () => {
      if (query.length >= 2) {
        setLoading(true);
        try {
          const results = await hospitalService.searchMedicines(query);
          setMedicines(results);
          setSearchError(null);
        } catch (error) {
          console.error('Failed to search medicines', error);
          setMedicines([]);
          setSearchError(safeLoadMessage(error, "Couldn't search the medicine catalogue."));
        } finally {
          setLoading(false);
        }
      } else {
        setMedicines([]);
      }
    }, 300);

    return () => clearTimeout(timeoutId);
  }, [query]);

  // Sync external value changes to selectedMedicine
  useEffect(() => {
    const currentName = selectedMedicine ? selectedMedicine.name : '';
    if (currentName !== value) {
      if (value) {
        setSelectedMedicine({ name: value });
      } else {
        setSelectedMedicine(null);
        setQuery('');
      }
    }
  }, [value, selectedMedicine]);

  return (
    <Combobox
      value={selectedMedicine}
      onChange={(medicine) => {
        setSelectedMedicine(medicine);
        // Only a real pick resolves the field. This fires with null on blur too, which is the
        // combobox tidying up rather than the prescriber choosing anything.
        if (medicine && onUnresolvedTextChange) onUnresolvedTextChange('');
        if (medicine) {
          onChange(medicine.name);
          if (onSelect) onSelect(medicine);
        } else {
          onChange('');
        }
      }}
      nullable
    >
      <div className="relative mt-1">
        <div className="relative w-full cursor-default overflow-hidden rounded-lg bg-white text-left shadow-md focus:outline-none focus-visible:ring-2 focus-visible:ring-white/75 focus-visible:ring-offset-2 focus-visible:ring-offset-teal-300 sm:text-sm border border-gray-300">
          <Combobox.Input
            className="w-full border-none py-2 pl-3 pr-10 text-sm leading-5 text-gray-900 focus:ring-0"
            displayValue={(medicine) => medicine?.name || ''}
            onChange={(event) => {
              const typed = event.target.value;
              setQuery(typed);
              if (!typed) {
                setSelectedMedicine(null);
                onChange('');
              } else if (selectedMedicine) {
                // User is retyping after a selection — clear the previous pick
                setSelectedMedicine(null);
                onChange('');
              }
              // Unresolved until something is picked from the list below.
              //
              // An empty value arrives here two ways: the prescriber cleared the field, or the
              // field tidied itself up on blur. Only the first is a decision. Losing focus wipes
              // the text on screen, so treating that as "nothing was typed" is what let a typed
              // medicine vanish between the field and the Complete button.
              if (onUnresolvedTextChange && (typed || document.activeElement === event.target)) {
                onUnresolvedTextChange(typed);
              }
            }}
            onFocus={(event) => {
              // Coming back to an empty field is the way out of the warning above.
              if (onUnresolvedTextChange && !event.target.value) onUnresolvedTextChange('');
            }}
            placeholder="Search medicine from catalog..."
          />
          <Combobox.Button className="absolute inset-y-0 right-0 flex items-center pr-2">
            <ChevronUpDownIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
          </Combobox.Button>
        </div>
        <Combobox.Options className="absolute mt-1 max-h-60 w-full overflow-auto rounded-md bg-white py-1 text-base shadow-lg ring-1 ring-black/5 focus:outline-none sm:text-sm z-50">
          {searchError ? (
            <div className="px-4 py-3 text-sm" role="alert">
              <p className="font-bold text-gray-900">Catalogue search failed</p>
              <p className="text-gray-600">{searchError}</p>
              <p className="mt-1 text-xs text-gray-500">
                This does not mean the medicine is missing. Try again before adding it.
              </p>
            </div>
          ) : loading ? (
            <div className="relative cursor-default select-none px-4 py-2 text-gray-500 text-sm">
              Searching...
            </div>
          ) : medicines.length === 0 && query.length >= 2 ? (
            <div className="relative cursor-default select-none px-4 py-2 text-gray-700 text-sm">
              No medicines found in catalog. Add to catalog first.
            </div>
          ) : (
            medicines.map((medicine) => (
              <Combobox.Option
                key={medicine.id}
                className={({ active }) =>
                  `relative cursor-default select-none py-2 pl-10 pr-4 ${active ? 'bg-primary-600 text-white' : 'text-gray-900'}`
                }
                value={medicine}
              >
                {({ selected, active }) => (
                  <>
                    <div className="flex justify-between">
                      <span
                        className={`block truncate ${selected ? 'font-medium' : 'font-normal'}`}
                      >
                        {medicine.name}
                      </span>
                      <span className={`text-xs ${active ? 'text-blue-200' : 'text-gray-500'}`}>
                        {medicine.type}
                        {medicine.defaultDosage ? ` • ${medicine.defaultDosage}` : ''}
                      </span>
                    </div>
                    {selected && (
                      <span
                        className={`absolute inset-y-0 left-0 flex items-center pl-3 ${active ? 'text-white' : 'text-primary-600'}`}
                      >
                        <CheckIcon className="h-5 w-5" aria-hidden="true" />
                      </span>
                    )}
                  </>
                )}
              </Combobox.Option>
            ))
          )}
        </Combobox.Options>
      </div>
    </Combobox>
  );
}
