{{/* Release-scoped name, so two installs in one namespace do not collide. */}}
{{- define "json-store-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "json-store-api.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "json-store-api.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "json-store-api.labels" -}}
app.kubernetes.io/name: {{ include "json-store-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/component: api
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "json-store-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "json-store-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* The secret to read credentials from: an existing one, or the chart's own. */}}
{{- define "json-store-api.secretName" -}}
{{- .Values.existingSecret | default (include "json-store-api.fullname" .) -}}
{{- end -}}
