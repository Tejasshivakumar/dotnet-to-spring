import type { ReviewStatus } from '../api/types'
import { Button } from './Layout'

/**
 * Accept, reject, or save an edit. Accepting with unsaved edits saves them too; nothing a
 * reviewer typed is silently thrown away.
 */
export function ReviewActions({
  status,
  dirty,
  busy,
  onReview,
  onRevert,
}: {
  status: ReviewStatus
  dirty: boolean
  busy: boolean
  onReview: (status: ReviewStatus, saveEdit: boolean) => void
  onRevert: () => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {dirty && (
        <>
          <span className="text-xs text-ink-3">Unsaved edit</span>
          <Button onClick={onRevert} disabled={busy}>Discard</Button>
          <Button onClick={() => onReview(status, true)} disabled={busy}>Save edit</Button>
        </>
      )}
      <Button
        variant="danger"
        onClick={() => onReview('REJECTED', false)}
        disabled={busy || status === 'REJECTED'}
        aria-pressed={status === 'REJECTED'}
      >
        ✕ Reject
      </Button>
      <Button
        variant="primary"
        onClick={() => onReview('ACCEPTED', dirty)}
        disabled={busy || (status === 'ACCEPTED' && !dirty)}
        aria-pressed={status === 'ACCEPTED'}
      >
        ✓ {dirty ? 'Save & accept' : 'Accept'}
      </Button>
    </div>
  )
}
