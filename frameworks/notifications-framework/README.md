# Framework: Notifications

Lets a mod tell the user something the way Bitwig does: a short reply in Bitwig's transient notification (a **toast**), a **message** in Bitwig's notification centre (the dot at the top right of the window), or a running **task** there with a status line, a progress bar and Cancel. Plain Java, no Bitwig names, safe from any thread.

What to say, and whether it is a toast, a message or a task, is the mod's choice. A mod can also lay out every row in the notification centre, Bitwig's own included ([Row layouts](#row-layouts)); without such a mod, Bitwig's rows stay exactly as Bitwig draws them.

A mod can also [watch the centre's entries](#watching-the-centre), [take Bitwig's toasts](#taking-toasts) before they are shown, and [show a message row of its own](#message-rows-outside-the-centre) outside the centre. The framework itself changes nothing the user sees: Notifications: History and Notifications: Pop-ups in the Centre are mods built on these.

- Mod ID: `notifications-framework`
- Built against Bitwig 6.1.4.
- Needs no other mod.

## Using it

**1. Require the framework in `mod.json`.** There is nothing to register (except for [row layouts](#row-layouts)):

```json
"requires": [
  { "id": "notifications-framework", "min_version": "<version>" }
]
```

**2. Compile against its helper jar**, in `build.json`:

```json
"classpath_mods": ["notifications-framework@<version>"]
```

**3. Call it.** Everything is in the package `bml.notifications`.

## Which one to use

| What you have to say | Use | Why |
|---|---|---|
| A reply to a click: "already set up", "nothing selected", "busy" | `Notifications.toast` | the user is looking at what they clicked; it goes by itself |
| A job that takes a while | `Notifications.task` | live status, progress and Cancel, without a window of your own |
| An outcome to find later: done, failed, needs something installed | `Notifications.post` / `message` | it stays in the centre until it is closed or dismisses itself |

## Toasts

```java
Notifications.toast("Nothing to tidy: select a device first");
```

Bitwig's transient notification, as its own short messages appear. A mod's [toast handler](#taking-toasts) may show it another way (Notifications: Pop-ups in the Centre makes it a message whose title is the part before the first colon), so write it as "Your Mod: what happened".

## Messages

```java
Notifications.post(Severity.WARNING, "Tidy needs a newer driver", "Install it from the vendor's site, then try again.");
```

With options:

```java
Message m = Notifications.message(Severity.ERROR, "Export failed", "The disk is full.")
        .action("Open Folder", () -> openFolder(dir))   // a button in the message; runs on Bitwig's UI thread
        .dismissAfterSeconds(0)                         // 0 (default): stays until closed
        .closable(true)                                 // default: a close button
        .quiet(false)                                   // false: opens the centre's panel when posted
        .post();

m.replace("Export failed", "The disk is still full.");  // new words, same options
m.dismiss();                                            // take it away
```

- `Severity`: `INFORMATION`, `WARNING`, `ERROR`. Errors use Bitwig's error colour and colour the dot.
- `closable(boolean)`: the close button (default true). A message without one and without auto-dismiss stays until the mod dismisses it.
- `dismissAfterSeconds(int)`: removes the message after that many seconds; 0 (default) keeps it.
- `quiet(boolean)`: a quiet message doesn't open the centre's panel. Default: information is quiet, warnings and errors open it.
- `action(label, Runnable)`: one button per call, in that order. Clicking runs the `Runnable` on Bitwig's UI thread; the message stays (call `dismiss()` in the action to close it).
- The text can be empty; the row then shows only the title.
- The `Message` handle is safe from any thread. After the user closes the message, or it dismisses itself, `dismiss()` does nothing and `replace(...)` posts it again.

## Tasks

```java
Task task = Notifications.task("Render Stems", () -> job.cancel()); // null instead: no Cancel button
task.status("Rendering drums…");
task.progress(0.25);          // fills the progress bar, 0 to 1
task.progressUnknown();       // back to busy: how far the work is isn't known
task.status("Writing files…");
if (task.cancelled()) { /* the user pressed Cancel */ }
task.finish();                // always, however the work ends
```

- `task(title, onCancel)` puts an entry in the centre at once: one row with the title, an empty status line, a busy progress bar, and Cancel when `onCancel` isn't null. Cancel runs `onCancel` once, on Bitwig's UI thread, and greys the button out; stopping the work is the mod's.
- `status`, `progress`, `progressUnknown` and `finish` are safe from any thread and return at once. Progress is sent to Bitwig at most about ten times a second, so calling it for every block of a download is fine.
- The bar is Bitwig's own task progress bar, part of the task's row. While the progress is unknown (before the first `progress` above 0, and after `progressUnknown()`), it shows Bitwig's busy animation instead of a value. For overall progress across several steps, weight each step by how long it usually takes.
- Bitwig doesn't show a task that ends within its first 300 ms, and keeps one on screen for at least two seconds.
- `finish()` takes the entry away. To say how it ended, post a message after it.
- The work runs on the mod's own thread: the framework only shows it. The entry is updated on the framework's own threads, never Bitwig's background-task pool.

## Row layouts

A mod can lay out the rows of Bitwig's notification centre: every task row (Bitwig's own and every mod's, in the centre, inside another task's row and in the list of hidden tasks) and every message row. It gets each row as Bitwig built it, just before Bitwig shows it, with the row's parts under plain names, and moves, replaces, hides or adds parts. No Bitwig names are needed. Without such a mod, the rows stay exactly as Bitwig draws them.

**1. Register with the framework** in `mod.json` (this also makes it a prerequisite):

```json
"registrations": [
  {
    "id": "notification-rows",
    "consumer": "notifications-framework",
    "descriptor": "notification-rows.properties",
    "jar": "helper.jar"
  }
]
```

**2. The descriptor** `package/notification-rows.properties`:

```properties
api.version=1
provider.class=com.example.tidyrows.TidyRows
```

**3. The provider**, declaring a task-row styler, a message-row styler, or both:

```java
import bml.notifications.Part;
import bml.notifications.RowProvider;
import bml.notifications.Rows;
import bml.notifications.TaskRow;
import bml.notifications.TextLook;

public final class TidyRows implements RowProvider {
    @Override
    public void declare(Rows rows) {
        rows.taskRows(row -> {
            Part status = row.status();                            // null for a task without a status line
            if (status != null) {
                Part wrapping = row.wrappingStatus(TextLook.TITLE); // Bitwig's wrapping text, following the status
                status.replaceWith(wrapping);
                status = wrapping;
            }
            row.column().order(row.titleLine(), status, row.barBox(), row.cancel()); // the rest follows
            row.barShownWhileRunning();
            row.barWaitingWhileUnknown();
        });
    }
}
```

**A task row** (`TaskRow`): Bitwig's layout, top to bottom in `column()`, is `titleLine()` (the `spinner()` and the `title()`), `barBox()` (holding the `bar()`), `cancel()` (aligned right), `status()` (one line, cut in the middle when too long) and `subtasks()`; `images()`, when a task has any, sit left of the column in `frame()`. A part the task doesn't have is null. About the task: `task()` (this framework's `Task` handle, or null for a task a mod didn't start through it), `titleText()`, `statusText()`, `hasProgress()`, `progress()` (0 to 1, NaN without a progress value), `cancellable()`, `cancelled()`. Helpers:

- `wrappingStatus(look)`: a new wrapping text that follows the task's status, hidden while it is empty.
- `barShownWhileRunning()`: the bar stays visible while the row is there, also for tasks Bitwig shows no bar for.
- `barWaiting(boolean)`: the bar's busy mode (an animated bar instead of a value), on or off.
- `barWaitingWhileUnknown()`: busy while the progress isn't known (the value is 0, or the task hides its bar), the value as soon as there is one; always busy for a task that has no progress value at all.

**A message row** (`MessageRow`): `column()` holds `titleLine()` (the `title()`, one line, and `close()` at the right), `body()` (the wrapping text) and `buttons()` (the group of buttons). About the message: `message()` (this framework's `Message` handle, or null), `severity()`, `titleText()`, `bodyText()`.

**Parts** (`Part`): `isGroup()`, `parts()`, `add(part)`, `add(index, part)`, `order(parts...)` (puts those first, in that order; nulls skipped), `remove()`, `replaceWith(other)`, `shown(boolean)` (replaces whatever Bitwig bound its visibility to). `row.newText(text, look)` makes a wrapping text, `row.part(widget)` takes one of Bitwig's widgets the mod built itself, and `part.widget()` / `part.content()` give Bitwig's widget for a mod that works with Bitwig's classes. `TextLook`: `TITLE` (bold, the notification text colour), `ERROR_TITLE` (bold, the error colour), `SECONDARY` (the description's colour and font), all from the current theme.

**Rules:**

- A styler runs on Bitwig's UI thread, once each time Bitwig builds a row, never per paint. Keep it short. Rows already shown keep their layout until Bitwig builds them again.
- One styler of each kind is used. When several mods declare one, the last in order of mod ID wins; the log says which one is used and which were passed over.
- Whatever a styler throws is caught and logged: that row is Bitwig's own, and the styler isn't called again in this session.
- A row that isn't built the way the framework expects (a future Bitwig) is left as Bitwig built it, and the styler isn't called for it.

## Declaring with the framework

Row layouts, watchers and a toast handler are declared the same way: a registration with the framework and a provider class.

**1. The registration** in `mod.json` (this also makes the framework a prerequisite):

```json
"registrations": [
  {
    "id": "notification-centre",
    "consumer": "notifications-framework",
    "descriptor": "notification-centre.properties",
    "jar": "helper.jar"
  }
]
```

**2. The descriptor** `package/notification-centre.properties` (any file name; the registration names it):

```properties
api.version=1
provider.class=com.example.mymod.MyCentre
```

**3. The provider** implements `CentreProvider`; its `declare(Centre)` is called once, on Bitwig's UI thread, before the first row is built or the first toast is shown. `Centre` has `taskRows`, `messageRows` ([Row layouts](#row-layouts)), `watch` and `toasts`. A provider that only lays out rows may implement `RowProvider` instead, as above.

## Watching the centre

```java
public final class MyCentre implements CentreProvider, CentreWatcher {
    @Override public void declare(Centre centre) { centre.watch(this); }
    @Override public void shown(CentreEntry entry) { /* it appeared */ }
    @Override public void ended(CentreEntry entry) { /* it went */ }
}
```

- `shown(entry)` is called once for every entry the centre shows, from every mod and from Bitwig; `ended(entry)` when it goes, however it goes (closed, dismissed itself, finished, cancelled, removed). Both get the same object, so it can be a map key.
- `CentreEntry`: `isTask()`, `title()`, `text()` (a message's text, a task's status line), `severity()`, `cancelled()`, `shownAt()`, `endedAt()` (0 while it is there), `replaced()` (its mod called `Message.replace`, and the new words follow as a new entry), `message()` / `task()` (this framework's handles, or null), `entry()` (Bitwig's own object).
- Entries the centre never shows aren't reported: a task that ends within Bitwig's first 300 ms, and background tasks Bitwig keeps behind the spinner next to the dot (such as re-indexing the library).
- Every watcher is told, in order of mod ID. Calls come on Bitwig's UI thread; keep them short. A watcher that throws is logged and not called again in this session.
- While no mod declares a watcher, the framework doesn't listen to the centre at all.

## Taking toasts

```java
public final class MyCentre implements CentreProvider, ToastHandler {
    @Override public void declare(Centre centre) { centre.toasts(this); }
    @Override public boolean toast(Toast toast) {
        if (toast.fromController()) return false; // a knob's value as it turns: leave it to Bitwig
        // show toast.text() another way
        return true; // taken: Bitwig doesn't show it
    }
}
```

- Every toast reaches the handler on Bitwig's UI thread, just before the active window would show it: Bitwig's own, every mod's `Notifications.toast`, and a controller script's popup (`showPopupNotification`), marked by `fromController()`.
- Return true when the handler showed the toast itself; false leaves it to Bitwig.
- One handler is used: when several mods declare one, the last in order of mod ID wins, and the log says which. A handler that throws is logged, that toast shows as Bitwig's, and the handler isn't called again in this session.
- Without a handler, every toast is Bitwig's.

## Message rows outside the centre

```java
Object row = Notifications.messageRow(Severity.INFORMATION, "Export finished", "14:32 · 12 stems", () -> forget(it));
```

Bitwig's own message row with these words, as the centre shows a message and laid out by the message-row styler in use, for a mod that builds its own views with Bitwig's classes (Notifications: History's panel uses it for its rows). The message is never posted: it isn't in the centre and no watcher is told. With a close action the row has a close button that runs it; with null it has none. No action buttons, no auto-dismiss. Call it on Bitwig's UI thread; the result is Bitwig's widget.

## Notes

- While the centre's overlay is hidden (by the dot), tasks and quiet messages leave it hidden (the dot pulses while a task runs and turns the error colour for an error); other messages open it.
- Bitwig keeps messages only for this session. Messages and tasks are not saved with a project.
- Every toast and message from every mod goes through one place in the framework, so later changes to how they look or are kept apply to all mods at once.
