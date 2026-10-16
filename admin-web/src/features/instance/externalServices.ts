import { instanceApi } from '../../api/client'
import type { InstanceExternalService } from '../../api/generated/instance'

export type { InstanceExternalService } from '../../api/generated/instance'

/**
 * The outside services a member's browser calls (uc.admin.configure-external-services, ADR 0088 decision 7, ADR 0089):
 * the map tiles, the geocoder and the breached-password check, each keyed by its register entry. Server-Owner only. A
 * service with no address is not used at all: its entry is not active and the served policy leaves its origin out.
 */
export function listExternalServices(): Promise<InstanceExternalService[]> {
  return instanceApi().listInstanceExternalServices()
}

/** Configure one service's address (and, for the map tiles, the attribution); acts at once. Refusals: 400
 *  `invalid_request` (not an http(s) URL, a tile template without `{z}`, `{x}`, `{y}`, an attribution where none is
 *  taken), 404 `service_not_found`. */
export function setExternalService(id: string, url: string, attribution: string | null): Promise<InstanceExternalService> {
  return instanceApi().setInstanceExternalService({ capabilityId: id, instanceExternalServiceUpdate: { url, attribution } })
}

/** Unconfigure one service: no client is told an address, and the policy leaves its origin out. */
export function clearExternalService(id: string): Promise<InstanceExternalService> {
  return instanceApi().clearInstanceExternalService({ capabilityId: id })
}
