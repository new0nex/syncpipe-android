package org.schabi.newpipe.local.youtube;

import android.content.Context;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
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
import java.util.ArrayList;
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
        long[] subscriptionDetailIds = null;
        String historyError = null;
        String playlistError = null;
        String subscriptionsError = null;
        final List<String> errors = new ArrayList<>();

        if (selection.importHistory) {
            try {
                historyResult = new YoutubeWatchHistoryImporter(context).importFrom(file);
            } catch (final Exception e) {
                historyError = friendlyMessage(e);
                errors.add("history: " + historyError);
            }
        }

        if (selection.importPlaylists) {
            try {
                playlistResult = new YoutubePlaylistImporter(context).importFrom(file);
            } catch (final Exception e) {
                playlistError = friendlyMessage(e);
                errors.add("playlists: " + playlistError);
            }
        }

        if (selection.importSubscriptions) {
            try {
                final SubscriptionImportResult subscriptionResult = importSubscriptions(file);
                insertedSubscriptions = subscriptionResult.insertedSubscriptions;
                subscriptionDetailIds = subscriptionResult.detailSubscriptionIds;
            } catch (final Exception e) {
                subscriptionsError = friendlyMessage(e);
                errors.add("subscriptions: " + subscriptionsError);
            }
        }

        if (historyResult == null && playlistResult == null && insertedSubscriptions == null) {
            throw new IOException("No selected YouTube Takeout data could be imported. "
                    + String.join("; ", errors));
        }

        return new ImportResult(historyResult, playlistResult, insertedSubscriptions,
                subscriptionDetailIds, historyError, playlistError, subscriptionsError);
    }

    @NonNull
    private SubscriptionImportResult importSubscriptions(@NonNull final StoredFileHelper file)
            throws Exception {
        final byte[] subscriptionsCsv = readSubscriptionsCsv(file);
        final List<SubscriptionItem> subscriptionItems = NewPipe
                .getService(ServiceList.YouTube.getServiceId())
                .getSubscriptionExtractor()
                .fromInputStream(new ByteArrayInputStream(subscriptionsCsv), "csv");
        final SubscriptionManager subscriptionManager = new SubscriptionManager(context);
        final List<SubscriptionEntity> inserted =
                subscriptionManager.insertAll(subscriptionItems);
        final long[] idsWithoutAvatar = subscriptionManager.getIdsWithoutAvatar(subscriptionItems);
        if (idsWithoutAvatar.length > 0) {
            return new SubscriptionImportResult(inserted.size(), idsWithoutAvatar);
        }

        final long[] insertedIds = new long[inserted.size()];
        for (int i = 0; i < inserted.size(); i++) {
            insertedIds[i] = inserted.get(i).getUid();
        }
        return new SubscriptionImportResult(inserted.size(), insertedIds);
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
        throw new IOException("This ZIP does not contain YouTube subscriptions. "
                + "In Google Takeout, include YouTube and YouTube Music > subscriptions.");
    }

    @NonNull
    private static String friendlyMessage(@NonNull final Exception exception) {
        final String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        return message.trim();
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
        private final long[] subscriptionDetailIds;
        private final String historyError;
        private final String playlistError;
        private final String subscriptionsError;

        private ImportResult(final YoutubeWatchHistoryImporter.ImportResult historyResult,
                             final YoutubePlaylistImporter.ImportResult playlistResult,
                             final Integer insertedSubscriptions,
                             final long[] subscriptionDetailIds,
                             final String historyError,
                             final String playlistError,
                             final String subscriptionsError) {
            this.historyResult = historyResult;
            this.playlistResult = playlistResult;
            this.insertedSubscriptions = insertedSubscriptions;
            this.subscriptionDetailIds = subscriptionDetailIds;
            this.historyError = historyError;
            this.playlistError = playlistError;
            this.subscriptionsError = subscriptionsError;
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

        @NonNull
        public long[] getInsertedSubscriptionIds() {
            return subscriptionDetailIds == null ? new long[0] : subscriptionDetailIds;
        }

        public String getHistoryError() {
            return historyError;
        }

        public String getPlaylistError() {
            return playlistError;
        }

        public String getSubscriptionsError() {
            return subscriptionsError;
        }
    }

    private static final class SubscriptionImportResult {
        private final int insertedSubscriptions;
        private final long[] detailSubscriptionIds;

        private SubscriptionImportResult(final int insertedSubscriptions,
                                         @NonNull final long[] detailSubscriptionIds) {
            this.insertedSubscriptions = insertedSubscriptions;
            this.detailSubscriptionIds = detailSubscriptionIds;
        }
    }
}
