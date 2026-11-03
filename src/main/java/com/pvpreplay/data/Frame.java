package com.pvpreplay.data;

/** One server tick. {@code key} frames start a segment (full equipment is re-emitted). */
public record Frame(boolean key, PState[] states, Ev[] events) {
}
