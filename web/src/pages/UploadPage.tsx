import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type DragEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type StartOptions } from '../api/client'
import { keys, useJobs } from '../api/hooks'
import type { JobSummary } from '../api/types'
import { Button, ErrorNote, Layout } from '../components/Layout'

export function UploadPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [options, setOptions] = useState<StartOptions>({ basePackage: '', keepInterfacePrefix: false, ai: false })

  const start = useMutation({
    mutationFn: (file?: File) => (file ? api.upload(file, options) : api.sample(options)),
    onSuccess: (job) => {
      client.invalidateQueries({ queryKey: keys.jobs })
      navigate(`/jobs/${job.id}`)
    },
  })

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files[0]
    if (file) start.mutate(file)
  }

  return (
    <Layout>
      <div className="mx-auto max-w-3xl px-4 py-12 sm:px-6">
        <h1 className="text-3xl font-semibold tracking-tight">Migrate an ASP.NET Core service to Spring Boot</h1>
        <p className="mt-3 max-w-2xl text-ink-2">
          Deterministic rules translate everything they can. An LLM handles only what the rules cannot. Every
          generated file is compiled before you see it, and you review it beside the original C#.
        </p>

        <div
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`mt-8 rounded-xl border-2 border-dashed p-10 text-center transition ${
            dragging ? 'border-accent bg-accent-soft' : 'border-line bg-surface'
          }`}
        >
          <p className="font-medium">Drop a zipped project here</p>
          <p className="mt-1 text-sm text-ink-3">.cs, .csproj and appsettings.json are read; up to 20 MB</p>
          <div className="mt-5 flex flex-wrap justify-center gap-3">
            <Button onClick={() => input.current?.click()} disabled={start.isPending}>
              Choose a zip…
            </Button>
            <Button variant="primary" onClick={() => start.mutate(undefined)} disabled={start.isPending}>
              {start.isPending ? 'Starting…' : 'Use sample project'}
            </Button>
          </div>
          <input
            ref={input}
            type="file"
            accept=".zip,application/zip"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) start.mutate(file)
              e.target.value = ''
            }}
          />
        </div>

        <details className="mt-4 rounded-lg border border-line bg-surface px-4 py-3">
          <summary className="cursor-pointer text-sm font-medium">Options</summary>
          <div className="mt-3 grid gap-3 text-sm">
            <label className="grid gap-1">
              <span className="text-ink-2">Base Java package</span>
              <input
                value={options.basePackage}
                onChange={(e) => setOptions({ ...options, basePackage: e.target.value })}
                placeholder="derived from the C# root namespace"
                className="rounded-md border border-line bg-bg px-2 py-1.5 font-mono text-[13px]"
              />
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={options.keepInterfacePrefix}
                onChange={(e) => setOptions({ ...options, keepInterfacePrefix: e.target.checked })}
              />
              Keep the C# <code className="font-mono text-[13px]">I</code> prefix on service interfaces
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={options.ai} onChange={(e) => setOptions({ ...options, ai: e.target.checked })} />
              Use AI for method bodies the rules cannot translate
            </label>
          </div>
        </details>

        <div className="mt-4">
          <ErrorNote error={start.error} />
        </div>

        <RecentJobs />
      </div>
    </Layout>
  )
}

function RecentJobs() {
  const { data } = useJobs()
  if (!data || data.items.length === 0) return null
  return (
    <section className="mt-12">
      <h2 className="text-sm font-semibold uppercase tracking-wider text-ink-3">Recent migrations</h2>
      <ul className="mt-3 divide-y divide-line rounded-lg border border-line bg-surface">
        {data.items.map((job) => (
          <li key={job.id}>
            <Link to={job.status === 'COMPLETED' ? `/jobs/${job.id}/review` : `/jobs/${job.id}`} className="flex items-center gap-3 px-4 py-2.5 hover:bg-surface-2">
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{job.name}</span>
              <JobOutcome job={job} />
              <span className="text-xs tabular-nums text-ink-3">{new Date(job.createdAt).toLocaleString()}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

function JobOutcome({ job }: { job: JobSummary }) {
  if (job.status === 'FAILED') return <span className="text-xs text-critical">✕ failed</span>
  if (job.status !== 'COMPLETED') return <span className="text-xs text-ink-2">{job.currentStage?.toLowerCase() ?? 'queued'}…</span>
  const rules = job.methodsByStrategy?.RULE_MAPPED ?? 0
  return (
    <span className="text-xs text-ink-2 tabular-nums">
      {rules}/{job.methods ?? 0} by rules · {job.compileStatus === 'PASS' ? '✓ compiles' : '✕ compile errors'}
    </span>
  )
}
