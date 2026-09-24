import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { ReviewStatus } from './types'

export const keys = {
  jobs: ['jobs'] as const,
  job: (id: string) => ['job', id] as const,
  files: (id: string) => ['files', id] as const,
  file: (id: string, fileId: string) => ['file', id, fileId] as const,
  report: (id: string) => ['report', id] as const,
}

export const useJobs = () => useQuery({ queryKey: keys.jobs, queryFn: () => api.listJobs(0, 8) })

export const useJob = (id: string) => useQuery({ queryKey: keys.job(id), queryFn: () => api.job(id) })

export const useFiles = (id: string) => useQuery({ queryKey: keys.files(id), queryFn: () => api.files(id) })

export const useFile = (id: string, fileId: string | undefined) =>
  useQuery({
    queryKey: keys.file(id, fileId ?? ''),
    queryFn: () => api.file(id, fileId!),
    enabled: !!fileId,
  })

export const useReport = (id: string) => useQuery({ queryKey: keys.report(id), queryFn: () => api.report(id) })

export function useReview(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (v: { fileId: string; status: ReviewStatus; editedContent?: string }) =>
      api.review(jobId, v.fileId, v.status, v.editedContent),
    onSuccess: (_, v) => {
      client.invalidateQueries({ queryKey: keys.files(jobId) })
      client.invalidateQueries({ queryKey: keys.file(jobId, v.fileId) })
      client.invalidateQueries({ queryKey: keys.report(jobId) })
    },
  })
}

export function useVerify(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.verify(jobId),
    onSuccess: () => {
      // Verification runs in the background; poll a few times for its result.
      for (const delay of [1500, 4000, 9000, 20000]) {
        setTimeout(() => {
          client.invalidateQueries({ queryKey: keys.files(jobId) })
          client.invalidateQueries({ queryKey: keys.job(jobId) })
          client.invalidateQueries({ queryKey: ['file', jobId] })
        }, delay)
      }
    },
  })
}
