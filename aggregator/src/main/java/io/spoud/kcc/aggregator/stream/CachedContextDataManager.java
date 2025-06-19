package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.repository.ContextDataRepository;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Looking into globalKTable take an insane amount of time, so we cache the data for a little while.
 * This help a lot with processing time. Cache is kept for a few seconds, so we have an acceptable
 * little delay when a new context data is added. In addition, we keep the Pattern, so we don't have
 * to compile it everytime.
 */
@ApplicationScoped
public class CachedContextDataManager {
    private static final Duration CACHE_DURATION = Duration.ofSeconds(10);
    private final ContextDataRepository contextDataRepository;
    private List<CachedContextData> cachedContextData;
    private Instant lastUpdate = null;

    public CachedContextDataManager(ContextDataRepository contextDataRepository) {
        this.contextDataRepository = Objects.requireNonNull(contextDataRepository);
    }

    /**
     * Get a list of all contexts. The list is cached for CACHE_DURATION seconds,
     * so that repeated calls do not result in the list being rebuilt.
     * The list is sorted by creation time (oldest first). Thus merging contexts in the order in which they appear in
     * this list ensures that the newest value will be the one that is kept if there is a conflict.
     *
     * @return the list of all contexts
     */
    public synchronized List<CachedContextData> getCachedContextData() {
        if (cachedContextData == null || lastUpdate.isBefore(Instant.now().minus(CACHE_DURATION))) {
            // Building cache
            // use the internal globalKTable to get the context data
            ReadOnlyKeyValueStore<String, ContextData> store = contextDataRepository.getStore();

            List<CachedContextData> list = new ArrayList<>();
            try (final KeyValueIterator<String, ContextData> iterator = store.all()) {
                while (iterator.hasNext()) {
                    KeyValue<String, ContextData> next = iterator.next();
                    String key = next.key;
                    ContextData contextData = next.value;

                    try {
                        list.add(new CachedContextData(key, contextData));
                    } catch (Exception e) {
                        Log.warnf("Error while creating CachedContextData for key %s: %s. This context will not be considered: %s", key, contextData, e.getMessage());
                        Log.debug("Previous warning due to", e);
                    }
                }
            }
            lastUpdate = Instant.now();
            cachedContextData = list.stream()
                    .sorted(Comparator.comparing((CachedContextData c) -> c.getContextData().getCreationTime()))
                    .toList();
        }
        return cachedContextData;
    }

    public Map<String, String> getContextDataForName(EntityType entityType, String objectName, Instant timestamp) {
        // Get the cached context data
        List<CachedContextData> contextDataList = getCachedContextData();
        Map<String, String> context = new HashMap<>();
        // This is the join with regex
        contextDataList.forEach(cachedContext -> {
            cachedContext.getMatcher(entityType, objectName, timestamp)
                    .ifPresent(matcher -> {
                        context.putAll(cachedContext.getContextData().getContext().entrySet().stream()
                                // replace all the regex variable in the value
                                .map(entry -> {
                                    try {
                                        return Map.entry(entry.getKey(), matcher.replaceAll(entry.getValue()));
                                    } catch (IndexOutOfBoundsException ex) {
                                        Log.warnv(ex, "Unable to replace regex variable for the entry \"{0}\" with the regex \"{1}\" and the context \"{2}={3}\"",
                                                objectName, cachedContext.getContextData().getRegex(), entry.getKey(), entry.getValue());
                                        return entry;
                                    }
                                })
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (l, r) -> r)));
                    });
        });
        return context;
    }

    public synchronized void clearCache() {
        cachedContextData = null;
    }


    public static class CachedContextData {
        @Getter
        private final String key;
        @Getter
        private final ContextData contextData;
        private final Pattern pattern;

        public CachedContextData(String key, ContextData contextData) {
            this.key = key;
            this.contextData = contextData;
            this.pattern = Pattern.compile(contextData.getRegex(), Pattern.CASE_INSENSITIVE);
        }

        /**
         * Get the Matcher object, but only if the current metric is eligible and satisfy the regex
         */
        public Optional<Matcher> getMatcher(EntityType type, String objectName, Instant timestamp) {
            if (contextData.getEntityType().equals(type)
                    && isInContextDataTimeRange(timestamp, contextData)) {
                return Optional.of(pattern.matcher(objectName))
                        // only if there is a match
                        .filter(Matcher::matches);
            } else {
                return Optional.empty();
            }
        }

        private boolean isInContextDataTimeRange(Instant timestamp, ContextData contextData) {
            return (contextData.getValidFrom() == null || timestamp.compareTo(contextData.getValidFrom()) >= 0)
                    && (contextData.getValidUntil() == null || contextData.getValidUntil().compareTo(timestamp) > 0);
        }
    }
}
