package net.democracrycraft.pluginInfrastructure.configurate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * An in-memory store of MiniMessage-formatted messages keyed by dotted path, with helpers that
 * resolve them into Adventure {@link Component}s.
 *
 * <p>Messages are stored as their raw MiniMessage strings and deserialized on demand. A message
 * stored under the key {@code prefix}, if present, is exposed to every other message as a
 * {@code <prefix>} placeholder so a shared prefix can be interpolated.
 *
 * <p>Messages load from and save to a Configurate {@link ConfigurationNode}: nested nodes flatten to
 * dotted keys (e.g. {@code errors.no-permission}), and list nodes join with newlines into a single
 * multi-line message.
 *
 * <p><strong>Thread-safety:</strong> backed by a {@link ConcurrentHashMap}, so concurrent reads and
 * {@link #setMessage(String, String) writes} are safe. The Adventure serializers used here are
 * themselves thread-safe singletons.
 */
public class MessageContainer {

    private final Map<String, String> rawMessages = new ConcurrentHashMap<>();

    /**
     * @return a resolver binding {@code <prefix>} to the deserialized {@code prefix} message, or to
     *         {@link Component#empty()} when no prefix has been set
     */
    private @NotNull TagResolver prefixResolver() {
        String raw = this.rawMessages.get("prefix");
        if (raw == null) {
            return Placeholder.component("prefix", Component.empty());
        }
        Component prefix = MiniMessage.miniMessage().deserialize(raw);
        return Placeholder.component("prefix", prefix);
    }

    /**
     * Deserializes an arbitrary MiniMessage string, exposing the {@code <prefix>} placeholder.
     *
     * @param raw the MiniMessage source
     * @return the rendered component
     */
    public @NotNull Component deserializeRaw(@NotNull String raw) {
        return MiniMessage.miniMessage().deserialize(raw, prefixResolver());
    }

    /**
     * Renders the message for {@code key} and strips it to plain text.
     *
     * @param key the message key
     * @return the plain-text rendering, or the key itself when no message is stored
     */
    public @NotNull String plaintextMessageFor(@NotNull String key) {
        return PlainTextComponentSerializer.plainText().serialize(messageFor(key));
    }

    /**
     * Renders the message for {@code key}, exposing the {@code <prefix>} placeholder.
     *
     * @param key the message key
     * @return the rendered component, or {@code Component.text(key)} when no message is stored
     */
    public @NotNull Component messageFor(@NotNull String key) {
        String raw = this.rawMessages.get(key);
        if (raw == null) {
            return Component.text(key);
        }
        return MiniMessage.miniMessage().deserialize(raw, prefixResolver());
    }

    /**
     * Renders the message for {@code key} with additional tag resolvers, alongside the
     * {@code <prefix>} placeholder.
     *
     * @param key       the message key
     * @param resolvers extra resolvers (e.g. {@code Placeholder}s) applied during deserialization
     * @return the rendered component, or {@code Component.text(key)} when no message is stored
     */
    public @NotNull Component messageFor(@NotNull String key, @NotNull TagResolver... resolvers) {
        String raw = this.rawMessages.get(key);
        if (raw == null) {
            return Component.text(key);
        }
        TagResolver[] combined = Arrays.copyOf(resolvers, resolvers.length + 1);
        combined[resolvers.length] = prefixResolver();
        return MiniMessage.miniMessage().deserialize(raw, combined);
    }

    /**
     * @param key the message key
     * @return the stored raw MiniMessage string, or the key itself when none is stored
     */
    public @NotNull String miniMessageFormattedFor(@NotNull String key) {
        return this.rawMessages.getOrDefault(key, key);
    }

    /**
     * Stores (or replaces) the raw MiniMessage string for a key.
     *
     * @param key            the message key
     * @param rawMiniMessage the raw MiniMessage source to store
     */
    public void setMessage(@NotNull String key, @NotNull String rawMiniMessage) {
        this.rawMessages.put(key, rawMiniMessage);
    }

    /**
     * Removes all stored messages.
     */
    public void clear() {
        this.rawMessages.clear();
    }

    /**
     * Loads messages from a configuration tree, merging them over any already stored. Nested nodes
     * flatten to dotted keys; list nodes join with newlines.
     *
     * @param root the root node to read
     * @throws ConfigurateException if the tree cannot be read
     */
    public void load(@NotNull ConfigurationNode root) throws ConfigurateException {
        Map<String, String> temp = new HashMap<>();
        loadInto("", root, temp);
        this.rawMessages.putAll(temp);
    }

    /**
     * Writes the stored messages into a configuration tree, expanding dotted keys back into nested
     * nodes.
     *
     * @param root the root node to populate
     * @throws ConfigurateException if a value cannot be written
     */
    public void save(@NotNull ConfigurationNode root) throws ConfigurateException {
        for (Map.Entry<String, String> entry : this.rawMessages.entrySet()) {
            root.node((Object[]) entry.getKey().split(java.util.regex.Pattern.quote("."))).set(entry.getValue());
        }
    }

    private void loadInto(@NotNull String path,
                          @NotNull ConfigurationNode root,
                          @NotNull Map<String, String> temp) throws ConfigurateException {
        if (!root.empty()) {
            if (root.isList()) {
                List<String> strings = root.getList(String.class, Collections.emptyList());
                String joined = strings.stream()
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                if (!joined.isEmpty()) {
                    temp.put(path, joined);
                }
            } else {
                String raw = root.getString();
                if (raw != null) {
                    temp.put(path, raw);
                }
            }
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            String key = entry.getKey().toString();
            ConfigurationNode node = entry.getValue();
            String newPath = path.isEmpty() ? key : path + "." + key;
            loadInto(newPath, node, temp);
        }
    }
}
