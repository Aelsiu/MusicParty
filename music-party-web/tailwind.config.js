/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // 由页面主题变量提供色板，保留已有的 medical/accent 类名。
        'medical': {
          50: 'rgb(var(--medical-50) / <alpha-value>)',
          100: 'rgb(var(--medical-100) / <alpha-value>)',
          200: 'rgb(var(--medical-200) / <alpha-value>)',
          300: 'rgb(var(--medical-300) / <alpha-value>)',
          400: 'rgb(var(--medical-400) / <alpha-value>)',
          500: 'rgb(var(--medical-500) / <alpha-value>)',
          600: 'rgb(var(--medical-600) / <alpha-value>)',
          700: 'rgb(var(--medical-700) / <alpha-value>)',
          800: 'rgb(var(--medical-800) / <alpha-value>)',
          900: 'rgb(var(--medical-900) / <alpha-value>)',
        },
        'accent': {
          DEFAULT: 'rgb(var(--accent) / <alpha-value>)',
          hover: 'rgb(var(--accent-hover) / <alpha-value>)',
        },
        'surface': 'rgb(var(--surface) / <alpha-value>)',
        'strong': 'rgb(var(--strong) / <alpha-value>)',
        'overlay': 'rgb(var(--overlay) / <alpha-value>)',
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', '"Liberation Mono"', '"Courier New"', 'monospace', '"PingFang SC"', '"Microsoft YaHei"'],
        sans: [
          '"Noto Sans SC"',
          '"PingFang SC"',
          '"Hiragino Sans GB"',
          '"Microsoft YaHei"',
          '"微软雅黑"',
          'system-ui',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          '"Helvetica Neue"',
          'Arial',
          'sans-serif'
        ],
      }
    },
  },
  plugins: [],
}
