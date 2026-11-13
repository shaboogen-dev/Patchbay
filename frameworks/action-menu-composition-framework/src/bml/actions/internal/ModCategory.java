package bml.actions.internal;

import bitwig.obf.dmS; // Bitwig's action category

/** A mod's own action category; answers Bitwig's label lookup with the mod's label. */
final class ModCategory extends dmS {
    private final String label;

    ModCategory(String categoryId, String label) {
        super(categoryId);
        this.label = label;
    }

    /** Bitwig's label lookup for a category. */
    @Override
    public String Hnt() {
        return label;
    }
}
