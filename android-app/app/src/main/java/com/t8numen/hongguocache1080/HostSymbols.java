package com.t8numen.hongguocache1080;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Exact, offline-verified symbol aliases for the supported Manju version. */
final class HostSymbols {
    private static final ConcurrentHashMap<Class<?>, List<Class<?>>> HIERARCHIES =
            new ConcurrentHashMap<>();

    static final class Alias {
        final String name;
        final String[] parameters;

        Alias(String name, String[] parameters) {
            this.name = name;
            this.parameters = parameters;
        }
    }

    private HostSymbols() { }

    static String className(String name) {
        return TargetApp.isManju() ? ManjuSymbols.CLASSES.getOrDefault(name, name) : name;
    }

    static String resource(String name) {
        return TargetApp.isManju() ? ManjuSymbols.RESOURCES.getOrDefault(name, "") : name;
    }

    static Class<?> findClass(String name, ClassLoader loader) {
        return XposedHelpers.findClass(className(name), loader);
    }

    static Object getObjectField(Object object, String name) {
        return XposedHelpers.getObjectField(object, fieldName(object.getClass(), name));
    }

    static long getLongField(Object object, String name) {
        return XposedHelpers.getLongField(object, fieldName(object.getClass(), name));
    }

    static Object getStaticObjectField(Class<?> type, String name) {
        return XposedHelpers.getStaticObjectField(type, fieldName(type, name));
    }

    static Object callMethod(Object object, String name, Object... arguments) {
        return XposedHelpers.callMethod(object,
                methodName(object.getClass(), name, arguments, false), arguments);
    }

    static Object callStaticMethod(Class<?> type, String name, Object... arguments) {
        return XposedHelpers.callStaticMethod(type,
                methodName(type, name, arguments, false), arguments);
    }

    static XC_MethodHook.Unhook findAndHookMethod(String owner, ClassLoader loader,
            String name, Object... parametersAndCallback) {
        return findAndHookMethod(findClass(owner, loader), name, parametersAndCallback);
    }

    static XC_MethodHook.Unhook findAndHookMethod(Class<?> type, String name,
            Object... parametersAndCallback) {
        Object[] parameters = java.util.Arrays.copyOf(parametersAndCallback,
                parametersAndCallback.length - 1);
        return XposedHelpers.findAndHookMethod(type,
                methodName(type, name, parameters, true), parametersAndCallback);
    }

    static XC_MethodHook.Unhook findAndHookConstructor(String owner, ClassLoader loader,
            Object... parametersAndCallback) {
        return XposedHelpers.findAndHookConstructor(findClass(owner, loader), parametersAndCallback);
    }

    static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type, String name,
            XC_MethodHook callback) {
        if (!TargetApp.isManju()) {
            return XposedBridge.hookAllMethods(type, name, callback);
        }
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> parent : hierarchy(type)) {
            List<Alias> aliases = ManjuSymbols.METHODS.get(parent.getName() + "#" + name);
            if (aliases != null) {
                for (Alias alias : aliases) {
                    names.add(alias.name);
                }
            }
        }
        if (names.isEmpty()) {
            names.add(name);
        }
        Set<XC_MethodHook.Unhook> hooks = new LinkedHashSet<>();
        for (String actual : names) {
            hooks.addAll(XposedBridge.hookAllMethods(type, actual, callback));
        }
        return hooks;
    }

    private static String fieldName(Class<?> type, String name) {
        if (TargetApp.isManju()) {
            for (Class<?> parent : hierarchy(type)) {
                String alias = ManjuSymbols.FIELDS.get(parent.getName() + "#" + name);
                if (alias != null) {
                    return alias;
                }
            }
        }
        return name;
    }

    private static String methodName(Class<?> type, String name, Object[] arguments,
            boolean explicitTypes) {
        if (!TargetApp.isManju()) {
            return name;
        }
        for (Class<?> parent : hierarchy(type)) {
            List<Alias> aliases = ManjuSymbols.METHODS.get(parent.getName() + "#" + name);
            if (aliases == null) {
                continue;
            }
            int bestScore = -1;
            String best = null;
            boolean ambiguous = false;
            for (Alias alias : aliases) {
                int score = score(alias, arguments, explicitTypes, type.getClassLoader());
                if (score > bestScore) {
                    bestScore = score;
                    best = alias.name;
                    ambiguous = false;
                } else if (score >= 0 && score == bestScore && !alias.name.equals(best)) {
                    ambiguous = true;
                }
            }
            if (best != null && !ambiguous) {
                return best;
            }
            throw new NoSuchMethodError("No unambiguous Manju alias: " + parent.getName()
                    + "#" + name + "/" + arguments.length);
        }
        return name;
    }

    private static int score(Alias alias, Object[] arguments, boolean explicitTypes,
            ClassLoader loader) {
        if (alias.parameters.length != arguments.length) {
            return -1;
        }
        int score = 0;
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            if (argument == null) {
                if (primitive(alias.parameters[index])) {
                    return -1;
                }
                continue;
            }
            String expected = alias.parameters[index];
            String actual = explicitTypes
                    ? argument instanceof Class<?> ? ((Class<?>) argument).getName()
                            : className(String.valueOf(argument))
                    : argument.getClass().getName();
            if (expected.equals(actual) || (!explicitTypes && boxed(expected).equals(actual))) {
                score += 4;
                continue;
            }
            if (explicitTypes || primitive(expected)) {
                return -1;
            }
            try {
                if (!XposedHelpers.findClass(expected, loader).isInstance(argument)) {
                    return -1;
                }
                score++;
            } catch (Throwable unavailable) {
                return -1;
            }
        }
        return score;
    }

    private static boolean primitive(String name) {
        return !name.equals(boxed(name));
    }

    private static String boxed(String name) {
        switch (name) {
            case "boolean": return "java.lang.Boolean";
            case "byte": return "java.lang.Byte";
            case "char": return "java.lang.Character";
            case "short": return "java.lang.Short";
            case "int": return "java.lang.Integer";
            case "long": return "java.lang.Long";
            case "float": return "java.lang.Float";
            case "double": return "java.lang.Double";
            default: return name;
        }
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        return HIERARCHIES.computeIfAbsent(type, key -> {
            Set<Class<?>> found = new LinkedHashSet<>();
            // Prefer concrete declarations, then parents, then interface contracts.
            for (Class<?> current = key; current != null; current = current.getSuperclass()) {
                found.add(current);
            }
            for (Class<?> current : new ArrayList<>(found)) {
                addInterfaces(current, found);
            }
            return Collections.unmodifiableList(new ArrayList<>(found));
        });
    }

    private static void addInterfaces(Class<?> type, Set<Class<?>> found) {
        for (Class<?> contract : type.getInterfaces()) {
            if (found.add(contract)) {
                addInterfaces(contract, found);
            }
        }
    }
}
