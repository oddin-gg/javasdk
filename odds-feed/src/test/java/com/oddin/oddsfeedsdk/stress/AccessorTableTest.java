package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The table lists the getters an entity has and calls each of them. */
class AccessorTableTest {

    @Test
    void everyNoArgumentAndLocaleGetterOfAnEntityIsListedAndCalled() throws Exception {
        var called = new ArrayList<String>();
        var locales = new ArrayList<Object>();
        var player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (_, method, args) -> {
                    called.add(method.getName());
                    if (args != null) {
                        locales.add(args[0]);
                    }
                    return null;
                });

        var accessors = AccessorTable.of(player);
        for (var accessor : accessors) {
            accessor.call();
        }

        assertThat(accessors).extracting(AccessorTable.Accessor::name).contains("Player.getId", "Player.getName");
        assertThat(called)
                .containsExactlyInAnyOrderElementsOf(accessors.stream()
                        .map(accessor -> accessor.method().getName())
                        .toList());
        assertThat(locales).isNotEmpty().containsOnly(Locale.ENGLISH);
    }

    @Test
    void anObjectOfNoKnownEntityTypeHasNoAccessors() {
        assertThat(AccessorTable.of("not an entity")).isEmpty();
        assertThat(AccessorTable.TYPES).hasSize(7);
    }
}
