import { render, screen } from '@testing-library/react';
import StatusBadge, { classifyStatus, resolveStatusLabel } from './StatusBadge';
import { describe, expect, it } from 'vitest';

describe('StatusBadge', () => {
  it('renders known status with semantic class', () => {
    render(<StatusBadge status="healthy">Healthy</StatusBadge>);
    const badge = screen.getByText('Healthy');
    expect(badge).toBeTruthy();
    expect(badge.className).toContain('status-badge');
    expect(badge.className).toContain('success');
  });

  it('defaults unknown status to a neutral badge and normalized label', () => {
    render(<StatusBadge status="retrying-soon">Retrying Soon</StatusBadge>);
    const badge = screen.getByText('Retrying Soon');
    expect(badge.className).toContain('neutral');
  });

  it('exposes status helpers with deterministic defaults', () => {
    expect(classifyStatus('error')).toBe('error');
    expect(resolveStatusLabel('disabled')).toBe('Disabled');
  });
});
