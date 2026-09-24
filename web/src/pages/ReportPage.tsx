import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { useReport } from '../api/hooks'
import type { Strategy } from '../api/types'
import { SeverityChip } from '../components/Badges'
import { ErrorNote, Layout } from '../components/Layout'
import { formatMillis } from '../components/StageTracker'
import { StrategyBar } from '../components/StrategyBar'

type Compile = { status?: string; verifier?: string; rounds?: number; errors?: number; millis?: number }
type Ai = { calls?: number; cacheHits?: number; inputTokens?: number; outputTokens?: number; estimatedCostUsd?: number }

export function ReportPage() {
  const { id = '' } = useParams()
  const { data, error } = useReport(id)
  if (!data) {
    return (
      <Layout jobId={id}>
        <div className="mx-auto max-w-5xl px-4 py-10"><ErrorNote error={error} /></div>
      </Layout>
    )
  }
  const stats = data.stats ?? {}
  const counts = (stats.methodsByStrategy ?? {}) as Partial<Record<Strategy, number>>
  const compile = (stats.compile ?? {}) as Compile
  const ai = (stats.ai ?? null) as Ai | null
  const methods = (stats.methods as number) ?? 0
  const automatic = methods - (counts.MANUAL_REQUIRED ?? 0)

  return (
    <Layout jobId={id}>
      <div className="mx-auto max-w-5xl px-4 py-10 sm:px-6">
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex-1">
            <p className="text-sm text-ink-3">Migration report</p>
            <h1 className="text-2xl font-semibold tracking-tight">{data.job.name}</h1>
          </div>
          <a
            href={api.downloadUrl(id)}
            className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-ink hover:opacity-90"
          >
            ↓ Download Spring Boot project
          </a>
        </div>

        <div className="mt-6 grid gap-4 sm:grid-cols-3">
          <Stat label="Files generated" value={String(stats.files ?? 0)} />
          <Stat
            label="Methods migrated automatically"
            value={`${automatic} of ${methods}`}
            note={`${counts.RULE_MAPPED ?? 0} by rules, ${(counts.AI_VERIFIED ?? 0) + (counts.AI_REPAIRED ?? 0)} by AI`}
          />
          <Stat
            label="Compile check"
            value={compile.status === 'PASS' ? '✓ Pass' : compile.status === 'FAIL' ? `✕ ${compile.errors} errors` : 'Not run'}
            note={compile.verifier ? `${compile.verifier}${compile.millis != null ? `, ${formatMillis(compile.millis)}` : ''}` : undefined}
          />
        </div>

        <section className="mt-8 rounded-lg border border-line bg-surface p-5">
          <h2 className="text-sm font-semibold">How each method was produced</h2>
          <p className="mb-4 text-xs text-ink-3">A file's confidence is its weakest method's, never the average.</p>
          <StrategyBar counts={counts} />
        </section>

        <section className="mt-8">
          <h2 className="text-sm font-semibold">Unresolved manual items ({data.manualItems.length})</h2>
          {data.manualItems.length === 0 ? (
            <p className="mt-2 text-sm text-ink-3">None. Every method body was migrated.</p>
          ) : (
            <ul className="mt-3 divide-y divide-line rounded-lg border border-line bg-surface">
              {data.manualItems.map((m) => (
                <li key={m.javaSignature + m.path} className="px-4 py-2.5 text-sm">
                  <div className="flex flex-wrap items-baseline gap-x-3">
                    <span className="font-mono text-[12.5px]">{m.javaSignature}</span>
                    {m.fileId && (
                      <Link to={`/jobs/${id}/review/${m.fileId}`} className="text-xs text-accent hover:underline">
                        open
                      </Link>
                    )}
                  </div>
                  <p className="text-ink-2">{m.reason}</p>
                  <p className="text-xs text-ink-3">{m.sourcePath}:{m.sourceLine}</p>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="mt-8">
          <h2 className="text-sm font-semibold">Findings by code</h2>
          <div className="mt-3 overflow-x-auto rounded-lg border border-line bg-surface">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-ink-3">
                <tr className="border-b border-line">
                  <th className="px-4 py-2 font-medium">Severity</th>
                  <th className="px-4 py-2 font-medium">Code</th>
                  <th className="px-4 py-2 text-right font-medium">Count</th>
                  <th className="px-4 py-2 font-medium">Example</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.findings.map((g) => (
                  <tr key={g.code}>
                    <td className="px-4 py-2"><SeverityChip severity={g.severity} /></td>
                    <td className="px-4 py-2 font-mono text-[12px]">{g.code}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{g.count}</td>
                    <td className="px-4 py-2 text-ink-2">{g.findings[0]?.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <div className="mt-8 grid gap-4 sm:grid-cols-2">
          <section className="rounded-lg border border-line bg-surface p-5">
            <h2 className="text-sm font-semibold">Review progress</h2>
            <p className="mt-2 text-sm tabular-nums text-ink-2">
              {data.review.accepted} accepted · {data.review.rejected} rejected · {data.review.pending} pending
            </p>
          </section>
          <section className="rounded-lg border border-line bg-surface p-5">
            <h2 className="text-sm font-semibold">AI usage</h2>
            {ai ? (
              <p className="mt-2 text-sm tabular-nums text-ink-2">
                {ai.calls ?? 0} calls ({ai.cacheHits ?? 0} from cache) · {(ai.inputTokens ?? 0).toLocaleString()} in /{' '}
                {(ai.outputTokens ?? 0).toLocaleString()} out
                {ai.estimatedCostUsd != null && ` · ~$${ai.estimatedCostUsd.toFixed(4)}`}
              </p>
            ) : (
              <p className="mt-2 text-sm text-ink-3">Not used for this migration.</p>
            )}
          </section>
        </div>
      </div>
    </Layout>
  )
}

function Stat({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface p-4">
      <p className="text-xs text-ink-3">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums tracking-tight">{value}</p>
      {note && <p className="mt-1 text-xs text-ink-2">{note}</p>}
    </div>
  )
}
