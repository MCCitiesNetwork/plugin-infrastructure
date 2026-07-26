package com.minecraftcitiesnetwork.pluginInfrastructure.configurate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
 * <h2>Security rule: runtime values are never parsed as markup</h2>
 * <strong>Every runtime value substituted into a message MUST go through
 * {@link #value(String, String)}</strong> (which delegates to
 * {@link Placeholder#unparsed(String, String)}). MiniMessage's {@code Placeholder.parsed} re-parses
 * its value <em>as markup</em>, which turns any user- or operator-supplied string into executable
 * tags.
 *
 * <p>That is not a theoretical concern for a plugin library. The typical shape is: some text is
 * authored by a player or a staff member, persisted, and later re-rendered to a <em>different</em>
 * viewer — a punishment reason, a shop description, a mail subject, a region greeting. Rendered with
 * {@code parsed}, a stored {@code <click:run_command:'/op evil'>} runs that command as whoever
 * clicks it. Stored privilege escalation, from one wrong resolver.
 *
 * <p>When a slot genuinely needs formatting, build a {@link Component} and inject it with
 * {@link #markup(String, ComponentLike)} — typically another message from this container:
 * <pre>{@code
 * container.messageFor("player.banned",
 *         MessageContainer.value("player", name),
 *         MessageContainer.markup("time", container.messageFor("time.permanent")));
 * }</pre>
 * A {@code Component} is already-structured data: nothing inside it is re-parsed, so a hostile
 * string cannot become a tag.
 *
 * <p>These two factories are deliberately the only substitution API here, and there is deliberately
 * no "render this arbitrary string as MiniMessage" helper — building a template by concatenating
 * values into {@link #miniMessageFormattedFor(String)} and deserializing it is the same hole with
 * extra steps. A call site that stays inside this API never imports {@code Placeholder}, so
 * reintroducing {@code Placeholder.parsed} shows up in review as a new, unexplained import.
 *
 * <p><strong>Thread-safety:</strong> backed by a {@link ConcurrentHashMap}, so concurrent reads and
 * {@link #setMessage(String, String) writes} are safe, and {@link #load(ConfigurationNode)} never
 * leaves the map observably empty. The Adventure serializers used here are themselves thread-safe
 * singletons.
 */
public class MessageContainer {

    /** Splits a dotted key into node path elements; the '.' is a literal, not a regex wildcard. */
    private static final Pattern KEY_SEPARATOR = Pattern.compile(Pattern.quote("."));

    /** The resolver used when no prefix is configured; immutable, so one instance serves everyone. */
    private static final TagResolver EMPTY_PREFIX = Placeholder.component("prefix", Component.empty());

    private final Map<String, String> rawMessages = new ConcurrentHashMap<>();

    /**
     * The last prefix parse, kept so that rendering a message does not re-deserialize the prefix
     * template every time. Validated against the current raw string rather than invalidated on
     * reload, so it cannot serve a stale prefix; {@code volatile} because messages are rendered
     * from several threads. A race costs one redundant parse, never a wrong answer.
     */
    private volatile CachedPrefix cachedPrefix;

    /** A parsed prefix together with the raw template it came from. */
    private record CachedPrefix(@NotNull String raw, @NotNull TagResolver resolver) {
    }

    /**
     * A {@link TagResolver} that substitutes a literal, never-parsed value into {@code <name>}.
     * This is the correct choice for player names, reasons, durations, amounts, ids, error text —
     * anything that did not come out of the message file itself.
     *
     * @param name  the tag name to bind, without angle brackets
     * @param value the literal text to insert; markup inside it is <em>not</em> interpreted
     * @return a resolver binding {@code <name>} to {@code value} as plain text
     */
    public static @NotNull TagResolver value(@NotNull String name, @NotNull String value) {
        return Placeholder.unparsed(name, value);
    }

    /**
     * A {@link TagResolver} that substitutes an already-built {@link Component} into {@code <name>}.
     * This is the <em>only</em> sanctioned way to get formatting into a slot: the component is
     * inserted as structured data and is never re-parsed as markup.
     *
     * @param name      the tag name to bind, without angle brackets
     * @param component the component to insert
     * @return a resolver binding {@code <name>} to {@code component}
     */
    public static @NotNull TagResolver markup(@NotNull String name, @NotNull ComponentLike component) {
        return Placeholder.component(name, component);
    }

    /**
     * @return a resolver binding {@code <prefix>} to the deserialized {@code prefix} message, or to
     *         {@link Component#empty()} when no prefix has been set or the stored one will not parse
     */
    private @NotNull TagResolver prefixResolver() {
        String raw = this.rawMessages.get("prefix");
        if (raw == null) {
            return EMPTY_PREFIX;
        }
        CachedPrefix cached = this.cachedPrefix;
        if (cached != null && cached.raw().equals(raw)) {
            return cached.resolver();
        }
        // The prefix is itself a template out of the message file, not runtime data, so parsing it
        // as markup is correct here.
        TagResolver resolver;
        try {
            resolver = Placeholder.component("prefix", MiniMessage.miniMessage().deserialize(raw));
        } catch (ParsingException ex) {
            // Unlike a broken message body, which renders as its key, a broken prefix renders as
            // nothing: putting the literal word "prefix" in front of every message in the plugin
            // would be worse than the missing decoration.
            resolver = EMPTY_PREFIX;
        }
        this.cachedPrefix = new CachedPrefix(raw, resolver);
        return resolver;
    }

    /**
     * Renders the message for {@code key}, optionally with additional tag resolvers, alongside the
     * {@code <prefix>} placeholder.
     *
     * <p>Never throws. An unknown key renders as {@code Component.text(key)} — visibly wrong in
     * game, which is what you want from a localisation miss rather than a stack trace on the main
     * thread. A <em>malformed</em> template is handled by MiniMessage's default (lenient) instance,
     * which leaves an unrecognised tag as literal text rather than raising; the
     * {@link ParsingException} catch below is a backstop for a stricter instance or a future
     * Adventure that does raise, and falls back to the same {@code Component.text(key)}.
     *
     * @param key       the message key
     * @param resolvers extra resolvers, built with {@link #value(String, String)} or
     *                  {@link #markup(String, ComponentLike)}; a resolver supplied here takes
     *                  precedence over the built-in {@code <prefix>}
     * @return the rendered component, or {@code Component.text(key)} when no message is stored
     */
    public @NotNull Component messageFor(@NotNull String key, @NotNull TagResolver... resolvers) {
        String raw = this.rawMessages.get(key);
        if (raw == null) {
            return Component.text(key);
        }
        // Prefix goes first and caller-supplied resolvers after it: with a resolver array,
        // MiniMessage lets a later entry win, so this is what lets a caller override <prefix> for
        // one message without mutating the container.
        TagResolver[] combined = new TagResolver[resolvers.length + 1];
        combined[0] = prefixResolver();
        System.arraycopy(resolvers, 0, combined, 1, resolvers.length);
        try {
            return MiniMessage.miniMessage().deserialize(raw, combined);
        } catch (ParsingException ex) {
            // A malformed operator-edited template. Fail the same visible way a missing key does.
            return Component.text(key);
        }
    }

    /**
     * Renders the message for {@code key} and strips it to plain text — for console logs, item names
     * in APIs that take strings, and other non-Adventure sinks.
     *
     * @param key       the message key
     * @param resolvers extra resolvers, as for {@link #messageFor(String, TagResolver...)}
     * @return the plain-text rendering, or the key itself when no message is stored
     */
    public @NotNull String plaintextMessageFor(@NotNull String key, @NotNull TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(messageFor(key, resolvers));
    }

    /**
     * The raw MiniMessage template stored for {@code key}, or {@code key} itself when none is
     * stored.
     *
     * <p>Do <strong>not</strong> concatenate or {@code String.replace} runtime values into the
     * result and deserialize it — that reintroduces the markup-injection hole described in this
     * class's documentation. Use {@link #messageFor(String, TagResolver...)} with
     * {@link #value(String, String)}. This accessor exists for callers that must hand a raw
     * template to something else (a serializer, another config file, a test assertion).
     *
     * @param key the message key
     * @return the stored raw MiniMessage string, or the key
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
     * Replaces the message set with the contents of {@code root}.
     *
     * <p>The node tree is flattened to dotted keys, so a nested section reads as
     * {@code section.child} and callers can keep their key constants a flat list. List values are
     * joined with newlines, so a multi-line message can be written as a YAML list.
     *
     * <p>Keys that are absent from {@code root} are <strong>dropped</strong> — this is a reload, not
     * a merge, so a message deleted from the file stops being served. The map is never cleared
     * first, so a reader on another thread always sees either the old or the new value for a key,
     * never nothing. If you need a defaults-plus-overrides arrangement, merge the two
     * {@link ConfigurationNode} trees (Configurate's {@code mergeFrom}) and load the result once.
     *
     * <p>Never throws. A structurally odd node — a list of maps, say — is skipped rather than
     * failing the reload, and the affected key then renders as its own name in game. A reload that
     * throws halfway leaves a plugin serving a half-populated message set, which is worse than one
     * visibly wrong string.
     *
     * @param root the root node to read
     */
    public void load(@NotNull ConfigurationNode root) {
        Map<String, String> loaded = new HashMap<>();
        flatten("", root, loaded);
        this.rawMessages.putAll(loaded);
        this.rawMessages.keySet().retainAll(loaded.keySet());
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
            root.node((Object[]) KEY_SEPARATOR.split(entry.getKey())).set(entry.getValue());
        }
    }

    private static void flatten(@NotNull String path,
                                @NotNull ConfigurationNode node,
                                @NotNull Map<String, String> target) {
        // The root itself is never a message: an empty path would store a value under the "" key.
        if (!path.isEmpty() && !node.empty()) {
            if (node.isList()) {
                String joined = joinLines(node);
                if (!joined.isEmpty()) {
                    target.put(path, joined);
                }
            } else if (!node.isMap()) {
                String raw = node.getString();
                if (raw != null) {
                    target.put(path, raw);
                }
            }
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
            String key = String.valueOf(child.getKey());
            flatten(path.isEmpty() ? key : path + '.' + key, child.getValue(), target);
        }
    }

    /**
     * Joins a YAML list into one multi-line template.
     *
     * <p>Iterates the child nodes rather than calling {@code getList(String.class, ...)} so that a
     * list element which is not a scalar is skipped instead of throwing a
     * {@link SerializationException} out of a reload.
     */
    private static @NotNull String joinLines(@NotNull ConfigurationNode node) {
        return node.childrenList().stream()
                .map(ConfigurationNode::getString)
                .filter(line -> line != null && !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
