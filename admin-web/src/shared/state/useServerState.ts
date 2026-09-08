import { serverState } from './serverState'

export function useServerState() {
  function setSetupCompleted(value: boolean) {
    serverState.setupCompleted = value
  }

  function setUnreachable(value: boolean) {
    serverState.unreachable = value
  }

  return { serverState, setSetupCompleted, setUnreachable }
}
