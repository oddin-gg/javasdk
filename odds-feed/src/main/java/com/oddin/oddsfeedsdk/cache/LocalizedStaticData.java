package com.oddin.oddsfeedsdk.cache;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface LocalizedStaticData extends StaticData {
    @Nullable
    String getDescription(Locale locale);
}
