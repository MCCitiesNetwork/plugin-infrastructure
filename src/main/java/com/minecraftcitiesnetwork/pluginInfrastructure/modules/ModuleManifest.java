package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;

@ConfigSerializable
public record ModuleManifest(
        @Required @NotNull String moduleName,
        @Required @NotNull String entryClass,
        @Required @NotNull String author,
        @Required @NotNull String expectedPluginClass,
        boolean reloadable
) {
}
