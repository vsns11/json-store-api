package com.nest.jsonstore.profile.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * What a user chose and typed: a template id per group, and a value per field. The server composes
 * the stored inputs from this; it never accepts the composed inputs themselves.
 */
public record TemplateRequest(Map<String, String> selection, Map<String, JsonNode> values) {
}
