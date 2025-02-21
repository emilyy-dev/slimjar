package io.github.slimjar.relocation;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

public interface Relocator {
    void relocate(final File input, final File output) throws IOException;
}
