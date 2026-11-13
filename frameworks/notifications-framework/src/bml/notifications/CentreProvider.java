package bml.notifications;

/**
 * A mod's declarations for the notification centre, through a registration with this framework
 * ({@code notification-rows.properties}, {@code provider.class=...}): row layouts, watchers and a toast
 * handler. {@link #declare} is called once, on Bitwig's UI thread, before the first row is built or the
 * first toast is shown. A provider that only lays out rows may implement {@link RowProvider} instead.
 */
public interface CentreProvider {
    void declare(Centre centre);
}
