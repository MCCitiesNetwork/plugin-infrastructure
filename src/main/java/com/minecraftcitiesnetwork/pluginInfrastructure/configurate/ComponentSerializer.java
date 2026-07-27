package com.minecraftcitiesnetwork.pluginInfrastructure.configurate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * A Configurate {@link TypeSerializer} that reads and writes Adventure {@link Component}s as strings
 * via a supplied string codec. Two ready-made instances are provided: {@link #MINI_MESSAGE} and
 * {@link #LEGACY_AMPERSAND}.
 *
 * <p>A value serializes to a single string node and deserializes back from one; a null or absent
 * node yields {@code null}.
 *
 * <p><strong>Thread-safety:</strong> instances are immutable and safe to share, provided the
 * supplied codec functions are themselves thread-safe (the Adventure serializers backing the
 * provided instances are).
 */
public class ComponentSerializer implements TypeSerializer<Component> {

    /** Serializes components to and from MiniMessage strings. */
    public static final ComponentSerializer MINI_MESSAGE = new ComponentSerializer(
            MiniMessage.miniMessage()::deserialize,
            MiniMessage.miniMessage()::serialize);

    /** Serializes components to and from legacy strings using the {@code &} colour-code character. */
    public static final ComponentSerializer LEGACY_AMPERSAND = new ComponentSerializer(
            LegacyComponentSerializer.legacyAmpersand()::deserialize,
            LegacyComponentSerializer.legacyAmpersand()::serialize);

    private final Function<String, Component> deserializer;
    private final Function<Component, String> serializer;

    /**
     * @param deserializer converts a stored string into a component
     * @param serializer   converts a component into its stored string form
     */
    public ComponentSerializer(
            @NotNull Function<String, Component> deserializer,
            @NotNull Function<Component, String> serializer
    ) {
        this.deserializer = deserializer;
        this.serializer = serializer;
    }

    @Override
    public @Nullable Component deserialize(Type type, ConfigurationNode node) throws SerializationException {
        if (node.isNull()) {
            return null;
        }
        String s = node.getString();
        if (s == null) {
            return null;
        }
        try {
            return this.deserializer.apply(s);
        } catch (Exception ex) {
            throw new SerializationException(ex);
        }
    }

    @Override
    public void serialize(Type type, @Nullable Component obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(String.class, null);
            return;
        }
        String item = this.serializer.apply(obj);
        node.set(String.class, item);
    }
}
