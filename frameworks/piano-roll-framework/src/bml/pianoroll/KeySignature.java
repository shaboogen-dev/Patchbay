package bml.pianoroll;

import java.util.BitSet;

/** Bitwig's global key at a beat, as plain values: see {@link Clip#keySignatureAt}. */
public final class KeySignature {
    private final int root;
    private final BitSet scale;

    KeySignature(int root, BitSet scale) {
        this.root = Math.floorMod(root, 12);
        this.scale = (BitSet) scale.clone();
    }

    /** The key's root as a pitch class: 0 is C, 11 is B. */
    public int root() {
        return root;
    }

    /** Whether the scale holds this many semitones above the root, 0 to 11. */
    public boolean inScale(int semitonesAboveRoot) {
        return scale.get(Math.floorMod(semitonesAboveRoot, 12));
    }
}
