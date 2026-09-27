{{/*
Fixed object names, NOT release-prefixed. The backend's projected tenant Ingress objects route to a Service
LITERALLY named `veyq-app` (veyq.tenant.ingress.kubernetes.service-name's default), so the name is part of the
backend's contract and a release prefix here would have to be threaded back into that config. One release per
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
The one TLS rule for every Ingress this chart owns — the same three modes the backend applies to the objects
it projects, so the operator decides once and the bootstrap domain, the store host and the tenant domains all
carry the same shape. `per-domain` names the object's own name, exactly as the backend does.
Usage: {{ include "veyq.ingressTls" (dict "root" . "host" <host> "name" <object name>) }}
*/}}
{{- define "veyq.ingressTls" -}}
{{- $mode := .root.Values.ingress.tls.mode -}}
{{- if ne $mode "none" }}
  tls:
    - hosts: [{{ .host | quote }}]
      secretName: {{ if eq $mode "shared" }}{{ .root.Values.ingress.tls.secretName | quote }}{{ else }}{{ .name | quote }}{{ end }}
{{- end }}
{{- end }}

{{/*
The annotation map every Ingress receives — the operator's `ingress.annotations`, plus the ACME opt-in's
`cert-manager.io/issuer` when it is on. It has TWO consumers and both must read it from here: the chart's own
Ingress metadata below, and app-configmap.yaml, which hands the same map to the backend for every tenant
Ingress it projects at runtime. A merge done at only one of them is a chart whose own hosts get certificates
while every tenant domain silently gets none (scripts/chart_meta_check.py (ix) and (x) assert the two apart).
Returned as YAML because `include` can only return a string; `{}` when empty, so a consumer's `with`/`range`
over `fromYaml` sees an empty map. The merge copies — `set` on .Values itself would leak into every later read.
*/}}
{{- define "veyq.ingressAnnotations" -}}
{{- $annotations := deepCopy (.Values.ingress.annotations | default dict) -}}
{{- if (.Values.ingress.tls.acme | default dict).enabled -}}
{{- $_ := set $annotations "cert-manager.io/issuer" "veyq-acme" -}}
{{- end -}}
{{- toYaml $annotations -}}
{{- end }}

{{- define "veyq.ingressMetadata" -}}
{{- with (include "veyq.ingressAnnotations" . | fromYaml) }}
  annotations:
    {{- toYaml . | nindent 4 }}
{{- end }}
{{- end }}
