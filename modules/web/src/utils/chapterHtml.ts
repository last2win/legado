import DOMPurify from 'dompurify'

/** Book content is data, never a script or a form in the authenticated management origin. */
export function chapterHtml(content: string, imageUrl: (src: string) => string): string {
  const fragment = DOMPurify.sanitize(content, {
    ALLOWED_TAGS: ['p', 'br', 'b', 'strong', 'i', 'em', 'u', 's', 'small', 'sub', 'sup',
      'ruby', 'rt', 'rp', 'span', 'blockquote', 'code', 'pre', 'hr', 'img'],
    ALLOWED_ATTR: ['src', 'alt', 'title'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    RETURN_DOM_FRAGMENT: true,
  })
  for (const img of fragment.querySelectorAll('img')) {
    const src = img.getAttribute('src')?.trim()
    if (!src || /^(?:javascript|vbscript|blob):/i.test(src) ||
      (/^data:/i.test(src) && !/^data:image\/(?:png|jpeg|gif|webp);base64,/i.test(src))) {
      img.remove()
      continue
    }
    // A supplied same-origin URL must not turn <img> into a credentialed management request.
    img.setAttribute('src', imageUrl(src))
    img.setAttribute('referrerpolicy', 'no-referrer')
  }
  const holder = document.createElement('div')
  holder.append(fragment)
  return holder.innerHTML
}
