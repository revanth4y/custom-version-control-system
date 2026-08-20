package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.User;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.TextContent;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.BlobResponse;
import com.gitforge.vcsapi.dto.DirectoryResponse;
import com.gitforge.vcsapi.dto.PutContentRequest;
import com.gitforge.vcsapi.dto.TreeEntryResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Browsing and writing repository contents.
 *
 * <p>Everything is served from the immutable object store; nothing is
 * materialised to disk to answer a request.
 */
@Service
public class ContentApiService {

    private static final String UTF_8 = "utf-8";
    private static final String BASE_64 = "base64";

    private final VcsRepositoryProvider repositories;
    private final CommitApiService commits;

    public ContentApiService(VcsRepositoryProvider repositories, CommitApiService commits) {
        this.repositories = repositories;
        this.commits = commits;
    }

    public DirectoryResponse listDirectory(String owner, String name, User viewer, String ref, String path) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        String revision = revisionOrHead(ref);

        List<TreeEntry> entries = repository.reader().listDirectory(revision, path)
                .orElseThrow(() -> new NotFoundException("No such directory at " + revision + ": "
                        + (path == null || path.isBlank() ? "/" : path)));

        return new DirectoryResponse(
                revision,
                path == null ? "" : path,
                entries.stream().map(entry -> TreeEntryResponse.from(entry, path)).toList());
    }

    public BlobResponse readBlob(String owner, String name, User viewer, String ref, String path) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        String revision = revisionOrHead(ref);

        if (path == null || path.isBlank()) {
            throw new BadRequestException("A file path is required");
        }
        TreeEntry entry = repository.reader().entryAt(revision, path)
                .filter(candidate -> !candidate.isDirectory())
                .orElseThrow(() -> new NotFoundException("No such file at " + revision + ": " + path));

        byte[] content = repository.reader().readFile(revision, path)
                .orElseThrow(() -> new NotFoundException("No such file at " + revision + ": " + path));

        return describe(path, entry, content);
    }

    /** Writes a single file, as one commit. */
    public com.gitforge.vcsapi.dto.CommitSummaryResponse putContent(
            String owner, String name, User viewer, PutContentRequest request) {

        FileChange change = FileChange.put(
                request.path(),
                decode(request.content(), request.encoding()),
                modeOf(request.mode()));

        return commits.commit(owner, name, viewer, request.branch(), request.message(), List.of(change));
    }

    /**
     * Presents a blob in a form JSON can carry without loss.
     *
     * <p>Text or binary is decided by {@link TextContent}, the same rule the diff
     * engine uses, so a file returned here as base64 is exactly the file the
     * differ declines to line-diff.
     */
    private static BlobResponse describe(String path, TreeEntry entry, byte[] content) {
        Optional<String> text = TextContent.asText(content);
        boolean binary = text.isEmpty();

        return new BlobResponse(
                path,
                entry.id().toHex(),
                entry.mode().value(),
                content.length,
                binary,
                binary ? BASE_64 : UTF_8,
                binary ? Base64.getEncoder().encodeToString(content) : text.get());
    }

    static byte[] decode(String content, String encoding) {
        if (content == null) {
            throw new BadRequestException("Content is required");
        }
        if (BASE_64.equalsIgnoreCase(encoding)) {
            try {
                return Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Content is not valid base64");
            }
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    static FileMode modeOf(String mode) {
        if (mode == null || mode.isBlank()) {
            return FileMode.REGULAR_FILE;
        }
        try {
            FileMode parsed = FileMode.fromValue(mode);
            if (parsed.isDirectory()) {
                throw new BadRequestException("A file cannot have a directory mode");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported file mode: " + mode);
        }
    }

    private static String revisionOrHead(String ref) {
        return ref == null || ref.isBlank() ? "HEAD" : ref;
    }
}
