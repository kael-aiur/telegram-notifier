import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import LoginWizard from './LoginWizard.vue'

// Mock the API modules
vi.mock('@/api/accounts', () => ({
  getAccountStatus: vi.fn().mockResolvedValue({
    authorizationState: 'WAIT_PHONE',
    activeProxyId: null,
    errorMessage: null,
  }),
  submitPhone: vi.fn(),
  submitCode: vi.fn(),
  submitPassword: vi.fn(),
}))

describe('LoginWizard', () => {
  it('can be mounted', () => {
    const wrapper = mount(LoginWizard, {
      props: { modelValue: true, accountId: 1 },
      global: {
        stubs: {
          'el-dialog': { template: '<div><slot /></div>' },
          'el-steps': { template: '<div><slot /></div>' },
          'el-step': { template: '<div />' },
          'el-form': { template: '<div><slot /></div>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<input />' },
          'el-button': { template: '<button><slot /></button>' },
          'el-alert': { template: '<div />' },
          'el-result': { template: '<div />' },
          'el-icon': { template: '<div />' },
        }
      }
    })

    expect(wrapper.exists()).toBe(true)
  })

  it('accepts accountId prop', () => {
    const wrapper = mount(LoginWizard, {
      props: { modelValue: true, accountId: 42 },
      global: {
        stubs: {
          'el-dialog': { template: '<div><slot /></div>' },
          'el-steps': { template: '<div><slot /></div>' },
          'el-step': { template: '<div />' },
          'el-form': { template: '<div><slot /></div>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<input />' },
          'el-button': { template: '<button><slot /></button>' },
          'el-alert': { template: '<div />' },
          'el-result': { template: '<div />' },
          'el-icon': { template: '<div />' },
        }
      }
    })

    expect(wrapper.props('accountId')).toBe(42)
  })

  it('accepts modelValue prop', () => {
    const wrapper = mount(LoginWizard, {
      props: { modelValue: false, accountId: 1 },
      global: {
        stubs: {
          'el-dialog': { template: '<div><slot /></div>' },
          'el-steps': { template: '<div><slot /></div>' },
          'el-step': { template: '<div />' },
          'el-form': { template: '<div><slot /></div>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<input />' },
          'el-button': { template: '<button><slot /></button>' },
          'el-alert': { template: '<div />' },
          'el-result': { template: '<div />' },
          'el-icon': { template: '<div />' },
        }
      }
    })

    expect(wrapper.props('modelValue')).toBe(false)
  })
})
