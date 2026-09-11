import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import WardCard from './WardCard';

const actions = { onViewBeds: vi.fn(), onEdit: vi.fn() };

describe('WardCard staffing state', () => {
  it('renders the staffed state supplied by the ward API', () => {
    render(<WardCard ward={{ wardName: 'A', totalBeds: 2, bedPrice: 0, staffed: true }} {...actions} />);
    expect(screen.getByText('STAFFED')).toBeInTheDocument();
  });

  it('makes absent incharge staffing visible rather than implying coverage', () => {
    render(<WardCard ward={{ wardName: 'A', totalBeds: 2, bedPrice: 0, staffed: false }} {...actions} />);
    expect(screen.getByText('UNSTAFFED — No Nurse Incharge assigned')).toBeInTheDocument();
  });
});
