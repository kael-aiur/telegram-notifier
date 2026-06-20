import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders loading state before bootstrap completes', () => {
    vi.spyOn(window, 'fetch').mockReturnValue(new Promise(() => {}))

    const wrapper = mount(App)

    expect(wrapper.text()).toContain('Loading')
  })
})
