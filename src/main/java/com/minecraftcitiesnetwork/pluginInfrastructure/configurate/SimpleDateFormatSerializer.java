package com.minecraftcitiesnetwork.pluginInfrastructure.configurate;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;

/**
 * A Configurate {@link TypeSerializer} that reads and writes a {@link SimpleDateFormat} as its
 * pattern string. Use the shared {@link #INSTANCE}.
 *
 * <p>Deserialization rejects a null node value and surfaces an invalid pattern as a
 * {@link SerializationException}; serialization writes {@link SimpleDateFormat#toPattern()}.
 *
 * <p><strong>Thread-safety:</strong> the serializer itself is stateless and safe to share. Note that
 * each {@link SimpleDateFormat} it produces is <em>not</em> thread-safe and must not be shared across
 * threads — see {@code DateFormatter} for the same caveat when formatting.
 */
public final class SimpleDateFormatSerializer implements TypeSerializer<SimpleDateFormat> {

    public static final SimpleDateFormatSerializer INSTANCE = new SimpleDateFormatSerializer();

    private SimpleDateFormatSerializer() {
    }

    @Override
    public SimpleDateFormat deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String pattern = node.getString();
        if (pattern == null) {
            throw new SerializationException("date-format pattern cannot be null");
        }
        try {
            return new SimpleDateFormat(pattern);
        } catch (IllegalArgumentException ex) {
            throw new SerializationException("Invalid date-format pattern: " + pattern + ": " + ex.getMessage());
        }
    }

    @Override
    public void serialize(Type type, @Nullable SimpleDateFormat obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(String.class, null);
            return;
        }
        node.set(String.class, obj.toPattern());
    }
}
