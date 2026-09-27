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
    '  BACKEND_URL          the backend\'s admin listener (default http://app:8090)',
  ].join('\n')
}

/**
 * Split `tenant create --name x` into the command words and the remaining argv.
 *
 * Command words are the leading arguments that are not flags — which is why this is done by hand instead of by
 * `parseArgs`: the flags a command accepts are only knowable *after* the command is known, and parsing twice
 * with the right table beats parsing once with the union of every command's flags (which would happily accept
 * `--domain` on `release`).
 */
function splitCommand(argv: string[]): { name: string; rest: string[] } {
  let i = 0
  while (i < argv.length && !argv[i].startsWith('-')) i += 1
  return { name: argv.slice(0, i).join(' '), rest: argv.slice(i) }
}

/** Parse `rest` against exactly this command's flags. Throws {@link CliError} for an unknown or missing one. */
function readFlags(command: Command, rest: string[]): Record<string, string> {
  const options = Object.fromEntries(Object.keys(command.flags).map((f) => [f, { type: 'string' as const }]))
  let values: Record<string, string | undefined>
  try {
    values = parseArgs({ args: rest, options, strict: true, allowPositionals: false }).values
  } catch (e) {
    // parseArgs' own message names the offending token ("Unknown option '--foo'"), which is the useful half.
    throw new CliError(`${(e as Error).message}\nusage: ${NAME} ${command.usage}`)
  }
  for (const [flag, spec] of Object.entries(command.flags)) {
    if (spec.required && !values[flag]) throw new CliError(`--${flag} is required (${spec.describe})\nusage: ${NAME} ${command.usage}`)
  }
  return values as Record<string, string>
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
    out(renderResult(await command.run(flags)))
    return 0
  } catch (e) {
    err(`${NAME}: ${await describeFailure(e)}`)
    return 1
  }
}
