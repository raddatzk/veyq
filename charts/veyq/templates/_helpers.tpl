{{/*
Fixed object names, NOT release-prefixed. The backend's projected tenant HTTPRoutes route to a Service LITERALLY
named `veyq-app` (veyq.tenant.ingress.kubernetes.service-name's default) and attach to a Gateway literally named
`veyq` (…gateway-name's default), so both names are part of the backend's contract and a release prefix here
would have to be threaded back into that config. One release per
namespace is the shape anyway (values.yaml, top), so the prefix would buy nothing.
*/}}

{{- define "veyq.labels" -}}
app.kubernetes.io/name: veyq
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{/* Helm's own value — never the backend's ownership value, which the sink lists by and deletes over. */}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{- define "veyq.selector" -}}
app.kubernetes.io/name: veyq
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
The `restricted` PodSecurity standard's container half, identical on every container this chart runs, plus a
read-only root (ADR 0071 §1): what a container still writes is a claim or an `emptyDir` of its own pod, each the
scratch path measured by running its image read-only under compose.
*/}}
{{- define "veyq.containerSecurityContext" -}}
allowPrivilegeEscalation: false
capabilities:
  drop: ["ALL"]
readOnlyRootFilesystem: true
runAsNonRoot: true
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{- define "veyq.secretName" -}}
{{- if .Values.secrets.existingSecret }}{{ .Values.secrets.existingSecret }}{{ else }}veyq{{ end -}}
{{- end }}

{{- define "veyq.appImage" -}}
{{ required "image.repository is required (e.g. ghcr.io/raddatzk/veyq)" .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}{{ with .Values.image.digest }}@{{ . }}{{ end }}
{{- end }}

{{- define "veyq.adminImage" -}}
{{ required "admin.image.repository is required (e.g. ghcr.io/raddatzk/veyq-admin)" .Values.admin.image.repository }}:{{ .Values.admin.image.tag | default .Chart.AppVersion }}{{ with .Values.admin.image.digest }}@{{ . }}{{ end }}
{{- end }}

{{/*
Where a chart-owned HTTPRoute attaches — the same rule the backend applies to the routes it projects
(KubernetesGatewaySink.parentFor), so the operator decides once and the bootstrap domain, the store host and the
tenant domains all follow one TLS mode: `none` the Gateway's plain-HTTP listener `http`, `shared` its one
hostname-less HTTPS listener `https`, `per-domain` the HTTPS listener this host has on the Gateway (`listener`).
The names `http` and `https` are the sink's constants; scripts/chart_meta_check.py reads them from there.
Usage: {{ include "veyq.routeParent" (dict "root" . "listener" "https-app") }}
*/}}
{{- define "veyq.routeParent" -}}
{{- $mode := .root.Values.gateway.tls.mode -}}
parentRefs:
  - group: gateway.networking.k8s.io
    kind: Gateway
    name: veyq
    sectionName: {{ if eq $mode "none" }}http{{ else if eq $mode "shared" }}https{{ else }}{{ .listener }}{{ end }}
{{- end }}

{{/*
The chart's own hosts, one per HTTPS listener under `per-domain`: [listener name, hostname, certificate (= the
route's own object name, exactly as the backend names a tenant's)]. One list, read by gateway.yaml for the
listeners and by every route for its parent, so a host cannot have a route without a listener or the reverse.
*/}}
{{- define "veyq.chartHosts" -}}
- [https-app, {{ required "domain is required" .Values.domain | quote }}, veyq-app]
- [https-store, {{ required "storeDomain is required" .Values.storeDomain | quote }}, veyq-garage]
{{- with .Values.admin.domain }}
- [https-admin, {{ . | quote }}, veyq-admin]
{{- end }}
{{- end }}

{{/*
The annotation map the chart's Gateway and every tenant ListenerSet receive — the operator's
`gateway.annotations`, plus, when the ACME opt-in is on, `cert-manager.io/issuer: veyq-acme` and
`acme.cert-manager.io/http01-parentreffallback: "true"` (the latter sends each ListenerSet's HTTP-01 challenge
route to the Gateway's one port-80 listener; ADR 0064). It has TWO consumers and both must read it from here: the
Gateway's metadata in gateway.yaml, and app-configmap.yaml, which hands the same map to the backend for every
ListenerSet it projects at runtime. A merge done at only one of them is a chart whose own hosts get certificates
while every tenant domain silently gets none (scripts/chart_meta_check.py (ix) and (x) assert the two apart). An
HTTPRoute carries no certificate and gets none of it.
Returned as YAML because `include` can only return a string; `{}` when empty, so a consumer's `with`/`range`
over `fromYaml` sees an empty map. The merge copies — `set` on .Values itself would leak into every later read.
*/}}
{{- define "veyq.gatewayAnnotations" -}}
{{- $annotations := deepCopy (.Values.gateway.annotations | default dict) -}}
{{- if (.Values.gateway.tls.acme | default dict).enabled -}}
{{- $_ := set $annotations "cert-manager.io/issuer" "veyq-acme" -}}
{{- $_ := set $annotations "acme.cert-manager.io/http01-parentreffallback" "true" -}}
{{- end -}}
{{- toYaml $annotations -}}
{{- end }}
