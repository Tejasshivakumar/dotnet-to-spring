import type { Category, FileSummary, Severity, Strategy } from '../api/types'

export type FileFilter = 'all' | 'needs-review' | 'has-findings' | 'failed-compile'

export const FILTERS: { id: FileFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'needs-review', label: 'To review' },
  { id: 'has-findings', label: 'Findings' },
  { id: 'failed-compile', label: 'Failing' },
]

export function applyFilter(files: FileSummary[], filter: FileFilter): FileSummary[] {
  switch (filter) {
    case 'needs-review':
      return files.filter((f) => f.reviewStatus === 'PENDING')
    case 'has-findings':
      return files.filter((f) => f.findings > 0)
    case 'failed-compile':
      return files.filter((f) => f.compileStatus === 'FAIL')
    default:
      return files
  }
}

/** Review groups in the order a reviewer should read them: the HTTP surface first. */
export const CATEGORY_ORDER: { id: Category; label: string }[] = [
  { id: 'CONTROLLER', label: 'Controllers' },
  { id: 'SERVICE', label: 'Services' },
  { id: 'ENTITY', label: 'Entities' },
  { id: 'REPOSITORY', label: 'Repositories' },
  { id: 'DTO', label: 'DTOs' },
  { id: 'CONFIG', label: 'Config' },
  { id: 'MODEL', label: 'Unclassified' },
  { id: 'BUILD', label: 'Build & notes' },
]

export function groupByCategory(files: FileSummary[]): { id: Category; label: string; files: FileSummary[] }[] {
  return CATEGORY_ORDER.map((c) => ({ ...c, files: files.filter((f) => f.category === c.id) })).filter(
    (g) => g.files.length > 0,
  )
}

export type ConfidenceLevel = 'high' | 'medium' | 'low'

/** Green at 0.9 and above, amber from 0.5, red below: the thresholds in the design notes. */
export function confidenceLevel(confidence: number): ConfidenceLevel {
  if (confidence >= 0.9) return 'high'
  if (confidence >= 0.5) return 'medium'
  return 'low'
}

export function fileName(path: string): string {
  return path.substring(path.lastIndexOf('/') + 1)
}

export const STRATEGIES: { id: Strategy; label: string; color: string }[] = [
  { id: 'RULE_MAPPED', label: 'Rules', color: 'var(--series-1)' },
  { id: 'AI_VERIFIED', label: 'AI, compiled first time', color: 'var(--series-2)' },
  { id: 'AI_REPAIRED', label: 'AI, after one repair', color: 'var(--series-3)' },
  { id: 'MANUAL_REQUIRED', label: 'Manual', color: 'var(--series-4)' },
]

export const SEVERITY_ORDER: Severity[] = ['HIGH', 'MEDIUM', 'LOW', 'INFO']
