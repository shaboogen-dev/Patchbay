package bml.notifications;

/**
 * A mod's row layouts, declared through a registration with this framework ({@code
 * notification-rows.properties}, {@code provider.class=...}). {@link #declare} is called once, on Bitwig's
 * UI thread, before the first row is built.
 */
public interface RowProvider {
    void declare(Rows rows);
}
