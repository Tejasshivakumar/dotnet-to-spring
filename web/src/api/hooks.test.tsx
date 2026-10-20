import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { PropsWithChildren } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import type { JobDetail } from './types'
import { keys, useVerify } from './hooks'

vi.mock('./client', () => ({ api: { verify: vi.fn(), job: vi.fn() } }))
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks() })

it('keeps verification pending beyond 20 seconds and refreshes the report when it finishes', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  vi.mocked(api.verify).mockResolvedValue(undefined)
  vi.mocked(api.job).mockResolvedValue({ job: { status: 'RUNNING' } } as JobDetail)
  const { result } = renderHook(() => useVerify('job'), { wrapper })
  vi.useFakeTimers()
  act(() => result.current.mutate())
  await act(async () => { await vi.advanceTimersByTimeAsync(25001) })
  expect(result.current.isPending).toBe(true)
  expect(vi.mocked(api.job).mock.calls.length).toBeGreaterThan(20)
  vi.mocked(api.job).mockResolvedValue({ job: { status: 'COMPLETED', compileStatus: 'PASS' } } as JobDetail)
  await act(async () => { await vi.advanceTimersByTimeAsync(1001) })
  expect(result.current.isSuccess).toBe(true)
  expect(invalidate).toHaveBeenCalledWith({ queryKey: keys.report('job') })
  client.clear()
})
