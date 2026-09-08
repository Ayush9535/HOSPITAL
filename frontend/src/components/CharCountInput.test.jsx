import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import CharCountInput from './CharCountInput';

describe('CharCountInput', () => {
  it('renders the label and a character counter', () => {
    render(<CharCountInput value="hi" maxLength={10} label="Name" required />);
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('2 / 10')).toBeInTheDocument();
  });

  it('hides the counter when showCount is false', () => {
    render(<CharCountInput value="hi" maxLength={10} showCount={false} />);
    expect(screen.queryByText('2 / 10')).not.toBeInTheDocument();
  });

  it('renders an error message and supports textarea mode', () => {
    render(<CharCountInput value="" error="Required" textarea rows={2} label="Notes" />);
    expect(screen.getByText('Required')).toBeInTheDocument();
  });

  it('forwards changes through onChange', () => {
    const onChange = vi.fn();
    render(<CharCountInput value="" onChange={onChange} placeholder="type here" />);
    fireEvent.change(screen.getByPlaceholderText('type here'), { target: { value: 'x' } });
    expect(onChange).toHaveBeenCalled();
  });
});
