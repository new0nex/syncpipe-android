package org.schabi.newpipe.local.playlist;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistRemoteDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class YoutubePlaylistImporter {
    private static final String TAG = YoutubePlaylistImporter.class.getSimpleName();
    private static final int ZIP_LOCAL_FILE_HEADER = 0x04034b50;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String LOCAL_PLAYLIST_PREFIX = "YouTube - ";

    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final PlaylistDAO playlistTable;
    private final PlaylistStreamDAO playlistStreamTable;
    private final PlaylistRemoteDAO playlistRemoteTable;

    public YoutubePlaylistImporter(@NonNull final Context context) {
        database = NewPipeDatabase.getInstance(context);
        streamTable = database.streamDAO();
        playlistTable = database.playlistDAO();
        playlistStreamTable = database.playlistStreamDAO();
        playlistRemoteTable = database.playlistRemoteDAO();
    }

    @NonNull
    public ImportResult importFrom(@NonNull final StoredFileHelper file) throws IOException {
        final ParsedTakeout parsedTakeout;
        try (SharpStream sharpStream = file.getStream();
             InputStream inputStream = new BufferedInputStream(new SharpInputStream(sharpStream))) {
            parsedTakeout = parseInput(inputStream);
        }

        if (parsedTakeout.playlists.isEmpty()) {
            throw new IOException("No supported YouTube playlists were found. "
                    + "Select a Google Takeout ZIP containing "
                    + "\"YouTube and YouTube Music/playlists/playlists.csv\".");
        }

        return database.runInTransaction(() -> importPlaylists(parsedTakeout));
    }

    @NonNull
    public ImportResult previewFrom(@NonNull final StoredFileHelper file) throws IOException {
        final ParsedTakeout parsedTakeout;
        try (SharpStream sharpStream = file.getStream();
             InputStream inputStream = new BufferedInputStream(new SharpInputStream(sharpStream))) {
            parsedTakeout = parseInput(inputStream);
        }

        if (parsedTakeout.playlists.isEmpty()) {
            throw new IOException("No supported YouTube playlists were found. "
                    + "Select a Google Takeout ZIP containing "
                    + "\"YouTube and YouTube Music/playlists/playlists.csv\".");
        }

        return countPlaylists(parsedTakeout);
    }

    @NonNull
    private ImportResult importPlaylists(@NonNull final ParsedTakeout parsedTakeout) {
        final ImportResult result = countPlaylists(parsedTakeout);
        int localVideos = 0;
        for (final TakeoutPlaylist playlist : parsedTakeout.playlists.values()) {
            if (playlist.title.isEmpty()) {
                continue;
            }

            if (playlist.isOnlineFollowable()) {
                importRemotePlaylist(playlist);
            } else {
                localVideos += importLocalPlaylist(playlist);
            }
        }

        Log.i(TAG, "Imported YouTube playlists: " + result.getLocalPlaylists() + " local, "
                + result.getRemotePlaylists() + " remote, "
                + result.getSkippedPlaylists() + " skipped");
        return new ImportResult(result.getLocalPlaylists(), localVideos,
                result.getRemotePlaylists(), result.getSkippedPlaylists());
    }

    @NonNull
    private ImportResult countPlaylists(@NonNull final ParsedTakeout parsedTakeout) {
        int localPlaylists = 0;
        int localVideos = 0;
        int remotePlaylists = 0;
        int skippedPlaylists = 0;

        for (final TakeoutPlaylist playlist : parsedTakeout.playlists.values()) {
            if (playlist.title.isEmpty()) {
                skippedPlaylists++;
                continue;
            }

            if (playlist.isOnlineFollowable()) {
                remotePlaylists++;
            } else {
                localVideos += playlist.videoIds.size();
                localPlaylists++;
            }
        }
        return new ImportResult(localPlaylists, localVideos, remotePlaylists, skippedPlaylists);
    }

    private void importRemotePlaylist(@NonNull final TakeoutPlaylist playlist) {
        final String thumbnailUrl = playlist.videoIds.isEmpty()
                ? "" : thumbnailUrlFor(playlist.videoIds.get(0));
        final PlaylistRemoteEntity remotePlaylist = new PlaylistRemoteEntity(
                ServiceList.YouTube.getServiceId(),
                playlist.title,
                playlistUrlFor(playlist.playlistId),
                thumbnailUrl,
                "YouTube",
                (long) playlist.videoIds.size());
        playlistRemoteTable.upsert(remotePlaylist);
    }

    private int importLocalPlaylist(@NonNull final TakeoutPlaylist playlist) {
        final String localPlaylistName = LOCAL_PLAYLIST_PREFIX + playlist.title;
        final String thumbnailUrl = playlist.videoIds.isEmpty()
                ? "" : thumbnailUrlFor(playlist.videoIds.get(0));
        final Long existingPlaylistId = playlistTable.getPlaylistIdByName(localPlaylistName);
        final long playlistId;
        if (existingPlaylistId == null) {
            playlistId = playlistTable.insert(new PlaylistEntity(localPlaylistName, thumbnailUrl, -1));
        } else {
            playlistId = existingPlaylistId;
            final PlaylistEntity updatedPlaylist =
                    new PlaylistEntity(localPlaylistName, thumbnailUrl, -1);
            updatedPlaylist.setUid(playlistId);
            playlistTable.update(updatedPlaylist);
            playlistStreamTable.deleteBatch(playlistId);
        }

        final List<PlaylistStreamEntity> playlistStreams = new ArrayList<>(playlist.videoIds.size());
        int index = 0;
        for (final String videoId : playlist.videoIds) {
            final long streamId = getOrCreateStream(videoId);
            playlistStreams.add(new PlaylistStreamEntity(playlistId, streamId, index++));
        }

        if (!playlistStreams.isEmpty()) {
            playlistStreamTable.insertAll(playlistStreams);
        }
        return playlistStreams.size();
    }

    private long getOrCreateStream(@NonNull final String videoId) {
        final String streamUrl = streamUrlFor(videoId);
        final Long existingStreamId = streamTable.getStreamId(ServiceList.YouTube.getServiceId(),
                streamUrl);
        if (existingStreamId != null) {
            return existingStreamId;
        }

        final StreamInfoItem item = new StreamInfoItem(ServiceList.YouTube.getServiceId(),
                streamUrl, "YouTube video " + videoId, StreamType.VIDEO_STREAM);
        item.setThumbnailUrl(thumbnailUrlFor(videoId));
        return streamTable.upsert(new StreamEntity(item));
    }

    @NonNull
    private ParsedTakeout parseInput(@NonNull final InputStream inputStream) throws IOException {
        final BufferedInputStream bufferedInputStream = inputStream instanceof BufferedInputStream
                ? (BufferedInputStream) inputStream : new BufferedInputStream(inputStream);
        bufferedInputStream.mark(4);
        final int signature = readLittleEndianInt(bufferedInputStream);
        bufferedInputStream.reset();

        if (signature != ZIP_LOCAL_FILE_HEADER) {
            throw new IOException("Please select a Google Takeout ZIP containing YouTube "
                    + "playlists.csv, not an extracted CSV file.");
        }

        return parseZip(bufferedInputStream);
    }

    @NonNull
    private ParsedTakeout parseZip(@NonNull final InputStream inputStream) throws IOException {
        final ParsedTakeout parsedTakeout = new ParsedTakeout();
        final Map<String, byte[]> playlistVideoFiles = new LinkedHashMap<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                final String name = entry.getName();
                if (entry.isDirectory() || name == null
                        || !name.toLowerCase(Locale.ROOT).contains("/playlists/")) {
                    continue;
                }

                final String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith("/playlists/playlists.csv")) {
                    parsePlaylistsCsv(parsedTakeout,
                            new ByteArrayInputStream(readEntry(zipInputStream)));
                } else if (lowerName.endsWith("-videos.csv")) {
                    playlistVideoFiles.put(displayNameFromVideosFile(name),
                            readEntry(zipInputStream));
                }
            }
        }

        for (final Map.Entry<String, byte[]> videoFile : playlistVideoFiles.entrySet()) {
            final TakeoutPlaylist playlist = parsedTakeout.findByTitle(videoFile.getKey());
            if (playlist != null) {
                playlist.videoIds.addAll(parsePlaylistVideosCsv(
                        new ByteArrayInputStream(videoFile.getValue())));
            }
        }

        return parsedTakeout;
    }

    private void parsePlaylistsCsv(@NonNull final ParsedTakeout parsedTakeout,
                                   @NonNull final InputStream inputStream) throws IOException {
        final List<List<String>> rows = parseCsv(inputStream);
        if (rows.size() < 2) {
            return;
        }

        final Map<String, Integer> header = headerMap(rows.get(0));
        for (int i = 1; i < rows.size(); i++) {
            final List<String> row = rows.get(i);
            final String playlistId = value(row, header, "Playlist ID");
            final String title = value(row, header, "Playlist Title (Original)");
            final String visibility = value(row, header, "Playlist Visibility");
            if (!playlistId.isEmpty() && !title.isEmpty()) {
                parsedTakeout.playlists.put(title, new TakeoutPlaylist(
                        playlistId, title, visibility));
            }
        }
    }

    @NonNull
    private List<String> parsePlaylistVideosCsv(@NonNull final InputStream inputStream)
            throws IOException {
        final List<List<String>> rows = parseCsv(inputStream);
        final List<String> videoIds = new ArrayList<>();
        if (rows.size() < 2) {
            return videoIds;
        }

        final Map<String, Integer> header = headerMap(rows.get(0));
        for (int i = 1; i < rows.size(); i++) {
            final String videoId = value(rows.get(i), header, "Video ID");
            if (!videoId.isEmpty()) {
                videoIds.add(videoId);
            }
        }
        return videoIds;
    }

    @NonNull
    private static List<List<String>> parseCsv(@NonNull final InputStream inputStream)
            throws IOException {
        final String csv = new String(readEntry(inputStream), StandardCharsets.UTF_8);
        final List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < csv.length(); i++) {
            final char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                addCsvRow(rows, row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }

        row.add(field.toString());
        addCsvRow(rows, row);
        return rows;
    }

    private static void addCsvRow(@NonNull final List<List<String>> rows,
                                  @NonNull final List<String> row) {
        for (final String field : row) {
            if (!field.trim().isEmpty()) {
                rows.add(row);
                return;
            }
        }
    }

    @NonNull
    private static Map<String, Integer> headerMap(@NonNull final List<String> headerRow) {
        final Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            header.put(headerRow.get(i).trim(), i);
        }
        return header;
    }

    @NonNull
    private static String value(@NonNull final List<String> row,
                                @NonNull final Map<String, Integer> header,
                                @NonNull final String name) {
        final Integer index = header.get(name);
        if (index == null || index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }

    @NonNull
    private static String displayNameFromVideosFile(@NonNull final String name) {
        final int slash = name.lastIndexOf('/');
        final String fileName = slash >= 0 ? name.substring(slash + 1) : name;
        final String suffix = "-videos.csv";
        if (fileName.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            return fileName.substring(0, fileName.length() - suffix.length());
        }
        return fileName;
    }

    @NonNull
    private static String playlistUrlFor(@NonNull final String playlistId) {
        return "https://www.youtube.com/playlist?list=" + playlistId;
    }

    @NonNull
    private static String streamUrlFor(@NonNull final String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    @NonNull
    private static String thumbnailUrlFor(@NonNull final String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
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
        private final int localPlaylists;
        private final int localVideos;
        private final int remotePlaylists;
        private final int skippedPlaylists;

        private ImportResult(final int localPlaylists, final int localVideos,
                             final int remotePlaylists, final int skippedPlaylists) {
            this.localPlaylists = localPlaylists;
            this.localVideos = localVideos;
            this.remotePlaylists = remotePlaylists;
            this.skippedPlaylists = skippedPlaylists;
        }

        public int getLocalPlaylists() {
            return localPlaylists;
        }

        public int getLocalVideos() {
            return localVideos;
        }

        public int getRemotePlaylists() {
            return remotePlaylists;
        }

        public int getSkippedPlaylists() {
            return skippedPlaylists;
        }
    }

    private static final class ParsedTakeout {
        private final Map<String, TakeoutPlaylist> playlists = new LinkedHashMap<>();

        @Nullable
        private TakeoutPlaylist findByTitle(@NonNull final String title) {
            return playlists.get(title);
        }
    }

    private static final class TakeoutPlaylist {
        private final String playlistId;
        private final String title;
        private final String visibility;
        private final List<String> videoIds = new ArrayList<>();

        private TakeoutPlaylist(@NonNull final String playlistId,
                                @NonNull final String title,
                                @NonNull final String visibility) {
            this.playlistId = playlistId;
            this.title = title;
            this.visibility = visibility;
        }

        private boolean isOnlineFollowable() {
            final String normalizedVisibility = visibility.toLowerCase(Locale.ROOT);
            return normalizedVisibility.equals("public")
                    || normalizedVisibility.equals("unlisted");
        }
    }
}
