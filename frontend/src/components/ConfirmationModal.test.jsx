import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ConfirmationModal from './ConfirmationModal';

describe('ConfirmationModal', () => {
  it('keeps the dialog open and shows a rejected mutation error', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    const onConfirm = vi.fn().mockRejectedValue({ response: { data: { error: 'Ward not empty' } } });

    render(
      <ConfirmationModal
        isOpen
        title="Delete ward"
        message="Delete this ward?"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Confirm action' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Ward not empty');
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
