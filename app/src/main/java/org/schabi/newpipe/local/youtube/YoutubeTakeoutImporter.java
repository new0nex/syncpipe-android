package org.schabi.newpipe.local.youtube;

import android.content.Context;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;
import org.schabi.newpipe.local.history.YoutubeWatchHistoryImporter;
import org.schabi.newpipe.local.playlist.YoutubePlaylistImporter;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.streams.io.SharpInputStream;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class YoutubeTakeoutImporter {
    private static final int BUFFER_SIZE = 16 * 1024;

    private final Context context;

    public YoutubeTakeoutImporter(@NonNull final Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    public ImportResult importFrom(@NonNull final StoredFileHelper file,
                                   @NonNull final ImportSelection selection) throws Exception {
        YoutubeWatchHistoryImporter.ImportResult historyResult = null;
        YoutubePlaylistImporter.ImportResult playlistResult = null;
        Integer insertedSubscriptions = null;

        if (selection.importHistory) {
            historyResult = new YoutubeWatchHistoryImporter(context).importFrom(file);
        }

        if (selection.importPlaylists) {
            playlistResult = new YoutubePlaylistImporter(context).importFrom(file);
        }

        if (selection.importSubscriptions) {
            insertedSubscriptions = importSubscriptions(file);
        }

        return new ImportResult(historyResult, playlistResult, insertedSubscriptions);
    }

    private int importSubscriptions(@NonNull final StoredFileHelper file) throws Exception {
        final byte[] subscriptionsCsv = readSubscriptionsCsv(file);
        final List<SubscriptionItem> subscriptionItems = NewPipe
                .getService(ServiceList.YouTube.getServiceId())
                .getSubscriptionExtractor()
                .fromInputStream(new ByteArrayInputStream(subscriptionsCsv), "csv");
        return new SubscriptionManager(context).insertAll(subscriptionItems).size();
    }

    @NonNull
    private byte[] readSubscriptionsCsv(@NonNull final StoredFileHelper file) throws IOException {
        try (SharpStream sharpStream = file.getStream();
             InputStream inputStream = new BufferedInputStream(new SharpInputStream(sharpStream));
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                final String name = entry.getName();
                if (!entry.isDirectory()
                        && name != null
                        && name.toLowerCase(Locale.ROOT).endsWith("/subscriptions/subscriptions.csv")) {
                    return readEntry(zipInputStream);
                }
            }
        }
        throw new IOException("Takeout zip does not contain subscriptions.csv");
    }

    @NonNull
    private static byte[] readEntry(@NonNull final InputStream inputStream) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
        return outputStream.toByteArray();
    }

    public static final class ImportSelection {
        private final boolean importHistory;
        private final boolean importPlaylists;
        private final boolean importSubscriptions;

        public ImportSelection(final boolean importHistory,
                               final boolean importPlaylists,
                               final boolean importSubscriptions) {
            this.importHistory = importHistory;
            this.importPlaylists = importPlaylists;
            this.importSubscriptions = importSubscriptions;
        }

        public boolean hasAnySelection() {
            return importHistory || importPlaylists || importSubscriptions;
        }
    }

    public static final class ImportResult {
        private final YoutubeWatchHistoryImporter.ImportResult historyResult;
        private final YoutubePlaylistImporter.ImportResult playlistResult;
        private final Integer insertedSubscriptions;

        private ImportResult(final YoutubeWatchHistoryImporter.ImportResult historyResult,
                             final YoutubePlaylistImporter.ImportResult playlistResult,
                             final Integer insertedSubscriptions) {
            this.historyResult = historyResult;
            this.playlistResult = playlistResult;
            this.insertedSubscriptions = insertedSubscriptions;
        }

        public YoutubeWatchHistoryImporter.ImportResult getHistoryResult() {
            return historyResult;
        }

        public YoutubePlaylistImporter.ImportResult getPlaylistResult() {
            return playlistResult;
        }

        public Integer getInsertedSubscriptions() {
            return insertedSubscriptions;
        }
    }
}
