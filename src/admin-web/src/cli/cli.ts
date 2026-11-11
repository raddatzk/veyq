// `veyq-admin` — the Server-Owner CLI, shipped in the same image as the admin UI (ADR 0065). It exists so
// tenant administration is scriptable, and it is a thin one on purpose: parse arguments, sign in, call the
// same facade the UI calls, print the result, exit 0 or 1.
//
// `node:util.parseArgs`, not commander/yargs: ten subcommands over flat string flags is comfortably inside
// what Node core handles, and a framework here would be a dependency bought for nothing.
import { parseArgs } from 'node:util'
import { COMMANDS, GROUPS, type Command } from './commands'
import { signIn } from './adminSession'
import { CliError, describeFailure, renderResult } from './output'

const NAME = 'veyq-admin'

/** The width help is wrapped to when the caller names none (not a terminal) — and the widest it ever gets, since a
 *  line across a wide terminal is as hard to read as one that wraps. */
const DEFAULT_WIDTH = 80
const MAX_WIDTH = 100

/** `text` broken at spaces into lines of at most `width` characters, the first starting with `lead`, every other with
 *  `indent` — both counted in the width. A word longer than the line stays whole. */
function wrap(text: string | string[], width: number, indent = '', lead = ''): string {
  const lines: string[] = []
  let line = lead
  let words = 0
  for (const word of typeof text === 'string' ? text.split(/\s+/).filter(Boolean) : text) {
    if (words && line.length + 1 + word.length > width) {
      lines.push(line)
      line = indent + word
    } else line = words ? `${line} ${word}` : line + word
    words += 1
  }
  lines.push(line)
  return lines.join('\n')
}

/** Two columns: the left padded to the widest entry (at most 36), the right wrapped under itself. A left entry wider
 *  than that takes its line alone, and its text starts on the next. */
function table(rows: [string, string][], width: number): string {
  const left = Math.min(36, Math.max(...rows.map(([l]) => l.length)))
  const pad = ' '.repeat(2 + left + 2)
  return rows
    .map(([l, r]) => {
      return l.length > left ? `  ${l}\n${wrap(r, width, pad, pad)}` : wrap(r, width, pad, `  ${l.padEnd(left)}  `)
    })
    .join('\n')
}

/** A usage line's words, an optional `[…]` group kept as one — so a line breaks between flags, never inside one. */
function usageWords(usage: string): string[] {
  const words: string[] = []
  let depth = 0
  let word = ''
  for (const ch of usage) {
    if (ch === ' ' && depth === 0) {
      if (word) words.push(word)
      word = ''
      continue
    }
    if (ch === '[') depth += 1
    if (ch === ']') depth -= 1
    word += ch
  }
  if (word) words.push(word)
  return words
}

const groupOf = (name: string): string => name.split(' ')[0]
const commandsIn = (group: string): [string, Command][] => Object.entries(COMMANDS).filter(([n]) => groupOf(n) === group)
/** How a command is listed under its group: its words after the group's (the group's own command as itself), and its
 *  operand, which is part of typing it. */
const listed = ([name, c]: [string, Command]): [string, string] => {
  const words = name.includes(' ') ? name.slice(name.indexOf(' ') + 1) : name
  return [c.operand ? `${words} <${c.operand.name}>` : words, c.summary]
}

/** The top of the help: the groups, one line each, and the sign-in it needs. What one group or one command takes is
 *  one level down, so this stays a screen. */
export function usage(width = DEFAULT_WIDTH): string {
  return [
    `${NAME} — Server-Owner administration for a veyq server.`,
    '',
    `Usage: ${NAME} <group> <command> [flags]`,
    '',
    'Groups:',
    table(Object.entries(GROUPS), width),
    '',
    'Help:',
    table(
      [
        [`${NAME} <group> --help`, 'a group’s commands'],
        [`${NAME} <group> <command> --help`, 'one command in full, with its flags'],
      ],
      width,
    ),
    '',
    'Environment:',
    table(
      [
        ['VEYQ_ADMIN_EMAIL', 'the Server Owner’s account (required)'],
        ['VEYQ_ADMIN_PASSWORD', 'its password (required): used once per call, never stored'],
        ['BACKEND_URL', 'the backend’s admin listener (default http://app:8090)'],
      ],
      width,
    ),
  ].join('\n')
}

/** One group's commands, one line each. A group that is a single command is that command's help; a group whose
 *  name is also a command (`release`) adds that command's help below the list, the only place it can be asked for. */
export function groupHelp(group: string, width = DEFAULT_WIDTH): string {
  const commands = commandsIn(group)
  if (commands.length === 1) return commandHelp(commands[0][0], width)
  const own = COMMANDS[group] ? ['', commandHelp(group, width)] : []
  return [
    wrap(`${NAME} ${group} — ${GROUPS[group]}`, width, '  '),
    '',
    `Commands — ${NAME} ${group} <command>:`,
    table(commands.map(listed), width),
    '',
    wrap(`${NAME} ${group} <command> --help shows one command in full, with its flags.`, width),
    ...own,
  ].join('\n')
}

/** One command in full: how it is written, everything it does, and each flag and operand with what it takes. */
export function commandHelp(name: string, width = DEFAULT_WIDTH): string {
  const c = COMMANDS[name]
  // The value's placeholder as the usage line spells it (`--tenant-id <id>`), so the two never read differently.
  const placeholder = (f: string) => new RegExp(`--${f} (<[^>]+>)`).exec(c.usage)?.[1] ?? '<value>'
  const flags = Object.entries(c.flags).map(([f, spec]): [string, string] => [
    spec.switch ? `--${f}` : `--${f} ${placeholder(f)}`,
    [spec.describe + (spec.required ? ' (required)' : ''), spec.oneOf ? `one of ${spec.oneOf.join(', ')}` : ''].filter(Boolean).join('; '),
  ])
  return [
    // A `[…]` group wider than a line is broken at its spaces after all — a narrow terminal still gets every word.
    wrap(['Usage:', NAME, ...usageWords(c.usage).flatMap((w) => (w.length > width - 4 ? w.split(' ') : [w]))], width, '    '),
    '',
    wrap(c.details ?? c.summary, width),
    ...(c.operand ? ['', 'Argument:', table([[`<${c.operand.name}>`, c.operand.describe]], width)] : []),
    ...(flags.length ? ['', 'Flags:', table(flags, width)] : []),
  ].join('\n')
}

/** The number of single-character edits — insert, delete, replace, swap two neighbours — from `a` to `b`. */
function distance(a: string, b: string): number {
  const d = Array.from({ length: a.length + 1 }, (_, i) => Array.from({ length: b.length + 1 }, (_, j) => (i ? (j ? 0 : i) : j)))
  for (let i = 1; i <= a.length; i += 1) {
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1)
    }
  }
  return d[a.length][b.length]
}

/** The command or group `typed` most likely meant — a typo of at most two edits — or undefined. */
function closest(typed: string): string | undefined {
  const [best] = [...Object.keys(COMMANDS), ...Object.keys(GROUPS)]
    .map((n) => [n, distance(typed, n)] as const)
    .filter(([, d]) => d <= 2 && d < typed.length / 2)
    .sort((x, y) => x[1] - y[1])
  return best?.[0]
}

/** What an unknown command prints: what was not understood, the likely typo, and where to look — the group's own
 *  commands when its first word is a group, else a pointer to `--help`, never the whole help. */
function unknownCommand(typed: string, width: number): string {
  const guess = closest(typed)
  const head = `${NAME}: unknown command '${typed}'${guess ? ` — did you mean '${guess}'?` : ''}`
  const group = groupOf(typed)
  if (GROUPS[group]) return [head, '', `${NAME} ${group} <command>:`, table(commandsIn(group).map(listed), width)].join('\n')
  return `${head}\nRun '${NAME} --help' for the groups.`
}

/**
 * Split `tenant create --name x` into the command words and the remaining argv.
 *
 * Command words are the leading arguments that are not flags — which is why this is done by hand instead of by
 * `parseArgs`: the flags a command accepts are only knowable *after* the command is known, and parsing twice
 * with the right table beats parsing once with the union of every command's flags (which would happily accept
 * `--domain` on `release`). A command that takes an operand (`capabilities off <id>`) is matched on its own words,
 * and what follows them stays in the rest; for every other command all leading words are its name, so a stray
 * word still reads as the unknown command it is.
 */
function splitCommand(argv: string[]): { name: string; rest: string[] } {
  let i = 0
  while (i < argv.length && !argv[i].startsWith('-')) i += 1
  const words = argv.slice(0, i).join(' ')
  if (!COMMANDS[words]) {
    for (let n = i - 1; n > 0; n -= 1) {
      const name = argv.slice(0, n).join(' ')
      if (COMMANDS[name]?.operand) return { name, rest: argv.slice(n) }
    }
  }
  return { name: words, rest: argv.slice(i) }
}

/** Parse `rest` against exactly this command's flags. Throws {@link CliError} for an unknown or missing one. */
function readFlags(command: Command, rest: string[]): Record<string, string> {
  const options = Object.fromEntries(
    Object.entries(command.flags).map(([f, spec]) => [f, { type: spec.switch ? ('boolean' as const) : ('string' as const) }]),
  )
  let values: Record<string, string | boolean | undefined>
  let positionals: string[]
  try {
    ;({ values, positionals } = parseArgs({ args: rest, options, strict: true, allowPositionals: command.operand !== undefined }))
  } catch (e) {
    // parseArgs' own message names the offending token ("Unknown option '--foo'"), which is the useful half.
    throw new CliError(`${(e as Error).message}\nusage: ${NAME} ${command.usage}`)
  }
  for (const [flag, spec] of Object.entries(command.flags)) {
    if (spec.required && !values[flag]) throw new CliError(`--${flag} is required (${spec.describe})\nusage: ${NAME} ${command.usage}`)
    const value = values[flag]
    if (spec.oneOf && typeof value === 'string' && !spec.oneOf.includes(value)) {
      throw new CliError(`--${flag} must be one of ${spec.oneOf.join(', ')} (got '${value}')\nusage: ${NAME} ${command.usage}`)
    }
  }
  if (command.operand) {
    const { name, describe } = command.operand
    if (positionals.length !== 1) {
      throw new CliError(`${positionals.length ? `exactly one <${name}> is taken` : `<${name}> is required (${describe})`}\nusage: ${NAME} ${command.usage}`)
    }
    values[name] = positionals[0]
  }
  // A switch given reads as 'true' and absent as missing, so every command sees the one `Record<string, string>`.
  return Object.fromEntries(
    Object.entries(values).filter(([, v]) => v !== undefined && v !== false).map(([k, v]) => [k, String(v)]),
  )
}

/**
 * Run one invocation and resolve to its exit code. Takes argv and env as arguments rather than reading the
 * globals, so the whole surface is testable without a subprocess; `main.ts` is the two lines that supply them.
 */
export async function run(
  argv: string[],
  env: Record<string, string | undefined>,
  out: (s: string) => void = console.log,
  err: (s: string) => void = console.error,
  columns?: number,
): Promise<number> {
  const width = Math.min(MAX_WIDTH, columns && columns >= 40 ? columns : DEFAULT_WIDTH)
  const isHelp = (a: string) => a === '--help' || a === '-h'
  if (argv.length === 0 || isHelp(argv[0])) {
    out(usage(width))
    return argv.length === 0 ? 1 : 0
  }
  const { name, rest } = splitCommand(argv.filter((a) => !isHelp(a)))
  // `--help` anywhere after the words asks for help, never for the command: a flag-less read run by mistake is
  // harmless, a `tenant delete` is not. Help costs no sign-in.
  if (argv.some(isHelp)) {
    if (GROUPS[name]) out(groupHelp(name, width))
    else if (COMMANDS[name]) out(commandHelp(name, width))
    else {
      err(unknownCommand(name || argv[0], width))
      return 1
    }
    return 0
  }
  const command = COMMANDS[name]
  if (!command) {
    // A group alone (`veyq-admin tenant`) asked nothing yet: its commands, and a non-zero exit as for no arguments.
    if (GROUPS[name]) {
      out(groupHelp(name, width))
      return 1
    }
    err(unknownCommand(name || argv[0], width))
    return 1
  }
  try {
    const flags = readFlags(command, rest)
    await signIn(env)
    const result = await command.run(flags, env)
    out(command.render ? command.render(result, flags, env) : renderResult(result))
    const warning = command.warning?.(result)
    if (warning) err(`${NAME}: warning: ${warning}`)
    return command.exitCode?.(result) ?? 0
  } catch (e) {
    err(`${NAME}: ${await describeFailure(e)}`)
    return 1
  }
}
