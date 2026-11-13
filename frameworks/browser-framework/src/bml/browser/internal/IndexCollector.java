package bml.browser.internal;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.CH; // a library result (a file)
import bitwig.obf.Ihc; // a query run's context
import bitwig.obf.PIR; // what a query run does next: CONTINUE, TERMINATE, SKIP_DIRECTORY
import bitwig.obf.Ph; // a file the query run is at
import bitwig.obf.pAe; // anything the query run is at
import bitwig.obf.sTA; // a query run's visitor

/**
 * Collects the files a library query matches, in the order the library visits them, up to a limit,
 * as Bitwig's own collectors do ({@code DI}, which isn't on every Bitwig version this framework runs
 * on): the run hands each result to {@link #cfM(pAe)}, and calls {@link #MRY()} when the result
 * matches; the collector takes the file and stops the run at the limit. Runs on the library's thread.
 */
final class IndexCollector implements sTA {
    private final int limit;
    private final List<CH> found = new ArrayList<>();
    private Ph current;

    IndexCollector(int limit) {
        this.limit = limit;
    }

    List<CH> found() {
        return found;
    }

    @Override
    public void cfM(Ihc context) {
    }

    /** The run is at a result: a file is of interest, anything else not. */
    @Override
    public sTA cfM(pAe result) {
        if (!(result instanceof Ph file)) return null;
        current = file;
        return this;
    }

    @Override
    public PIR cfM() {
        return PIR.cfM; // CONTINUE
    }

    /** The result matches: take its file. */
    @Override
    public PIR MRY() {
        if (current != null && found.size() < limit) found.add(current.NC()); // a new result object for the file
        return found.size() >= limit ? PIR.MRY : PIR.cfM; // TERMINATE at the limit, else CONTINUE
    }

    @Override
    public PIR Hnt() {
        return PIR.cfM; // CONTINUE
    }

    @Override
    public void FzB() {
    }

    @Override
    public void IwV() {
    }

    @Override
    public void UNj() {
    }

    @Override
    public void hUR() {
    }
}
