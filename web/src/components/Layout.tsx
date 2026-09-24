import type { ReactNode } from 'react'
import { Link, NavLink } from 'react-router-dom'

export function Layout({ children, jobId }: { children: ReactNode; jobId?: string }) {
  const tab = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-2.5 py-1 text-sm ${isActive ? 'bg-surface-2 text-ink' : 'text-ink-2 hover:text-ink'}`
  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex h-14 max-w-[1400px] items-center gap-6 px-4 sm:px-6">
          <Link to="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <span className="grid h-7 w-7 place-items-center rounded-md bg-accent text-sm text-accent-ink">P</span>
            Portway
          </Link>
          {jobId && (
            <nav className="flex gap-1" aria-label="Job">
              <NavLink to={`/jobs/${jobId}`} end className={tab}>Progress</NavLink>
              <NavLink to={`/jobs/${jobId}/review`} className={tab}>Review</NavLink>
              <NavLink to={`/jobs/${jobId}/report`} className={tab}>Report</NavLink>
            </nav>
          )}
          <span className="ml-auto hidden text-xs text-ink-3 sm:block">.NET to Spring Boot migration assistant</span>
        </div>
      </header>
      <main className="flex-1">{children}</main>
    </div>
  )
}

export function Button({
  children,
  variant = 'secondary',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  const styles = {
    primary: 'bg-accent text-accent-ink hover:opacity-90',
    secondary: 'border border-line bg-surface text-ink hover:bg-surface-2',
    danger: 'border border-line bg-surface text-critical hover:bg-surface-2',
  }[variant]
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${props.className ?? ''}`}
    >
      {children}
    </button>
  )
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof Error ? error.message : String(error)
  return (
    <div role="alert" className="rounded-md border border-line bg-surface px-3 py-2 text-sm text-ink">
      <span aria-hidden className="mr-1.5 text-critical">✕</span>
      {message}
    </div>
  )
}
