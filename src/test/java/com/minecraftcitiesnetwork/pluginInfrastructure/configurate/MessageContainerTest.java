package com.minecraftcitiesnetwork.pluginInfrastructure.configurate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorFlag;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageContainerTest {

    private final MessageContainer container = new MessageContainer();

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * True if any component in the rendered tree satisfies {@code predicate}.
     *
     * <p>Uses Adventure's own iterator rather than walking {@code children()} by hand, so the search
     * also reaches components nested inside hover events and translatable arguments — exactly where
     * a hostile value would end up if it were being parsed as markup.
     */
    private static boolean anyComponent(Component root, Predicate<Component> predicate) {
        for (Component component : root.iterable(ComponentIteratorType.DEPTH_FIRST,
                ComponentIteratorFlag.INCLUDE_HOVER_SHOW_TEXT_COMPONENT,
                ComponentIteratorFlag.INCLUDE_HOVER_SHOW_ENTITY_NAME,
                ComponentIteratorFlag.INCLUDE_TRANSLATABLE_COMPONENT_ARGUMENTS)) {
            if (predicate.test(component)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // The security contract: runtime values are inserted as text, never as markup.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "<click:run_command:'/op evil'>click me</click>",
            "<hover:show_text:'gotcha'>hi</hover>",
            "<red><bold>shouty",
            "<insertion:'/msg victim'>x</insertion>",
            "<newline>",
    })
    @DisplayName("markup in a runtime value passed through value() is inserted as literal text")
    void valueDoesNotInterpretMarkup(String hostile) {
        container.setMessage("punish.reason", "Reason: <reason>");

        Component rendered = container.messageFor("punish.reason",
                MessageContainer.value("reason", hostile));

        // The hostile string survives verbatim in the text, i.e. it was never parsed as tags.
        assertTrue(plain(rendered).contains(hostile),
                () -> "hostile value was not preserved literally: " + plain(rendered));
        // And crucially it produced no interactive behaviour. A click event here would run the
        // attacker's command as whoever clicks the message.
        assertFalse(anyComponent(rendered, c -> c.clickEvent() != null),
                "runtime value must not be able to attach a click event");
        assertFalse(anyComponent(rendered, c -> c.hoverEvent() != null),
                "runtime value must not be able to attach a hover event");
    }

    @Test
    @DisplayName("markup() injects a pre-built component as structured data")
    void markupInjectsComponent() {
        container.setMessage("time.permanent", "<red>permanent</red>");
        container.setMessage("punish.banned", "Banned for <time>");

        Component rendered = container.messageFor("punish.banned",
                MessageContainer.markup("time", container.messageFor("time.permanent")));

        assertEquals("Banned for permanent", plain(rendered));
        // The nested message kept its own formatting rather than being flattened to plain text.
        assertTrue(MiniMessage.miniMessage().serialize(rendered).contains("red"),
                () -> "expected the injected component to keep its colour: "
                        + MiniMessage.miniMessage().serialize(rendered));
    }

    @Test
    @DisplayName("a value containing the prefix tag cannot re-trigger prefix expansion")
    void valueCannotExpandPrefix() {
        container.setMessage("prefix", "<gold>[Server]</gold> ");
        container.setMessage("greet", "<prefix>hello <name>");

        Component rendered = container.messageFor("greet",
                MessageContainer.value("name", "<prefix>"));

        // The template's own <prefix> expands; the one arriving as a runtime value does not.
        assertEquals("[Server] hello <prefix>", plain(rendered));
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering, prefix and failure modes.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an unknown key renders as the key itself")
    void unknownKeyRendersAsKey() {
        assertEquals("errors.nope", plain(container.messageFor("errors.nope")));
        assertEquals("errors.nope", container.miniMessageFormattedFor("errors.nope"));
        assertEquals("errors.nope", container.plaintextMessageFor("errors.nope"));
    }

    @Test
    @DisplayName("the prefix message is exposed to every other message as <prefix>")
    void prefixIsInterpolated() {
        container.setMessage("prefix", "<gold>[Server]</gold> ");
        container.setMessage("hello", "<prefix>hello");

        assertEquals("[Server] hello", plain(container.messageFor("hello")));
    }

    @Test
    @DisplayName("without a prefix message, <prefix> resolves to nothing rather than failing")
    void absentPrefixResolvesEmpty() {
        container.setMessage("hello", "<prefix>hello");

        assertEquals("hello", plain(container.messageFor("hello")));
    }

    @Test
    @DisplayName("changing the prefix takes effect immediately; the parse cache cannot go stale")
    void prefixChangeIsPickedUp() {
        container.setMessage("hello", "<prefix>hello");
        container.setMessage("prefix", "[One] ");
        assertEquals("[One] hello", plain(container.messageFor("hello")));

        // Rendering above memoized the parsed prefix. A second render must not serve it.
        container.setMessage("prefix", "[Two] ");
        assertEquals("[Two] hello", plain(container.messageFor("hello")));

        // Same again via a reload, which is how it actually changes in production.
        ConfigurationNode root = BasicConfigurationNode.root();
        assertDoesNotThrow(() -> {
            root.node("hello").set("<prefix>hello");
            root.node("prefix").set("[Three] ");
        });
        container.load(root);
        assertEquals("[Three] hello", plain(container.messageFor("hello")));

        // And a reload that drops the prefix entirely falls back to rendering nothing for it.
        ConfigurationNode without = BasicConfigurationNode.root();
        assertDoesNotThrow(() -> without.node("hello").set("<prefix>hello"));
        container.load(without);
        assertEquals("hello", plain(container.messageFor("hello")));
    }

    @Test
    @DisplayName("a caller-supplied resolver takes precedence over the built-in prefix")
    void callerResolverOverridesPrefix() {
        container.setMessage("prefix", "[Server] ");
        container.setMessage("hello", "<prefix>hello");

        Component rendered = container.messageFor("hello",
                MessageContainer.value("prefix", "[Staff] "));

        assertEquals("[Staff] hello", plain(rendered));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "<hover:show_text>no argument</hover>",
            "<click>no argument</click>",
            "<color>no argument</color>",
            "<transition:red>too few args</transition>",
            "unclosed <red",
            "<lang:>",
    })
    @DisplayName("a template the operator has broken never throws out of messageFor")
    void brokenTemplateNeverThrows(String broken) {
        container.setMessage("broken", broken);

        Component rendered = assertDoesNotThrow(() -> container.messageFor("broken"));
        assertNotNull(rendered);
        // Rendering must also not throw when a resolver is supplied, and not on the plaintext path.
        assertDoesNotThrow(() -> container.plaintextMessageFor("broken",
                MessageContainer.value("x", "y")));
    }

    @Test
    @DisplayName("a malformed tag in a template degrades to literal text, not an exception")
    void malformedTagRendersLeniently() {
        container.setMessage("broken", "<hover:show_text>oops</hover>");

        // Documents MiniMessage's actual behaviour: the default instance is lenient, so a tag it
        // cannot make sense of survives as text. It does NOT raise ParsingException — the catch in
        // messageFor is a backstop for a stricter instance, and is not reachable from here.
        assertEquals("<hover:show_text>oops</hover>", plain(container.messageFor("broken")));
    }

    @Test
    @DisplayName("a broken prefix does not take every other message down with it")
    void brokenPrefixDegradesGracefully() {
        container.setMessage("prefix", "<hover:show_text>");
        container.setMessage("hello", "<prefix>hello");

        // Whatever the prefix renders to, the message itself still renders and still ends in the
        // message text — a bad prefix must not cost the operator every string in the file.
        Component rendered = assertDoesNotThrow(() -> container.messageFor("hello"));
        assertTrue(plain(rendered).endsWith("hello"),
                () -> "unexpected rendering: " + plain(rendered));
    }

    // ---------------------------------------------------------------------------------------------
    // Loading and saving.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("nested nodes flatten to dotted keys and lists join with newlines")
    void loadFlattensTree() throws Exception {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("prefix").set("[S] ");
        root.node("errors", "no-permission").set("nope");
        root.node("help").set(List.of("line one", "", "line two"));

        container.load(root);

        assertEquals("nope", container.miniMessageFormattedFor("errors.no-permission"));
        // Blank list entries are dropped rather than producing a stray empty line.
        assertEquals("line one\nline two", container.miniMessageFormattedFor("help"));
        assertEquals("[S] ", container.miniMessageFormattedFor("prefix"));
        // The intermediate map node is not itself a message.
        assertEquals("errors", container.miniMessageFormattedFor("errors"));
    }

    @Test
    @DisplayName("a reload drops keys that disappeared from the file")
    void loadDropsRemovedKeys() throws Exception {

        ConfigurationNode first = BasicConfigurationNode.root();
        first.node("a").set("one");
        first.node("b").set("two");
        container.load(first);
        assertEquals("one", container.miniMessageFormattedFor("a"));
        assertEquals("two", container.miniMessageFormattedFor("b"));

        ConfigurationNode second = BasicConfigurationNode.root();
        second.node("a").set("one changed");
        container.load(second);

        assertEquals("one changed", container.miniMessageFormattedFor("a"));
        // 'b' was deleted from the file, so it must stop being served — it now renders as its key.
        assertEquals("b", container.miniMessageFormattedFor("b"));
    }

    @Test
    @DisplayName("a structurally odd node is skipped instead of failing the whole reload")
    void loadSkipsOddNodes() throws Exception {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("good").set("fine");
        // A list whose elements are maps: getList(String.class, …) would throw here.
        root.node("weird").appendListNode().node("nested").set("value");

        assertDoesNotThrow(() -> container.load(root));

        assertEquals("fine", container.miniMessageFormattedFor("good"));
    }

    @Test
    @DisplayName("save expands dotted keys back into nested nodes")
    void saveRoundTrips() throws Exception {
        container.setMessage("errors.no-permission", "nope");
        container.setMessage("prefix", "[S] ");

        ConfigurationNode out = BasicConfigurationNode.root();
        container.save(out);

        assertEquals("nope", out.node("errors", "no-permission").getString());
        assertEquals("[S] ", out.node("prefix").getString());

        MessageContainer reloaded = new MessageContainer();
        reloaded.load(out);
        assertEquals("nope", reloaded.miniMessageFormattedFor("errors.no-permission"));
    }

    @Test
    @DisplayName("clear removes everything")
    void clearEmptiesTheContainer() {
        container.setMessage("a", "one");
        container.clear();
        assertEquals("a", container.miniMessageFormattedFor("a"));
    }
}
