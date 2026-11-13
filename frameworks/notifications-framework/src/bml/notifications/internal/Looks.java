package bml.notifications.internal;

import bitwig.obf.dve; // a widget
import bitwig.obf.ecp; // a wrapping text

import bml.notifications.TextLook;

/** Gives a text one of the looks Bitwig's own notification rows use. UI thread. */
final class Looks {
    private Looks() { }

    /** A new wrapping text with {@code text}, in {@code look}, colours and fonts from {@code row}'s theme. */
    static ecp text(dve row, String text, TextLook look) {
        ecp t = ecp.Hnt(text == null ? "" : text); // a wrapping text with that text
        apply(row, t, look);
        return t;
    }

    /** As Bitwig styles a row's title, an error title or a description. */
    static void apply(dve row, ecp text, TextLook look) {
        text.MJ(0);  // no margin, as Bitwig's task title
        text.UZr(0); // the same, the other side
        switch (look == null ? TextLook.SECONDARY : look) {
            case TITLE -> {
                text.cfM(row.jl_().Ai().wGc());                    // the theme's bold font
                text.cfM(row.PEn().cfM("Notification Normal"));    // the notification text colour
            }
            case ERROR_TITLE -> {
                text.cfM(row.jl_().Ai().wGc());                    // the theme's bold font
                text.cfM(row.PEn().cfM("Notification Error"));     // the notification error colour
            }
            case SECONDARY -> text.cfM(row.PEn().d3());            // the secondary text colour, default font
        }
    }
}
