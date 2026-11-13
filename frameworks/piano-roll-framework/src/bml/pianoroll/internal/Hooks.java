package bml.pianoroll.internal;

import bitwig.obf.HEL; // encodes a clip's notes for the audio engine
import bitwig.obf.Ibv; // a note, as the painter sees it
import bitwig.obf.Rke; // a note lane of one channel and key
import bitwig.obf.YeP; // a clip's note lanes
import bitwig.obf.bie; // a note editor's selection
import bitwig.obf.bro; // the painter of a grid's notes
import bitwig.obf.dfI; // a document's Undo: hears every change to what it can undo
import bitwig.obf.dhu; // a document's Undo history
import bml.api.At;
import bml.api.Hook;
import bml.api.This;
import java.util.List;

/** The framework's hooks, in one class as BML asks. Each hands over to the class that does the work. */
public final class Hooks {
    private Hooks() {
    }

    /** A note editor's selection was made: see {@link NoteEditors}. */
    @Hook(target = bie.class, method = "<init>(Lbii;)V", at = At.BEFORE_RETURN, id = "remember-note-selection")
    public static void rememberNoteSelection(@This bie selection) {
        NoteEditors.rememberNoteSelection(selection);
    }

    /** The note painter's "is this note about to be erased", which draws the stripes: see {@link Marks}. */
    @Hook(target = bro.class, method = "cfM(Lcom/bitwig/graphics/SYo;LIbv;LkPd;DDDDDIJJZZZZZ)V", at = At.REPLACE_CALL,
          id = "note-stripes", callOwner = Ibv.class, call = "EUG", occurrence = 1)
    public static boolean noteStripes(Ibv note) {
        return Marks.noteStripes(note);
    }

    /**
     * Something Undo can undo has just changed: an empty method Bitwig's Undo recorder calls after each change it
     * hears to an undoable value or list, outside previews. See {@link Notices}.
     */
    @Hook(target = dfI.class, method = "BNq()V", at = At.ENTRY, id = "undoable-change")
    public static void undoableChange() {
        Notices.undoableChange();
    }

    /** Undo is about to run. */
    @Hook(target = dhu.class, method = "lYc()V", at = At.ENTRY, id = "undo")
    public static void undo() {
        Notices.undoOrRedo();
    }

    /** Redo is about to run. */
    @Hook(target = dhu.class, method = "wGc()V", at = At.ENTRY, id = "redo")
    public static void redo() {
        Notices.undoOrRedo();
    }

    /**
     * The engine's encoder of a note clip asks for the clip's played lanes: a heard preview adds its own lanes. See
     * {@link Heard}.
     */
    @Hook(target = HEL.class, method = "cfM(LlSj;)[B", at = At.REPLACE_CALL, id = "heard-lanes",
          callOwner = YeP.class, call = "wGc()Ljava/util/List;", occurrence = 1)
    public static List<?> heardLanes(YeP lanes) {
        return Heard.playedLanes(lanes);
    }

    /** The encoder asks for a lane's notes: a heard preview gives them as Apply would leave them. */
    @Hook(target = HEL.class, method = "cfM(LlSj;)[B", at = At.REPLACE_CALL, id = "heard-notes",
          callOwner = Rke.class, call = "cfM()Ljava/util/List;", occurrence = 1)
    public static List<?> heardNotes(Rke lane) {
        return Heard.laneNotes(lane);
    }
}
