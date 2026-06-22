import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('renders router-view', () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          'router-view': { template: '<div>router-view-stub</div>' }
        }
      }
    })

    expect(wrapper.text()).toContain('router-view-stub')
  })
})
