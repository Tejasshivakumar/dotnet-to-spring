import { FILES } from '../test/fixtures'
import { applyFilter, confidenceLevel, groupByCategory } from './files'

describe('file filters', () => {
  it('needs review means not yet accepted or rejected', () => {
    expect(applyFilter(FILES, 'needs-review').map((f) => f.id)).toEqual(['pom', 'book', 'ctl'])
  })

  it('has findings and failed compile are independent filters', () => {
    expect(applyFilter(FILES, 'has-findings').map((f) => f.id)).toEqual(['book', 'svc', 'ctl'])
    expect(applyFilter(FILES, 'failed-compile').map((f) => f.id)).toEqual(['ctl'])
  })

  it('groups controllers first and build files last', () => {
    expect(groupByCategory(FILES).map((g) => g.label)).toEqual(['Controllers', 'Services', 'Entities', 'Build & notes'])
  })

  it('uses the documented confidence thresholds', () => {
    expect(confidenceLevel(0.95)).toBe('high')
    expect(confidenceLevel(0.9)).toBe('high')
    expect(confidenceLevel(0.7)).toBe('medium')
    expect(confidenceLevel(0.5)).toBe('medium')
    expect(confidenceLevel(0)).toBe('low')
  })
})
