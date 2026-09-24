import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReviewActions } from './ReviewActions'

describe('ReviewActions', () => {
  it('accepting with unsaved edits saves them', async () => {
    const onReview = vi.fn()
    render(<ReviewActions status="PENDING" dirty busy={false} onReview={onReview} onRevert={() => {}} />)

    await userEvent.click(screen.getByRole('button', { name: /Save & accept/ }))
    expect(onReview).toHaveBeenCalledWith('ACCEPTED', true)
  })

  it('rejecting never saves an edit', async () => {
    const onReview = vi.fn()
    render(<ReviewActions status="PENDING" dirty busy={false} onReview={onReview} onRevert={() => {}} />)

    await userEvent.click(screen.getByRole('button', { name: /Reject/ }))
    expect(onReview).toHaveBeenCalledWith('REJECTED', false)
  })

  it('offers save and discard only when there is an edit', () => {
    const { rerender } = render(
      <ReviewActions status="ACCEPTED" dirty={false} busy={false} onReview={() => {}} onRevert={() => {}} />,
    )
    expect(screen.queryByRole('button', { name: 'Save edit' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Accept/ })).toBeDisabled()

    rerender(<ReviewActions status="ACCEPTED" dirty busy={false} onReview={() => {}} onRevert={() => {}} />)
    expect(screen.getByRole('button', { name: 'Save edit' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Discard' })).toBeEnabled()
  })
})
