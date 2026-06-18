const TOKEN_KEY = 'telegram-notifier-token'

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  const token = getToken()
  if (token) headers['X-Auth-Token'] = token
  const response = await fetch(`/api${path}`, { ...options, headers })
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response))
  const contentType = response.headers.get('content-type') || ''
  return contentType.includes('application/json') ? response.json() : undefined
}

async function errorMessage(response) {
  const contentType = response.headers.get('content-type') || ''
  const text = await response.text()
  if (!contentType.includes('application/json')) return text
  try {
    return JSON.parse(text).error || text
  } catch {
    return text
  }
}
