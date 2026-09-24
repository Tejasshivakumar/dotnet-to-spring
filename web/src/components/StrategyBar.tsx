import { useState } from 'react'
import type { Strategy } from '../api/types'
import { STRATEGIES } from '../lib/files'

/**
 * How each method was produced, as one stacked bar. Part-to-whole with four parts reads more
 * accurately as lengths on a shared baseline than as donut angles. The legend doubles as the
 * table view: every segment's count and share is printed as text beside its swatch.
 */
export function StrategyBar({ counts }: { counts: Partial<Record<Strategy, number>> }) {
  const [hover, setHover] = useState<Strategy | null>(null)
  const total = STRATEGIES.reduce((sum, s) => sum + (counts[s.id] ?? 0), 0)
  if (total === 0) {
    return <p className="text-sm text-ink-3">No method bodies to migrate.</p>
  }
  const parts = STRATEGIES.map((s) => ({ ...s, count: counts[s.id] ?? 0 })).filter((s) => s.count > 0)

  return (
    <figure className="m-0">
      <div
        className="relative flex h-6 w-full gap-[2px]"
        role="img"
        aria-label={parts.map((p) => `${p.label}: ${p.count} of ${total}`).join('; ')}
        onMouseLeave={() => setHover(null)}
      >
        {parts.map((p, i) => (
          <div
            key={p.id}
            className="relative h-full"
            style={{
              width: `${(p.count / total) * 100}%`,
              minWidth: 6,
              background: p.color,
              borderRadius: `${i === 0 ? 4 : 0}px ${i === parts.length - 1 ? 4 : 0}px ${i === parts.length - 1 ? 4 : 0}px ${i === 0 ? 4 : 0}px`,
              opacity: hover && hover !== p.id ? 0.45 : 1,
            }}
            onMouseEnter={() => setHover(p.id)}
          >
            {hover === p.id && (
              <div
                role="tooltip"
                className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-2 -translate-x-1/2 whitespace-nowrap rounded-md border border-line bg-surface px-2 py-1 text-xs text-ink shadow-sm"
              >
                <span className="font-medium">{p.label}</span>
                <span className="ml-2 tabular-nums text-ink-2">
                  {p.count} of {total} ({Math.round((p.count / total) * 100)}%)
                </span>
              </div>
            )}
          </div>
        ))}
      </div>
      <figcaption>
        <table className="mt-3 w-full text-sm">
          <tbody>
            {STRATEGIES.map((s) => {
              const count = counts[s.id] ?? 0
              return (
                <tr key={s.id} className={count === 0 ? 'text-ink-3' : 'text-ink'}>
                  <td className="py-0.5 pr-2">
                    <span aria-hidden className="inline-block h-2.5 w-2.5 rounded-sm align-middle" style={{ background: s.color }} />
                  </td>
                  <td className="w-full py-0.5">{s.label}</td>
                  <td className="py-0.5 pl-4 text-right tabular-nums">{count}</td>
                  <td className="py-0.5 pl-3 text-right tabular-nums text-ink-3">
                    {Math.round((count / total) * 100)}%
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </figcaption>
    </figure>
  )
}
