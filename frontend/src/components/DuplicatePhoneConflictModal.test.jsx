import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import DuplicatePhoneConflictModal from './DuplicatePhoneConflictModal';

/**
 * The chooser that stands between a typed phone number and a second patient record.
 *
 * A parent and a child legitimately share one mobile, so this component never decides anything:
 * it lists every patient already on the number and makes both outcomes an explicit click.
 */
const parent = { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 };
const child = { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 };

const renderModal = (props = {}) =>
  render(
    <DuplicatePhoneConflictModal
      isOpen
      conflicts={[parent, child]}
      onUseExisting={vi.fn()}
      onRegisterDifferent={vi.fn()}
      onCancel={vi.fn()}
      {...props}
    />
  );

describe('DuplicatePhoneConflictModal', () => {
  it('renders nothing until there is something to choose between', () => {
    const { container } = render(
      <DuplicatePhoneConflictModal isOpen={false} conflicts={[parent]} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('lists every family member on the number, each with its own choice', async () => {
    renderModal();

    expect(screen.getByText('Mobile number already registered')).toBeInTheDocument();
    expect(screen.getByText('Rahul Patil')).toBeInTheDocument();
    expect(screen.getByText('Age 38')).toBeInTheDocument();
    expect(screen.getByText('Patient ID PAT101')).toBeInTheDocument();
    expect(screen.getByText('Aarav Patil')).toBeInTheDocument();
    expect(screen.getByText('Age 8')).toBeInTheDocument();
    expect(screen.getByText('Patient ID PAT145')).toBeInTheDocument();

    // One button per patient, so nobody has to be picked out of a single ambiguous control.
    expect(screen.getAllByRole('button', { name: 'Use This Patient' })).toHaveLength(2);
  });

  /**
   * The point of listing them separately: the child can be chosen without the parent, which is
   * exactly what the old "first patient with this number" behaviour could not do.
   */
  it('selects the exact patient whose button was pressed', async () => {
    const onUseExisting = vi.fn();
    const user = userEvent.setup();
    renderModal({ onUseExisting });

    const childRow = screen.getByText('Aarav Patil').closest('div.flex');
    await user.click(within(childRow).getByRole('button', { name: 'Use This Patient' }));

    expect(onUseExisting).toHaveBeenCalledTimes(1);
    expect(onUseExisting).toHaveBeenCalledWith(child);
  });

  it('selects the parent when the parent row is pressed', async () => {
    const onUseExisting = vi.fn();
    const user = userEvent.setup();
    renderModal({ onUseExisting });

    const parentRow = screen.getByText('Rahul Patil').closest('div.flex');
    await user.click(within(parentRow).getByRole('button', { name: 'Use This Patient' }));

    expect(onUseExisting).toHaveBeenCalledWith(parent);
  });

  it('registering a different person is its own deliberate action', async () => {
    const onRegisterDifferent = vi.fn();
    const onUseExisting = vi.fn();
    const user = userEvent.setup();
    renderModal({ onRegisterDifferent, onUseExisting });

    await user.click(screen.getByRole('button', { name: 'Register Different Patient' }));

    expect(onRegisterDifferent).toHaveBeenCalledTimes(1);
    expect(onUseExisting).not.toHaveBeenCalled();
  });

  it('cancelling chooses nothing at all', async () => {
    const onCancel = vi.fn();
    const onUseExisting = vi.fn();
    const onRegisterDifferent = vi.fn();
    const user = userEvent.setup();
    renderModal({ onCancel, onUseExisting, onRegisterDifferent });

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onUseExisting).not.toHaveBeenCalled();
    expect(onRegisterDifferent).not.toHaveBeenCalled();
  });

  it('every control is inert while a choice is in flight', () => {
    renderModal({ busy: true });

    screen.getAllByRole('button', { name: 'Use This Patient' }).forEach((b) => {
      expect(b).toBeDisabled();
    });
    expect(screen.getByRole('button', { name: /Register Different Patient/ })).toBeDisabled();
  });

  /** A missing age is shown as missing rather than as "Age undefined" or a silent blank. */
  it('says so when an age was never recorded', () => {
    renderModal({ conflicts: [{ ...parent, age: null }] });
    expect(screen.getByText('Age not recorded')).toBeInTheDocument();
  });

  /** The body is shown to staff, so it carries identity and nothing more. */
  it('shows no phone, date of birth, address or email', () => {
    const { container } = renderModal({
      conflicts: [{ ...parent, phone: '9876500001', dateOfBirth: '1987-04-02' }],
    });
    expect(container.textContent).not.toContain('9876500001');
    expect(container.textContent).not.toContain('1987-04-02');
  });
});
