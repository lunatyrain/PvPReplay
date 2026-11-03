package com.pvpreplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Small helpers for chat output. Everything is built from plain components (no user text is ever parsed). */
public final class Msg {
    private Msg() {
    }

    public static final Component PREFIX = Component.text("[PvPReplay] ", NamedTextColor.AQUA);

    public static Component info(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GRAY));
    }

    public static Component ok(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GREEN));
    }

    public static Component err(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.RED));
    }

    public static Component plain(String text, TextColor color) {
        return Component.text(text, color);
    }

    /** A clickable chat button that runs a command. */
    public static Component button(String label, TextColor color, String command, String hover) {
        return Component.text(label, color)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover + "\n" + command, NamedTextColor.GRAY)));
    }
}
