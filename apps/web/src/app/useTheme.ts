import { useEffect, useState } from 'react'

export type Theme = 'dark' | 'light'

const themeKey = 'spaceagent-theme'

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => {
    const stored = window.localStorage.getItem(themeKey)
    if (stored === 'dark' || stored === 'light') return stored
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  })

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#080a0c' : '#f2f3f0')
    window.localStorage.setItem(themeKey, theme)
    return () => {
      delete document.documentElement.dataset.theme
      document.documentElement.style.colorScheme = ''
    }
  }, [theme])

  return { theme, toggleTheme: () => setTheme((value) => value === 'dark' ? 'light' : 'dark') }
}
