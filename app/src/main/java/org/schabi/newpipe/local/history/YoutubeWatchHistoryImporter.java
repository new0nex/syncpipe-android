package org.schabi.newpipe.local.history;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.history.dao.StreamHistoryDAO;
import org.schabi.newpipe.database.history.model.StreamHistoryEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.dao.StreamStateDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.streams.io.SharpInputStream;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class YoutubeWatchHistoryImporter {
    private static final String TAG = YoutubeWatchHistoryImporter.class.getSimpleName();
    private static final int ZIP_LOCAL_FILE_HEADER = 0x04034b50;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final long IMPORTED_WATCHED_PROGRESS_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final long IMPORTED_WATCHED_DURATION_SECONDS =
            TimeUnit.MILLISECONDS.toSeconds(IMPORTED_WATCHED_PROGRESS_MILLIS);

    private static final DateTimeFormatter TAKEOUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a 'GMT'XXX", Locale.US);

    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final StreamHistoryDAO streamHistoryTable;
    private final StreamStateDAO streamStateTable;

    public YoutubeWatchHistoryImporter(@NonNull final Context context) {
        database = NewPipeDatabase.getInstance(context);
        streamTable = database.streamDAO();
        streamHistoryTable = database.streamHistoryDAO();
        streamStateTable = database.streamStateDAO();
    }

    @NonNull
    public ImportResult importFrom(@NonNull final StoredFileHelper file) throws IOException {
        final ParsedHistory parsedHistory;
        try (SharpStream sharpStream = file.getStream();
             InputStream inputStream = new BufferedInputStream(new SharpInputStream(sharpStream))) {
            parsedHistory = parseInput(inputStream);
        }

        if (parsedHistory.importedVideos.isEmpty()) {
            throw new IOException("No supported YouTube watch history entries were found. "
                    + "Select a Google Takeout ZIP containing "
                    + "\"YouTube and YouTube Music/history/watch-history.html\".");
        }

        return database.runInTransaction(() -> importVideos(parsedHistory));
    }

    @NonNull
    public ImportResult previewFrom(@NonNull final StoredFileHelper file) throws IOException {
        final ParsedHistory parsedHistory;
        try (SharpStream sharpStream = file.getStream();
             InputStream inputStream = new BufferedInputStream(new SharpInputStream(sharpStream))) {
            parsedHistory = parseInput(inputStream);
        }

        if (parsedHistory.importedVideos.isEmpty()) {
            throw new IOException("No supported YouTube watch history entries were found. "
                    + "Select a Google Takeout ZIP containing "
                    + "\"YouTube and YouTube Music/history/watch-history.html\".");
        }

        int totalHistoryEntries = 0;
        for (final ImportedVideo video : parsedHistory.importedVideos.values()) {
            totalHistoryEntries += video.repeatCount;
        }
        return new ImportResult(parsedHistory.importedVideos.size(), totalHistoryEntries,
                parsedHistory.skippedCards);
    }

    @NonNull
    private ImportResult importVideos(@NonNull final ParsedHistory parsedHistory) {
        int imported = 0;
        int skipped = parsedHistory.skippedCards;
        int totalHistoryEntries = 0;

        for (final ImportedVideo video : parsedHistory.importedVideos.values()) {
            totalHistoryEntries += video.repeatCount;

            final StreamInfoItem item = new StreamInfoItem(ServiceList.YouTube.getServiceId(),
                    video.url, video.title, StreamType.VIDEO_STREAM);
            item.setUploaderName(video.channelName);
            item.setUploaderUrl(video.channelUrl);
            item.setThumbnailUrl("https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg");

            final StreamEntity streamEntity = new StreamEntity(item);
            if (streamEntity.getDuration() <= 0) {
                streamEntity.setDuration(IMPORTED_WATCHED_DURATION_SECONDS);
            }
            final long streamId = streamTable.upsert(streamEntity);
            final long existingRepeatCount = streamHistoryTable.getTotalRepeatCount(streamId);
            final Long existingLatestMillis = streamHistoryTable.getLatestAccessDateMillis(streamId);
            final long importedLatestMillis = video.latestAccessDate
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
            final long mergedLatestMillis = existingLatestMillis == null
                    ? importedLatestMillis : Math.max(existingLatestMillis, importedLatestMillis);
            final long mergedRepeatCount = Math.max(existingRepeatCount, video.repeatCount);

            if (mergedRepeatCount <= 0 || mergedLatestMillis <= 0) {
                skipped++;
                continue;
            }

            streamHistoryTable.deleteStreamHistory(streamId);
            streamHistoryTable.insert(new StreamHistoryEntity(streamId,
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(mergedLatestMillis),
                            ZoneOffset.UTC),
                    mergedRepeatCount));
            streamStateTable.upsert(new StreamStateEntity(streamId,
                    getWatchedProgressMillis(streamEntity)));
            imported++;
        }

        return new ImportResult(imported, totalHistoryEntries, skipped);
    }

    private static long getWatchedProgressMillis(@NonNull final StreamEntity streamEntity) {
        return streamEntity.getDuration() > 0
                ? streamEntity.getDuration() * 1000 : IMPORTED_WATCHED_PROGRESS_MILLIS;
    }

    @NonNull
    private ParsedHistory parseInput(@NonNull final InputStream inputStream)
            throws IOException {
        final BufferedInputStream bufferedInputStream = inputStream instanceof BufferedInputStream
                ? (BufferedInputStream) inputStream : new BufferedInputStream(inputStream);
        bufferedInputStream.mark(4);
        final int signature = readLittleEndianInt(bufferedInputStream);
        bufferedInputStream.reset();

        if (signature == ZIP_LOCAL_FILE_HEADER) {
            return parseZip(bufferedInputStream);
        }

        return parseHtml(bufferedInputStream);
    }

    @NonNull
    private ParsedHistory parseZip(@NonNull final InputStream inputStream)
            throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                final String name = entry.getName();
                if (!entry.isDirectory() && name != null
                        && name.toLowerCase(Locale.ROOT).endsWith("watch-history.html")) {
                    return parseHtml(new ByteArrayInputStream(readEntry(zipInputStream)));
                }
            }
        }
        throw new IOException("This ZIP does not contain YouTube watch history. "
                + "In Google Takeout, include YouTube and YouTube Music > history.");
    }

    @NonNull
    private ParsedHistory parseHtml(@NonNull final InputStream inputStream)
            throws IOException {
        final Document document = Jsoup.parse(inputStream, "UTF-8", "");
        final Map<String, ImportedVideo> videos = new LinkedHashMap<>();
        int skippedCards = 0;

        for (final Element card : document.select(".outer-cell")) {
            final ParsedEntry entry = parseCard(card);
            if (entry == null) {
                skippedCards++;
                continue;
            }

            final ImportedVideo video = videos.get(entry.videoId);
            if (video == null) {
                videos.put(entry.videoId, new ImportedVideo(entry));
            } else {
                video.merge(entry);
            }
        }

        Log.i(TAG, "Parsed " + videos.size() + " unique videos from YouTube watch history; "
                + "skipped " + skippedCards + " cards");
        return new ParsedHistory(videos, skippedCards);
    }

    @Nullable
    private ParsedEntry parseCard(@NonNull final Element card) {
        final Element videoLink = card.selectFirst("a[href*=youtube.com/watch]");
        if (videoLink == null) {
            return null;
        }

        final String videoId = extractVideoId(videoLink.attr("href"));
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }

        final String title = videoLink.text().trim();
        if (title.isEmpty()) {
            return null;
        }

        final Element contentCell = videoLink.parent();
        if (contentCell == null) {
            return null;
        }

        final OffsetDateTime watchedAt = parseDate(lastNonEmptyLine(contentCell.wholeText()));
        if (watchedAt == null) {
            return null;
        }

        String channelName = "";
        String channelUrl = null;
        final Elements links = contentCell.select("a[href*=youtube.com]");
        for (final Element link : links) {
            final String href = link.attr("href");
            if (!href.contains("/watch")) {
                channelName = link.text().trim();
                channelUrl = href;
                break;
            }
        }

        return new ParsedEntry(videoId, "https://www.youtube.com/watch?v=" + videoId,
                title, channelName, channelUrl, watchedAt);
    }

    @Nullable
    private static String extractVideoId(@NonNull final String rawUrl) {
        final Uri uri = Uri.parse(rawUrl);
        final String watchId = uri.getQueryParameter("v");
        if (watchId != null && !watchId.isEmpty()) {
            return watchId;
        }

        final String path = uri.getPath();
        if (path == null) {
            return null;
        }

        final String shortsPrefix = "/shorts/";
        if (path.startsWith(shortsPrefix) && path.length() > shortsPrefix.length()) {
            return path.substring(shortsPrefix.length()).split("/", 2)[0];
        }

        return null;
    }

    @Nullable
    private static OffsetDateTime parseDate(@NonNull final String rawDate) {
        String normalized = rawDate.trim()
                .replace('\u00a0', ' ')
                .replace('\u202f', ' ')
                .replace('\u2007', ' ')
                .replace("Sept ", "Sep ");
        try {
            return OffsetDateTime.parse(normalized, TAKEOUT_DATE_FORMATTER);
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    @NonNull
    private static String lastNonEmptyLine(@NonNull final String text) {
        final String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            final String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private static int readLittleEndianInt(@NonNull final InputStream inputStream)
            throws IOException {
        final int b0 = inputStream.read();
        final int b1 = inputStream.read();
        final int b2 = inputStream.read();
        final int b3 = inputStream.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            return -1;
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
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

    public static final class ImportResult {
        private final int importedVideos;
        private final int historyEntries;
        private final int skippedVideos;

        private ImportResult(final int importedVideos, final int historyEntries,
                             final int skippedVideos) {
            this.importedVideos = importedVideos;
            this.historyEntries = historyEntries;
            this.skippedVideos = skippedVideos;
        }

        public int getImportedVideos() {
            return importedVideos;
        }

        public int getHistoryEntries() {
            return historyEntries;
        }

        public int getSkippedVideos() {
            return skippedVideos;
        }

        public int getMergedRepeatEvents() {
            return Math.max(0, historyEntries - importedVideos);
        }
    }

    private static final class ParsedHistory {
        private final Map<String, ImportedVideo> importedVideos;
        private final int skippedCards;

        private ParsedHistory(@NonNull final Map<String, ImportedVideo> importedVideos,
                              final int skippedCards) {
            this.importedVideos = importedVideos;
            this.skippedCards = skippedCards;
        }
    }

    private static final class ParsedEntry {
        private final String videoId;
        private final String url;
        private final String title;
        private final String channelName;
        @Nullable private final String channelUrl;
        private final OffsetDateTime watchedAt;

        private ParsedEntry(@NonNull final String videoId,
                            @NonNull final String url,
                            @NonNull final String title,
                            @NonNull final String channelName,
                            @Nullable final String channelUrl,
                            @NonNull final OffsetDateTime watchedAt) {
            this.videoId = videoId;
            this.url = url;
            this.title = title;
            this.channelName = channelName;
            this.channelUrl = channelUrl;
            this.watchedAt = watchedAt;
        }
    }

    private static final class ImportedVideo {
        private final String videoId;
        private final String url;
        private String title;
        private String channelName;
        @Nullable private String channelUrl;
        private OffsetDateTime latestAccessDate;
        private int repeatCount;

        private ImportedVideo(@NonNull final ParsedEntry entry) {
            videoId = entry.videoId;
            url = entry.url;
            title = entry.title;
            channelName = entry.channelName;
            channelUrl = entry.channelUrl;
            latestAccessDate = entry.watchedAt;
            repeatCount = 1;
        }

        private void merge(@NonNull final ParsedEntry entry) {
            repeatCount++;
            if (entry.watchedAt.isAfter(latestAccessDate)) {
                latestAccessDate = entry.watchedAt;
                title = entry.title;
                channelName = entry.channelName;
                channelUrl = entry.channelUrl;
            }
        }
    }
}
