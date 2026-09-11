import React, { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import FrequencyInput from './FrequencyInput';

const Harness = ({ initialValue = '' }) => {
  const [value, setValue] = useState(initialValue);
  return (
    <>
      <FrequencyInput value={value} onChange={setValue} />
      <output data-testid="frequency-value">{value}</output>
    </>
  );
};

describe('FrequencyInput', () => {
  it('replaces a clicked dose digit predictably without changing the frequency format', async () => {
    const user = userEvent.setup();
    render(<Harness initialValue="1-0-1" />);

    const morning = screen.getByRole('textbox', { name: 'Morning dose' });
    await user.click(morning);
    await user.keyboard('2');

    expect(morning).toHaveValue('2');
    expect(screen.getByTestId('frequency-value')).toHaveTextContent('2-0-1');
  });

  it('does not turn a pasted multi-digit value into an arbitrary last digit', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    const night = screen.getByRole('textbox', { name: 'Night dose' });
    await user.click(night);
    await user.paste('12');

    expect(night).toHaveValue('1');
    expect(screen.getByTestId('frequency-value')).toHaveTextContent('0-0-1');
  });
});
