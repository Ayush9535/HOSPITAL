import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import StatusBadge from './StatusBadge';

describe('StatusBadge', () => {
  it('renders a non-interactive badge with the status label', () => {
    render(<StatusBadge status="PAID" />);
    expect(screen.getByText('PAID')).toBeInTheDocument();
  });

  it('falls back to a default style for an unknown status', () => {
    render(<StatusBadge status="WEIRD_STATUS" />);
    expect(screen.getByText('WEIRD_STATUS')).toBeInTheDocument();
  });

  it('opens the dropdown and reports the chosen option when interactive', () => {
    const onUpdate = vi.fn();
    render(<StatusBadge status="PENDING" options={['PAID', 'UNPAID']} onUpdate={onUpdate} />);

    fireEvent.click(screen.getByText('PENDING'));
    fireEvent.click(screen.getByText('PAID'));
    expect(onUpdate).toHaveBeenCalledWith('PAID');
  });
});
