import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { keys, useJob } from '../api/hooks'
import { Button, ErrorNote, Layout } from '../components/Layout'
import { StageTracker } from '../components/StageTracker'

export function ProgressPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const client = useQueryClient()
  const { data, error } = useJob(id)
  const [live, setLive] = useState<string | null>(null)

  // The SSE stream drives the page; each event refreshes the job so stage
  // timings and details come from the server rather than being reconstructed.
  useEffect(() => {
    const source = new EventSource(api.eventsUrl(id))
    const refresh = () => client.invalidateQueries({ queryKey: keys.job(id) })
    const onStage = (e: MessageEvent) => {
      setLive(JSON.parse(e.data).stage)
      refresh()
    }
    source.addEventListener('snapshot', onStage)
    source.addEventListener('stage', onStage)
    source.addEventListener('stage-complete', refresh)
    source.addEventListener('failed', () => {
      refresh()
      source.close()
    })
    source.addEventListener('completed', () => {
      source.close()
      refresh()
      setTimeout(() => navigate(`/jobs/${id}/review`), 900)
    })
    return () => source.close()
  }, [id, client, navigate])

  const job = data?.job
  const finished = job?.status === 'COMPLETED' || job?.status === 'FAILED'

  return (
    <Layout jobId={id}>
      <div className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
        <p className="text-sm text-ink-3">Migration</p>
        <h1 className="text-2xl font-semibold tracking-tight">{job?.name ?? '…'}</h1>
        <ErrorNote error={error} />
        {job?.status === 'FAILED' && (
          <div role="alert" className="mt-4 rounded-md border border-line bg-surface px-4 py-3 text-sm">
            <p className="font-medium"><span className="text-critical">✕</span> The migration failed</p>
            <p className="mt-1 font-mono text-[12.5px] text-ink-2">{job.errorMessage}</p>
          </div>
        )}
        <div className="mt-6">
          <StageTracker
            timings={data?.stages ?? []}
            current={live ?? job?.currentStage ?? null}
            finished={finished}
            failed={job?.status === 'FAILED'}
          />
        </div>
        {job?.status === 'COMPLETED' && (
          <div className="mt-6 flex gap-3">
            <Link to={`/jobs/${id}/review`}><Button variant="primary">Review files</Button></Link>
            <Link to={`/jobs/${id}/report`}><Button>Report</Button></Link>
          </div>
        )}
      </div>
    </Layout>
  )
}
