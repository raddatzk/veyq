import { serverState } from './serverState'

export function useServerState() {
  function setSetupCompleted(value: boolean) {
    serverState.setupCompleted = value
  }

  function setUnreachable(value: boolean) {
    serverState.unreachable = value
  }

  function setSuspended(value: boolean) {
    serverState.suspended = value
  }

  return { serverState, setSetupCompleted, setUnreachable, setSuspended }
}
