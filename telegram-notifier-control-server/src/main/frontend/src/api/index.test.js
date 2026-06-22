import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { api, ApiError, getToken, setToken, clearToken } from './index'

describe('api', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('getToken returns null when no token stored', () => {
    expect(getToken()).toBeNull()
  })

  it('setToken stores token in localStorage', () => {
    setToken('test-token')
    expect(localStorage.getItem('telegram-notifier-token')).toBe('test-token')
  })

  it('setToken with null removes token', () => {
    localStorage.setItem('telegram-notifier-token', 'test')
    setToken(null)
    expect(localStorage.getItem('telegram-notifier-token')).toBeNull()
  })

  it('clearToken removes token', () => {
    localStorage.setItem('telegram-notifier-token', 'test')
    clearToken()
    expect(localStorage.getItem('telegram-notifier-token')).toBeNull()
  })

  it('api sends X-Auth-Token header when token exists', async () => {
    setToken('my-token')
    const fetchSpy = vi.spyOn(window, 'fetch').mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve({ data: 'test' }),
    })

    await api('/test')

    const [, options] = fetchSpy.mock.calls[0]
    expect(options.headers['X-Auth-Token']).toBe('my-token')
  })

  it('api throws ApiError on non-OK response', async () => {
    vi.spyOn(window, 'fetch').mockResolvedValue({
      ok: false,
      status: 404,
      headers: { get: () => 'application/json' },
      text: () => Promise.resolve('{"error":"not found"}'),
    })

    await expect(api('/test')).rejects.toThrow(ApiError)
  })

  it('ApiError has status property', async () => {
    vi.spyOn(window, 'fetch').mockResolvedValue({
      ok: false,
      status: 401,
      headers: { get: () => 'application/json' },
      text: () => Promise.resolve('{"error":"unauthorized"}'),
    })

    try {
      await api('/test')
    } catch (e) {
      expect(e.status).toBe(401)
      expect(e.message).toBe('unauthorized')
    }
  })
})
