package io.github.slimjar.resolver.reader;

import com.google.gson.Gson;
import io.github.slimjar.resolver.data.DependencyData;

import java.io.*;

public final class GsonDependencyReader implements DependencyReader {
    private final Gson gson;

    public GsonDependencyReader(Gson gson) {
        this.gson = gson;
    }

    @Override
    public DependencyData read(final InputStream inputStream) throws IOException {
        final InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        return gson.fromJson(inputStreamReader, DependencyData.class);
    }
}