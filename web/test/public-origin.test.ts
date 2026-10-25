import { expect, test } from 'bun:test'
import { publicOrigin } from '../lib/utils'

test('反代后以转发头为准，直连时退回请求地址', () => {
  const proxied = new Request('http://localhost:3000/api/model', {
    headers: { host: 'localhost:3000', 'x-forwarded-host': 'www.everytalk.cc', 'x-forwarded-proto': 'https' },
  })
  expect(publicOrigin(proxied)).toBe('https://www.everytalk.cc')
  expect(publicOrigin(new Request('https://www.everytalk.cc/api/model'))).toBe('https://www.everytalk.cc')
})
