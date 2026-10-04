package jbro.cobblemon.popupemotes.client.custom;

import java.util.List;

public final class CustomEmoteCatalog {
    public static final int PAGE_SIZE = 9;
    public static final int MAX_PAGES = 5;
    public static final int MAX_EMOTES = PAGE_SIZE * MAX_PAGES;

    private final List<CustomEmoteDefinition> entries;

    public CustomEmoteCatalog(List<CustomEmoteDefinition> entries) {
        this.entries = List.copyOf(entries);
        if (this.entries.size() > MAX_EMOTES) {
            throw new IllegalArgumentException("At most " + MAX_EMOTES + " custom emotes are allowed");
        }
    }

    public List<CustomEmoteDefinition> entries() {
        return entries;
    }

    public int pageCount() {
        return entries.isEmpty() ? 0 : (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    public List<CustomEmoteDefinition> page(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount()) {
            throw new IndexOutOfBoundsException("Custom emote page: " + pageIndex);
        }
        int start = pageIndex * PAGE_SIZE;
        return entries.subList(start, Math.min(entries.size(), start + PAGE_SIZE));
    }
}
