package com.pvpreplay;

import java.lang.reflect.Method;

/**
 * Tiny reflection helper for a few very new / rarely used API calls (mannequin skin, pose, hurt animation).
 * If a method does not exist in the running server version the call is simply skipped.
 */
public final class Compat {
    private Compat() {
    }

    /** Invoke {@code name} on {@code target} with the given args. Returns the result or null; never throws. */
    public static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        try {
            Method m = find(target.getClass(), name, args);
            if (m == null) return null;
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // public methods usually work without this
            }
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean has(Object target, String name, int argCount) {
        if (target == null) return false;
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount) return true;
        }
        return false;
    }

    private static Method find(Class<?> c, String name, Object[] args) {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length && ok; i++) {
                ok = args[i] == null ? !pt[i].isPrimitive() : wrap(pt[i]).isInstance(args[i]);
            }
            if (ok) return m;
        }
        return null;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }
}
