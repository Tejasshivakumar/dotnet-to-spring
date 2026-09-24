import { render, screen } from '@testing-library/react'
import { StrategyBar } from './StrategyBar'

describe('StrategyBar', () => {
  it('prints every count as text so the chart is never colour-only', () => {
    render(<StrategyBar counts={{ RULE_MAPPED: 14, MANUAL_REQUIRED: 2 }} />)

    expect(screen.getByRole('img')).toHaveAccessibleName('Rules: 14 of 16; Manual: 2 of 16')
    const rules = screen.getByText('Rules').closest('tr')!
    expect(rules).toHaveTextContent('14')
    expect(rules).toHaveTextContent('88%')
  })

  it('says so when there is nothing to chart', () => {
    render(<StrategyBar counts={{}} />)
    expect(screen.getByText('No method bodies to migrate.')).toBeInTheDocument()
  })
})
