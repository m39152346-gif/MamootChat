import type { ReactNode } from 'react'

const URL_RE = /(https?:\/\/[^\s<>"']+)/g
const TRAILING = /[.,;:!?…،؛؟)\]}]+$/

/**
 * Renders plain text safely (React escaping) and linkifies only http/https
 * URLs that pass the URL parser. No HTML injection is possible here.
 */
export function RichText({ text }: { text: string }) {
  const parts = text.split(URL_RE)
  const nodes: ReactNode[] = []

  parts.forEach((part, i) => {
    if (!part) return
    if (/^https?:\/\//.test(part)) {
      let urlText = part
      let suffix = ''
      const trailing = part.match(TRAILING)
      if (trailing && !part.endsWith('/')) {
        suffix = trailing[0]
        urlText = part.slice(0, -suffix.length)
      }
      try {
        const url = new URL(urlText)
        if (url.protocol === 'http:' || url.protocol === 'https:') {
          nodes.push(
            <a key={i} href={url.href} target="_blank" rel="noopener noreferrer nofollow" dir="ltr">
              {urlText}
            </a>,
          )
          if (suffix) nodes.push(suffix)
          return
        }
      } catch {
        /* fall through to plain text */
      }
    }
    nodes.push(part)
  })

  return <span className="rich-text">{nodes}</span>
}
