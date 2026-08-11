package com.agenttrail.loop.skills;

import java.util.Objects;

/** Registry-facing skill metadata; prompt rendering deliberately remains name + description only. */
public record SkillPack(Skill skill, SkillMetadata metadata) {
    public SkillPack {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(metadata, "metadata");
    }
}
