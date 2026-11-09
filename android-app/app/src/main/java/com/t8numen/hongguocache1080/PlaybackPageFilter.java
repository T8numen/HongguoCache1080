package com.t8numen.hongguocache1080;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Remove promotion pages before the player receives a list, preserving normal-page order. */
final class PlaybackPageFilter {
    private PlaybackPageFilter() {
    }

    static List<?> filter(List<?> pages, Predicate<Object> blocked) {
        List<Object> result = null;
        for (int index = 0; index < pages.size(); index++) {
            Object page = pages.get(index);
            if (blocked.test(page)) {
                if (result == null) {
                    result = new ArrayList<>(pages.subList(0, index));
                }
            } else if (result != null) {
                result.add(page);
            }
        }
        return result == null ? pages : result;
    }

    static int remapIndex(List<?> pages, Predicate<Object> blocked, int index, int filteredSize) {
        if (index < 0 || filteredSize == 0) {
            return -1;
        }
        int before = 0;
        for (int position = 0; position < Math.min(index, pages.size()); position++) {
            if (!blocked.test(pages.get(position))) {
                before++;
            }
        }
        return Math.min(before, filteredSize - 1);
    }
}
