// `veyq-admin` — the Server-Owner CLI, shipped in the same image as the admin UI (ADR 0065). It exists so
// tenant administration is scriptable, and it is a thin one on purpose: parse arguments, sign in, call the
// same facade the UI calls, print the result, exit 0 or 1.
//
// `node:util.parseArgs`, not commander/yargs: ten subcommands over flat string flags is comfortably inside
// what Node core handles, and a framework here would be a dependency bought for nothing.
import { parseArgs } from 'node:util'
import { COMMANDS, type Command } from './commands'
import { signIn } from './adminSession'
import { CliError, describeFailure, renderResult } from './output'

const NAME = 'veyq-admin'

/** The full `--help` text: every command's usage line and what it does. Generated from the table, so a
 *  command that exists is a command that is documented — the two cannot drift apart. */
export function usage(): string {
  const width = Math.max(...Object.values(COMMANDS).map((c) => c.usage.length))
  const lines = Object.values(COMMANDS).map((c) => `  ${NAME} ${c.usage.padEnd(width)}  ${c.summary}`)
  return [
    `${NAME} — Server-Owner administration for a veyq instance.`,
    '',
    'Commands:',
    ...lines,
    '',
    'Environment:',
    '  VEYQ_ADMIN_EMAIL     Server-Owner account (required)',
    '  VEYQ_ADMIN_PASSWORD  its password (required) — used once per invocation, never stored',
    '  VEYQ_STORE_SECRET_KEY, VEYQ_STORE_GARAGE_ADMIN_TOKEN  the secrets `store set` seals (optional: unset keeps the stored one)',
    '  BACKEND_URL          the backend\'s admin listener (default http://app:8090)',
    '  LC_ALL, LANG         the language `capabilities` prints the entry texts in (de* German, else English; --lang overrides)',
  ].join('\n')
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
): Promise<number> {
  if (argv.length === 0 || argv[0] === '--help' || argv[0] === '-h') {
    out(usage())
    return argv.length === 0 ? 1 : 0
  }
  const { name, rest } = splitCommand(argv)
  const command = COMMANDS[name]
  if (!command) {
    err(`${NAME}: unknown command '${name || argv[0]}'`)
    err(usage())
    return 1
  }
  try {
    const flags = readFlags(command, rest)
    await signIn(env)
    const result = await command.run(flags, env)
    out(command.render ? command.render(result, flags, env) : renderResult(result))
    return command.exitCode?.(result) ?? 0
  } catch (e) {
    err(`${NAME}: ${await describeFailure(e)}`)
    return 1
  }
}
