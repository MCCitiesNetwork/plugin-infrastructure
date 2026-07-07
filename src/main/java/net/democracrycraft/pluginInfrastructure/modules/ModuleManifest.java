package net.democracrycraft.pluginInfrastructure.modules;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record ModuleManifest(
        @NotNull String moduleName,
        @NotNull String entryClass,
        @NotNull String author,
        @NotNull String expectedPluginClass,
        boolean reloadable
) {
}
