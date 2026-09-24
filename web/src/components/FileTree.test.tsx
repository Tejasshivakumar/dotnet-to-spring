import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FILES } from '../test/fixtures'
import { FileTree } from './FileTree'

describe('FileTree', () => {
  it('filters to failed compiles and selects a file', async () => {
    const onSelect = vi.fn()
    render(<FileTree files={FILES} onSelect={onSelect} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    await userEvent.click(screen.getByRole('tab', { name: /Failing/ }))

    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(1)
    await userEvent.click(within(items[0]).getByRole('button'))
    expect(onSelect).toHaveBeenCalledWith('ctl')
  })

  it('shows each filter count and an empty state', async () => {
    render(<FileTree files={FILES.slice(0, 1)} onSelect={() => {}} />)

    expect(screen.getByRole('tab', { name: 'Findings 0' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /Findings/ }))
    expect(screen.getByText('Nothing matches this filter.')).toBeInTheDocument()
  })

  it('never shows confidence by colour alone', () => {
    render(<FileTree files={FILES} onSelect={() => {}} />)
    expect(screen.getByTitle('Confidence 0.00: low')).toHaveTextContent('✕')
  })
})
