package jbro.cobblemon.popupemotes.client.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CustomEmoteDrafts {
    private final List<Draft> entries = new ArrayList<>();

    public CustomEmoteDrafts(List<CustomEmoteDefinition> source) {
        for (CustomEmoteDefinition entry : source) {
            entries.add(new Draft(entry.name(), entry.url()));
        }
    }

    public int size() {
        return entries.size();
    }

    public Draft get(int index) {
        return entries.get(index);
    }

    public void set(int index, String name, String url) {
        entries.set(index, new Draft(name, url));
    }

    public int add(String name, String url) {
        if (entries.size() >= CustomEmoteCatalog.MAX_EMOTES) {
            throw new IllegalStateException("Custom emote limit reached");
        }
        entries.add(new Draft(name, url));
        return entries.size() - 1;
    }

    public int remove(int index) {
        entries.remove(index);
        return entries.isEmpty() ? -1 : Math.min(index, entries.size() - 1);
    }

    public int move(int index, int amount) {
        int destination = Math.max(0, Math.min(entries.size() - 1, index + amount));
        if (destination != index) {
            Collections.swap(entries, index, destination);
        }
        return destination;
    }

    public void swap(int firstIndex, int secondIndex) {
        Collections.swap(entries, firstIndex, secondIndex);
    }

    public int pageCount() {
        return Math.max(1, (entries.size() + CustomEmoteCatalog.PAGE_SIZE - 1) / CustomEmoteCatalog.PAGE_SIZE);
    }

    public int pageFor(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Custom emote index: " + index);
        }
        return index / CustomEmoteCatalog.PAGE_SIZE;
    }

    public List<Draft> page(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount()) {
            throw new IndexOutOfBoundsException("Custom emote page: " + pageIndex);
        }
        int start = pageIndex * CustomEmoteCatalog.PAGE_SIZE;
        int end = Math.min(entries.size(), start + CustomEmoteCatalog.PAGE_SIZE);
        return List.copyOf(entries.subList(start, end));
    }

    public List<Draft> snapshot() {
        return List.copyOf(entries);
    }

    public List<CustomEmoteDefinition> definitions() {
        return entries.stream()
            .filter(entry -> !entry.name().isBlank() || !entry.url().isBlank())
            .map(entry -> new CustomEmoteDefinition(entry.name(), entry.url()))
            .toList();
    }

    public record Draft(String name, String url) {
    }
}
