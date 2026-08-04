package betterquesting.api2.storage;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.collect.BiMap;

/** Database that uses randomly-generated UUIDs as keys. */
public interface IUuidDatabase<T> extends BiMap<UUID, T> {

    /** Returns an unused UUID. */
    UUID generateKey();

    @Nullable
    UUID lookupKey(T value);

    /**
     * Returns this database's entries in a stable order.
     * Useful for ensuring that exported files change as little as possible.
     */
    Stream<Map.Entry<UUID, T>> orderedEntries();

    Stream<T> getAll(Collection<UUID> keys);

    Map<UUID, T> filterKeys(Collection<UUID> keys);

    BiMap<UUID, T> filterValues(Collection<T> values);

    BiMap<UUID, T> filterEntries(BiPredicate<UUID, T> filter);

    /**
     * Removes {@code value} from the database, and returns its corresponding key.
     * Returns null if {@code value} is not present in the database.
     */
    @Nullable
    UUID removeValue(T value);
}
