package bml.notifications.internal;

import bitwig.obf.FXB; // a notification's severity: cfM warning, MRY error, Hnt information
import bitwig.obf.iFt; // the visitor every entry accepts (ignored)
import bitwig.obf.npm; // a notification-centre message
import bitwig.obf.sO3; // literal (untranslated) text: a name and a description

import bml.notifications.Severity;

/**
 * A message that is never posted, so that a row outside the centre ({@link
 * bml.notifications.Notifications#messageRow}) is Bitwig's own message row ({@code dMF}). It has a close
 * button when the mod gave {@code onClose}, no action buttons and no auto-dismiss. UI thread.
 */
final class Standin extends npm {
    private final FXB severity;
    private final Runnable onClose;

    Standin(Severity severity, String title, String text, Runnable onClose) {
        super(new sO3(Text.title(title, text), Text.text(text))); // literal title and description, never an empty title
        this.severity = switch (severity == null ? Severity.INFORMATION : severity) {
            case INFORMATION -> FXB.Hnt; // information
            case WARNING -> FXB.cfM;     // warning
            case ERROR -> FXB.MRY;       // error
        };
        this.onClose = onClose;
    }

    @Override
    public FXB MRY() { // its severity
        return severity;
    }

    @Override
    public boolean IwV() { // has a close button
        return onClose != null;
    }

    @Override
    public int wGc() { // never removes itself
        return 0;
    }

    @Override
    public boolean ab_() { // never posted; would not open the panel
        return false;
    }

    @Override
    public void hUR() { // its row's close button (it was never in Bitwig's store)
        if (onClose == null) return;
        try {
            onClose.run();
        } catch (Throwable t) {
            Center.MOD.log("a message row's close failed", t);
        }
    }

    @Override
    public void cfM(iFt visitor) { // ignored, as Bitwig's own messages do
    }
}
