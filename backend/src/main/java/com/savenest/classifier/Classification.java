package com.savenest.classifier;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Result of rule-based classification. Confidence expresses rule certainty,
 * not a statistical probability.
 */
public record Classification(
        GroupId groupId,
        String platform,
        String contentType,
        String subtype,
        String resourceId,
        Confidence confidence,
        Source source,
        String ruleId,
        int ruleVersion,
        String reason) {

    public enum Confidence {
        HIGH, MEDIUM, LOW;

        @JsonValue
        public String json() { return name().toLowerCase(); }
    }

    public enum Source {
        USER_OVERRIDE, URL_RULE, METADATA, DOMAIN_DEFAULT, FALLBACK;

        @JsonValue
        public String json() { return name().toLowerCase(); }
    }

    /** Applies a user group override; it always takes precedence over detection. */
    public Classification withUserOverride(GroupId override) {
        if (override == null) {
            return this;
        }
        return new Classification(override, platform, contentType, subtype, resourceId,
                Confidence.HIGH, Source.USER_OVERRIDE, "user.override", ruleVersion,
                "Group manually set by user (detected: " + groupId.id() + ")");
    }
}
