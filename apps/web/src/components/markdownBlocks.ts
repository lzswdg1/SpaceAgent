/** Stable top-level blocks. A pending list/table/fence stays in the mutable tail. */
export function markdownBlocks(content: string): string[] {
  // Reference definitions have document-wide semantics; parse them as one document.
  if (/^ {0,3}\[[^\]]+\]:/m.test(content)) return [content]
  const lines = content.split(/(?<=\n)/)
  const blocks: string[] = []
  let current = ''
  let fence: { char: string; length: number } | null = null
  let grouped = false
  for (const line of lines) {
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)
    if (marker) {
      const value = marker[1]
      if (!fence) fence = { char: value[0], length: value.length }
      else if (value[0] === fence.char && value.length >= fence.length && /^ {0,3}(`+|~+)\s*$/.test(line)) fence = null
    }
    if (/^( {0,3}(?:[-+*]|\d+[.)])\s|\s{0,3}>|\s*\||\s{4}\S)/.test(line)) grouped = true
    // Lists/quotes/tables can continue after an empty line; only an explicit new
    // top-level heading safely closes that group during incremental rendering.
    if (!fence && grouped && /^#{1,6}\s/.test(line) && current) {
      blocks.push(current); current = ''; grouped = false
    }
    current += line
    if (!fence && !grouped && /^\s*$/.test(line) && current.trim()) {
      blocks.push(current); current = ''
    }
  }
  if (current) blocks.push(current)
  return blocks.length ? blocks : [content]
}
