import { useState } from 'react'
import type { FileSummary } from '../api/types'
import { applyFilter, fileName, FILTERS, groupByCategory, type FileFilter } from '../lib/files'
import { CompileBadge, ConfidenceBadge, ReviewBadge } from './Badges'

export function FileTree({
  files,
  selectedId,
  onSelect,
}: {
  files: FileSummary[]
  selectedId?: string
  onSelect: (id: string) => void
}) {
  const [filter, setFilter] = useState<FileFilter>('all')
  const visible = applyFilter(files, filter)
  const groups = groupByCategory(visible)

  return (
    <div className="flex h-full flex-col">
      <div className="flex flex-wrap gap-1 border-b border-line p-2" role="tablist" aria-label="Filter files">
        {FILTERS.map((f) => {
          const count = applyFilter(files, f.id).length
          return (
            <button
              key={f.id}
              role="tab"
              aria-selected={filter === f.id}
              onClick={() => setFilter(f.id)}
              className={`rounded-md px-2 py-1 text-xs ${
                filter === f.id ? 'bg-accent-soft font-medium text-accent' : 'text-ink-2 hover:bg-surface-2'
              }`}
            >
              {f.label} <span className="tabular-nums text-ink-3">{count}</span>
            </button>
          )
        })}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto py-1">
        {groups.length === 0 && <p className="px-3 py-6 text-center text-sm text-ink-3">Nothing matches this filter.</p>}
        {groups.map((group) => (
          <section key={group.id} className="mb-1">
            <h3 className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-ink-3">
              {group.label}
            </h3>
            <ul>
              {group.files.map((file) => (
                <li key={file.id}>
                  <button
                    onClick={() => onSelect(file.id)}
                    aria-current={file.id === selectedId}
                    className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm ${
                      file.id === selectedId ? 'bg-accent-soft' : 'hover:bg-surface-2'
                    }`}
                    title={file.path}
                  >
                    <CompileBadge status={file.compileStatus} />
                    <span className="min-w-0 flex-1 truncate font-mono text-[12.5px]">
                      {fileName(file.path)}
                      {file.edited && <span className="ml-1 text-ink-3" title="Edited">•</span>}
                    </span>
                    <ReviewBadge status={file.reviewStatus} />
                    {file.findings > 0 && (
                      <span className="text-[11px] tabular-nums text-ink-3" title={`${file.findings} findings`}>
                        {file.findings}
                      </span>
                    )}
                    <ConfidenceBadge confidence={file.confidence} compact />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}
