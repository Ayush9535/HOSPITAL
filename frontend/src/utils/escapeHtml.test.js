import { describe, it, expect } from 'vitest';
import escapeHtml from './escapeHtml';

describe('escapeHtml', () => {
  it('returns an empty string for null/undefined', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });

  it('escapes all five HTML metacharacters', () => {
    expect(escapeHtml('&<>"\'')).toBe('&amp;&lt;&gt;&quot;&#39;');
  });

  it('escapes & first so existing entities are not double-mangled', () => {
    expect(escapeHtml('a & b < c')).toBe('a &amp; b &lt; c');
  });

  it('is safe inside a double-quoted attribute (no quote breakout)', () => {
    const out = escapeHtml('" onmouseover="alert(1)');
    expect(out).not.toContain('"');
    expect(out).toBe('&quot; onmouseover=&quot;alert(1)');
  });

  it('neutralises a script payload placed in text', () => {
    expect(escapeHtml('<script>alert(1)</script>')).toBe('&lt;script&gt;alert(1)&lt;/script&gt;');
  });

  it('coerces non-string values to their string form', () => {
    expect(escapeHtml(42)).toBe('42');
    expect(escapeHtml(0)).toBe('0');
    expect(escapeHtml(false)).toBe('false');
  });

  it('leaves plain text unchanged', () => {
    expect(escapeHtml('Hello World 123')).toBe('Hello World 123');
  });
});
