package bml.notifications.internal;

/**
 * One message a mod posted, through every replacement: what it says now and the entry showing it.
 * Only {@link Center} touches it, on Bitwig's UI thread.
 */
public final class Record {
    Spec spec;
    Entry entry; // the entry in Bitwig's centre, or null before it is posted or after it is dismissed
    boolean dismissed;
    volatile bml.notifications.Message handle; // the mod's handle, for row layouts

    Record(Spec spec) {
        this.spec = spec;
    }
}
