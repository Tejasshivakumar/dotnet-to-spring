import type { StageTiming } from '../api/types'

export const STAGES: { id: string; label: string; help: string }[] = [
  { id: 'INGEST', label: 'Ingest', help: 'Vet and record the uploaded sources' },
  { id: 'PARSE', label: 'Parse', help: 'C# into the intermediate model' },
  { id: 'CLASSIFY', label: 'Classify', help: 'Controllers, entities, services…' },
  { id: 'PLAN', label: 'Plan', help: 'What will be generated' },
  { id: 'GENERATE', label: 'Generate', help: 'Rules first: structure and method bodies' },
  { id: 'AI_FILL', label: 'AI fill', help: 'Only what the rules could not handle' },
  { id: 'VERIFY', label: 'Verify', help: 'Compile every generated file' },
  { id: 'REPAIR', label: 'Repair', help: 'Fix or demote what failed' },
  { id: 'REPORT', label: 'Report', help: 'Record files, methods and findings' },
]

type State = 'done' | 'running' | 'pending' | 'skipped' | 'failed'

export function StageTracker({
  timings,
  current,
  finished,
  failed,
}: {
  timings: StageTiming[]
  current: string | null
  finished: boolean
  failed: boolean
}) {
  const byStage = new Map(timings.map((t) => [t.stage, t]))
  return (
    <ol className="divide-y divide-line rounded-lg border border-line bg-surface">
      {STAGES.map((stage) => {
        const timing = byStage.get(stage.id)
        let state: State = 'pending'
        if (timing?.completedAt) state = 'done'
        else if (timing && failed) state = 'failed'
        else if (timing || current === stage.id) state = 'running'
        else if (finished) state = 'skipped'
        return (
          <li key={stage.id} className="flex items-start gap-3 px-4 py-3" aria-current={state === 'running' ? 'step' : undefined}>
            <StageIcon state={state} />
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline gap-2">
                <span className={`text-sm font-medium ${state === 'pending' || state === 'skipped' ? 'text-ink-3' : 'text-ink'}`}>
                  {stage.label}
                </span>
                <span className="text-xs text-ink-3">{stage.help}</span>
              </div>
              {timing?.detail && <p className="mt-0.5 text-sm text-ink-2">{timing.detail}</p>}
              {state === 'skipped' && <p className="mt-0.5 text-xs text-ink-3">Not needed for this job</p>}
            </div>
            <span className="shrink-0 text-xs tabular-nums text-ink-3">
              {timing?.millis != null ? formatMillis(timing.millis) : state === 'running' ? 'running…' : ''}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

function StageIcon({ state }: { state: State }) {
  const base = 'mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full text-[11px] font-bold'
  switch (state) {
    case 'done':
      return <span className={base} style={{ background: 'var(--good)', color: '#fff' }} aria-label="done">✓</span>
    case 'failed':
      return <span className={base} style={{ background: 'var(--critical)', color: '#fff' }} aria-label="failed">✕</span>
    case 'running':
      return (
        <span className={`${base} border-2 border-accent`} aria-label="running">
          <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />
        </span>
      )
    default:
      return <span className={`${base} border border-line`} aria-label={state} />
  }
}

export function formatMillis(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`
}
