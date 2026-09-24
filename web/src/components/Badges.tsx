import type { CompileStatus, ReviewStatus, Severity } from '../api/types'
import { confidenceLevel } from '../lib/files'

// Status colors never stand alone: every badge carries an icon and a label, so
// meaning survives colour blindness, greyscale printing and forced-colours mode.

const LEVEL = {
  high: { label: 'High', icon: '✓', color: 'var(--good)' },
  medium: { label: 'Medium', icon: '!', color: 'var(--warning)' },
  low: { label: 'Low', icon: '✕', color: 'var(--critical)' },
} as const

export function ConfidenceBadge({ confidence, compact = false }: { confidence: number; compact?: boolean }) {
  const level = LEVEL[confidenceLevel(confidence)]
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full border border-line bg-surface px-1.5 py-0.5 text-[11px] font-medium text-ink-2 tabular-nums"
      title={`Confidence ${confidence.toFixed(2)}: ${level.label.toLowerCase()}`}
    >
      <span aria-hidden style={{ color: level.color }}>{level.icon}</span>
      {compact ? confidence.toFixed(2) : `${level.label} · ${confidence.toFixed(2)}`}
    </span>
  )
}

export function CompileBadge({ status }: { status: CompileStatus }) {
  const map = {
    PASS: { icon: '✓', label: 'Compiles', color: 'var(--good)' },
    FAIL: { icon: '✕', label: 'Fails to compile', color: 'var(--critical)' },
    NOT_RUN: { icon: '–', label: 'Not verified', color: 'var(--text-3)' },
  }[status]
  return (
    <span className="inline-flex items-center gap-1 text-[11px] text-ink-2" title={map.label}>
      <span aria-hidden style={{ color: map.color }} className="font-semibold">{map.icon}</span>
      <span className="sr-only">{map.label}</span>
    </span>
  )
}

export function SeverityChip({ severity }: { severity: Severity }) {
  const map = {
    HIGH: { icon: '▲', color: 'var(--critical)' },
    MEDIUM: { icon: '◆', color: 'var(--warning)' },
    LOW: { icon: '●', color: 'var(--text-3)' },
    INFO: { icon: 'i', color: 'var(--text-3)' },
  }[severity]
  return (
    <span className="inline-flex shrink-0 items-center gap-1 rounded border border-line px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-ink-2">
      <span aria-hidden style={{ color: map.color }}>{map.icon}</span>
      {severity}
    </span>
  )
}

export function ReviewBadge({ status }: { status: ReviewStatus }) {
  if (status === 'PENDING') return null
  return (
    <span
      className="text-[10px] font-semibold uppercase tracking-wide"
      style={{ color: status === 'ACCEPTED' ? 'var(--good)' : 'var(--critical)' }}
      title={status === 'ACCEPTED' ? 'Accepted' : 'Rejected'}
    >
      {status === 'ACCEPTED' ? '✓ ok' : '✕ no'}
    </span>
  )
}
