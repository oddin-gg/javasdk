package com.oddin.oddsfeedsdk.stress;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Fixture;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The public getters of the entities, for reader threads to hammer: on a cache miss each one goes to
 * the API, so calling them all is what makes the caches load, coalesce and evict under a run. A
 * getter is a public {@code get*} or {@code is*} method of the entity's interface that takes nothing
 * or just a {@code Locale}, called with English.
 */
final class AccessorTable {

    /** The entity interfaces whose getters are listed. */
    static final List<Class<?>> TYPES = List.of(
            Match.class,
            Competitor.class,
            Player.class,
            Fixture.class,
            MatchStatus.class,
            Tournament.class,
            Sport.class);

    private AccessorTable() {}

    /** One getter of one entity. */
    record Accessor(String name, Object target, Method method) {

        /** Calls it, unwrapping what the getter threw. */
        Object call() throws Exception {
            try {
                return method.getParameterCount() == 0 ? method.invoke(target) : method.invoke(target, Locale.ENGLISH);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        }
    }

    /** The getters of {@code type} on this entity, by name. */
    static List<Accessor> of(Class<?> type, Object target) {
        return Arrays.stream(type.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method ->
                        method.getName().startsWith("get") || method.getName().startsWith("is"))
                .filter(method -> method.getParameterCount() == 0
                        || Arrays.equals(method.getParameterTypes(), new Class<?>[] {Locale.class}))
                .sorted(Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount))
                .map(method -> new Accessor(type.getSimpleName() + "." + method.getName(), target, method))
                .toList();
    }

    /** The getters of every entity interface in {@link #TYPES} that this entity implements. */
    static List<Accessor> of(Object target) {
        return TYPES.stream()
                .filter(type -> type.isInstance(target))
                .flatMap(type -> of(type, target).stream())
                .toList();
    }
}
