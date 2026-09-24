import type { FileDetail, FileSummary, JobDetail, JobSummary, Page, Problem, Report, ReviewStatus } from './types'

/** An RFC 7807 problem from the API, surfaced with its human-readable detail. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `Request failed (${status})`)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    let problem: Problem = { status: response.status }
    try {
      problem = await response.json()
    } catch {
      // not JSON: keep the status
    }
    throw new ApiError(response.status, problem)
  }
  if (response.status === 202 && response.headers.get('content-length') === '0') {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export interface StartOptions {
  basePackage?: string
  keepInterfacePrefix?: boolean
  ai?: boolean
}

function optionParams(options: StartOptions): URLSearchParams {
  const params = new URLSearchParams()
  if (options.basePackage?.trim()) params.set('basePackage', options.basePackage.trim())
  if (options.keepInterfacePrefix) params.set('keepInterfacePrefix', 'true')
  if (options.ai) params.set('ai', 'true')
  return params
}

export const api = {
  listJobs: (page = 0, size = 10) => request<Page<JobSummary>>(`/api/jobs?page=${page}&size=${size}`),

  job: (id: string) => request<JobDetail>(`/api/jobs/${id}`),

  files: (id: string) => request<FileSummary[]>(`/api/jobs/${id}/files`),

  file: (id: string, fileId: string) => request<FileDetail>(`/api/jobs/${id}/files/${fileId}`),

  report: (id: string) => request<Report>(`/api/jobs/${id}/report`),

  upload: (file: File, options: StartOptions) => {
    const form = new FormData()
    form.append('file', file)
    optionParams(options).forEach((value, key) => form.append(key, value))
    return request<JobSummary>('/api/jobs', { method: 'POST', body: form })
  },

  sample: (options: StartOptions) =>
    request<JobSummary>(`/api/jobs/sample?${optionParams(options)}`, { method: 'POST' }),

  review: (id: string, fileId: string, status: ReviewStatus, editedContent?: string) =>
    request<FileSummary>(`/api/jobs/${id}/files/${fileId}/review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, editedContent }),
    }),

  verify: (id: string) => request<void>(`/api/jobs/${id}/verify`, { method: 'POST' }),

  remove: (id: string) => request<void>(`/api/jobs/${id}`, { method: 'DELETE' }),

  downloadUrl: (id: string) => `/api/jobs/${id}/download`,

  eventsUrl: (id: string) => `/api/jobs/${id}/events`,
}
