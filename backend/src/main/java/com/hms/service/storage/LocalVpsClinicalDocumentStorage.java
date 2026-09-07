package com.hms.service.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Clinical documents on a private directory of the server's own filesystem.
 *
 * <p>The directory is deliberately not anywhere a web server publishes. There is no URL for a
 * patient's blood report -- the only way to read one is through the authenticated endpoint above
 * this class, which resolves the document tenant-scoped first. That makes the privacy property
 * structural rather than a setting somebody could switch off.
 *
 * <p>Keys are generated here from random UUIDs. Nothing in a stored path comes from a patient's
 * name, the original filename, or anything else a person could guess or that would leak who a
 * document belongs to if the directory listing were ever seen.
 *
 * <h2>Why nothing here works with whole paths</h2>
 *
 * <p>Checking a path and then using it is two operations, and an attacker who can create entries
 * inside the root gets to act in between: validate {@code <root>/h42} while it does not exist,
 * plant {@code h42 -> /elsewhere}, and the create that follows walks straight through the link.
 * No amount of re-checking closes that -- the check and the syscall resolve the name separately.
 *
 * <h2>What the root is assumed to be</h2>
 *
 * <p>The configured directory is private to the service account: {@code 0700}, owned by the user
 * the application runs as, and nothing else on the host writes inside it. That is enforced here
 * as far as a process can enforce it -- the root is refused if it is a symlink, and every
 * directory this class creates is owner-only from the moment it exists -- and it is the boundary
 * the guarantees below are stated against. A hospital user reaching the API cannot create a file,
 * a directory or a symlink inside the root; only this class does. An attacker who already has the
 * service account's own filesystem authority is a different situation entirely: they can read the
 * documents directly, and no arrangement of Java filesystem calls changes that.
 *
 * <p>What is guaranteed against concurrent legitimate requests, which is the case that actually
 * happens: no document overwrites another, no request deletes another's file, nothing escapes
 * the root, and no half-written file is ever reachable as a document.
 *
 * <h2>What a failed write leaves behind</h2>
 *
 * <p>A write that fails part-way leaves its file on disk, deliberately. Removing it would mean
 * unlinking by name, and a name is not a handle: Linux reuses an inode the instant it is freed,
 * so the identity check that would make such a delete safe cannot actually tell this file from
 * one that replaced it. Java offers no unlink through the descriptor already held. Between
 * leaving a few bytes nobody can reach and holding a delete that could, on a bad day, remove a
 * different patient's report, the orphan is the safer half of the trade.
 *
 * <p>Nothing points at it. The key is a fresh UUID that this call never returns, and the caller
 * records a document only when store() succeeds, so no row and no API path names it. A
 * maintenance sweep for such files belongs in operations, not in an upload request.
 *
 * <p>So every directory step is taken through a {@link SecureDirectoryStream}, which resolves
 * names against an already-open descriptor for the directory above, with links refused at each
 * step. Once a descriptor is held it keeps referring to the directory it was opened on, whatever
 * gets renamed or replaced afterwards. Directory creation -- the one thing that API has no
 * relative form of -- is done by creating the directory directly in the root (whose own path is
 * operator-owned, not attacker-writable) and moving it into place with the descriptor-relative
 * move, which is a {@code renameat} and cannot traverse a link either.
 */
@Component
public class LocalVpsClinicalDocumentStorage implements ClinicalDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalVpsClinicalDocumentStorage.class);

    /** Only these may appear in a key we generate, so a key can never be a path expression. */
    private static final String KEY_CHARS = "[A-Za-z0-9_-]+";

    /** A directory that keeps failing to open or appear is refused rather than retried forever. */
    private static final int MAX_DIRECTORY_ATTEMPTS = 3;

    private final String configuredRoot;
    private final Environment environment;
    private Path storageRoot;

    public LocalVpsClinicalDocumentStorage(
            @Value("${hms.document-storage.root:}") String configuredRoot,
            Environment environment) {
        this.configuredRoot = configuredRoot;
        this.environment = environment;
    }

    @PostConstruct
    void initialise() {
        String root = configuredRoot == null ? "" : configuredRoot.trim();

        if (root.isEmpty()) {
            // Production must not quietly invent somewhere to put clinical records. A default
            // under the working directory survives exactly until the next deploy, and the
            // documents would be gone with no error ever having been raised.
            if (isProduction()) {
                throw new IllegalStateException(
                        "HMS_DOCUMENT_STORAGE_PATH is not set. Production requires an explicit "
                                + "private directory for patient documents, on persistent storage "
                                + "and outside anything the web server publishes.");
            }
            root = Paths.get(System.getProperty("java.io.tmpdir"), "hms-patient-documents")
                    .toAbsolutePath().toString();
            log.warn("hms.document-storage.root is not set; using a temporary directory for development only.");
        }

        Path configured = Paths.get(root).toAbsolutePath().normalize();
        try {
            // A symlinked root is refused outright rather than resolved: if the directory an
            // operator configured is itself a link, every containment check below would be
            // measuring against wherever that link happens to point today.
            if (Files.isSymbolicLink(configured)) {
                throw new IllegalStateException(
                        "The configured patient document directory is a symbolic link. Point "
                                + "HMS_DOCUMENT_STORAGE_PATH at a real directory.");
            }
            Files.createDirectories(configured);
            // Canonical from here on, so containment is compared against real locations rather
            // than the spelling of a path.
            this.storageRoot = configured.toRealPath();
            restrictPermissions(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Patient document storage directory could not be prepared. Check that the "
                            + "service account owns it and that the volume is mounted.", e);
        }

        // Nothing below can be made safe without descriptor-relative directory access, so a
        // provider that cannot offer it is reported at startup rather than discovered by an
        // upload. Every operation fails closed regardless; production simply refuses to boot.
        if (!secureDirectoryAccessAvailable()) {
            String message = "Patient document storage requires a filesystem supporting "
                    + "descriptor-relative directory access (SecureDirectoryStream). Point "
                    + "HMS_DOCUMENT_STORAGE_PATH at a local filesystem.";
            if (isProduction()) {
                throw new IllegalStateException(message);
            }
            log.warn(message + " Document operations will be refused.");
        }
        log.info("Patient document storage initialised.");
    }

    @Override
    public StoredDocument store(String hospitalRef, String patientRef, String extension,
                                InputStream content, long declaredSize) {
        String key = safeSegment(hospitalRef) + "/" + safeSegment(patientRef) + "/"
                + UUID.randomUUID() + "." + safeExtension(extension);

        // Shape and lexical containment first, plus the cheap look for links that are already
        // there: it costs nothing and refuses the ordinary cases with a clearer error.
        String[] segments = validatedSegments(key);
        resolveWithin(key);

        Path finalName = Paths.get(segments[2]);

        try (SecureDirectoryStream<Path> root = openRoot()) {
            Path hospitalDir = storageRoot.resolve(segments[0]);
            try (SecureDirectoryStream<Path> hospital = openOrCreateDirectory(root, hospitalDir);
                 SecureDirectoryStream<Path> patient =
                         openOrCreateDirectory(hospital, hospitalDir.resolve(segments[1]))) {
                beforeDocumentCreate(key);
                // CREATE_NEW is the claim on the name: the kernel refuses if anything already
                // holds it, so there is no window between deciding the name is free and taking
                // it. Writing straight into the final entry rather than renaming one in is what
                // removes that window -- a rename would happily replace an entry that appeared
                // after any check we could make. Permissions come from the create itself, so the
                // file is never briefly readable by anyone else.
                long written;
                SeekableByteChannel channel = patient.newByteChannel(finalName,
                        openOptions(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        fileAttributes());
                try (channel; OutputStream out = Channels.newOutputStream(channel)) {
                    written = content.transferTo(out);
                }
                return new StoredDocument(key, written);
            }
        } catch (IOException | RuntimeException e) {
            // The path is deliberately absent from the message: it reaches an API client.
            throw new ClinicalDocumentStorageException("The document could not be stored.", e);
        }
    }

    @Override
    public InputStream load(String storageKey) {
        String[] segments = validatedSegments(storageKey);
        resolveWithin(storageKey);

        try (SecureDirectoryStream<Path> root = openRoot();
             SecureDirectoryStream<Path> hospital = openDirectory(root, segments[0]);
             SecureDirectoryStream<Path> patient = openDirectory(hospital, segments[1])) {
            Path name = Paths.get(segments[2]);
            // A link, a directory or anything else reached under this name is not our document.
            if (!isRegularFile(patient, name)) {
                throw new ClinicalDocumentStorageException("The document file is missing.", null);
            }
            SeekableByteChannel channel = patient.newByteChannel(name, openOptions(StandardOpenOption.READ));
            // The channel outlives the directory streams: the descriptor stays valid once open.
            return Channels.newInputStream(channel);
        } catch (NoSuchFileException e) {
            throw new ClinicalDocumentStorageException("The document file is missing.", e);
        } catch (IOException e) {
            throw new ClinicalDocumentStorageException("The document could not be read.", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        String[] segments;
        try {
            segments = validatedSegments(storageKey);
            resolveWithin(storageKey);
        } catch (RuntimeException e) {
            return false;
        }
        try (SecureDirectoryStream<Path> root = openRoot();
             SecureDirectoryStream<Path> hospital = openDirectory(root, segments[0]);
             SecureDirectoryStream<Path> patient = openDirectory(hospital, segments[1])) {
            return isRegularFile(patient, Paths.get(segments[2]));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void deleteQuietly(String storageKey) {
        try {
            String[] segments = validatedSegments(storageKey);
            resolveWithin(storageKey);
            try (SecureDirectoryStream<Path> root = openRoot();
                 SecureDirectoryStream<Path> hospital = openDirectory(root, segments[0]);
                 SecureDirectoryStream<Path> patient = openDirectory(hospital, segments[1])) {
                patient.deleteFile(Paths.get(segments[2]));
            }
        } catch (NoSuchFileException e) {
            // Already gone, which is the state the caller wanted.
        } catch (IOException | RuntimeException e) {
            // The caller is already unwinding a failed upload. Losing the file is not worse than
            // the failure that got us here, but an operator should be able to find the orphan.
            log.warn("Could not remove a partially stored clinical document; manual cleanup may "
                    + "be needed for key ending {}", tail(storageKey));
        }
    }

    // -- descriptor-relative traversal -------------------------------------------

    /** An open handle on the root, through which every other name is resolved. */
    private SecureDirectoryStream<Path> openRoot() throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(storageRoot);
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        // Fail closed: the path-based fallback is the raceable implementation this replaced.
        throw new ClinicalDocumentStorageException(
                "Document storage is not available on this filesystem.", null);
    }

    /**
     * Opens one directory below {@code parent}, refusing to follow a link in that position.
     *
     * <p>The name is resolved by the kernel against the descriptor {@code parent} holds, so no
     * component above it can be swapped out from underneath the call.
     */
    private SecureDirectoryStream<Path> openDirectory(SecureDirectoryStream<Path> parent, String name)
            throws IOException {
        DirectoryStream<Path> child = parent.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS);
        if (child instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        // Nothing usable came back, so this is the fail-closed path either way. The null check is
        // for the close, not for the decision: the provider's return is not declared non-null,
        // and an NPE here would replace a clear refusal with a confusing one.
        if (child != null) {
            child.close();
        }
        throw new ClinicalDocumentStorageException(
                "Document storage is not available on this filesystem.", null);
    }

    /**
     * The same, creating the directory if it is genuinely absent.
     *
     * <p>{@code mkdir} is itself the no-replace primitive: it fails if anything already holds the
     * name, so two requests admitting patients to the same ward at the same moment cannot have
     * one replace the other's directory -- the loser simply reopens what the winner made. An
     * earlier version placed a staging directory with a descriptor-relative rename, which is
     * {@code renameat} and would happily replace a destination. That is gone; this is both
     * simpler and stricter.
     *
     * <p>The create resolves by path, which is safe under the ownership assumption stated on
     * this class: only the service account may write inside the root, so no component here can
     * become a symlink between calls. It is not the last line regardless -- the open below is
     * descriptor-relative and refuses links, so nothing is ever written through one.
     */
    private SecureDirectoryStream<Path> openOrCreateDirectory(SecureDirectoryStream<Path> parent,
                                                              Path directory) throws IOException {
        String name = directory.getFileName().toString();
        for (int attempt = 0; attempt < MAX_DIRECTORY_ATTEMPTS; attempt++) {
            beforeDirectoryOpen(name);
            try {
                return openDirectory(parent, name);
            } catch (NoSuchFileException notThereYet) {
                // Falls through to creation. Any other failure -- a link or a file in this
                // position, a permission problem -- propagates: it is not one to retry around.
            }
            try {
                // Owner-only from the moment it exists: the permissions are part of the create,
                // never a second call on a name.
                Files.createDirectory(directory, directoryAttributes());
            } catch (FileAlreadyExistsException raced) {
                // Someone else got there first. The reopen above decides what it is, and that
                // is the check that cannot be fooled.
            }
        }
        throw new ClinicalDocumentStorageException(
                "The document directory could not be prepared.", null);
    }

    /**
     * A seam for the race that motivated all of this.
     *
     * <p>Called immediately before a directory is opened or created, which is exactly where a
     * planted symlink would have to arrive to be dangerous. Production does nothing here; a test
     * overrides it to make the race deterministic instead of hoping to hit it.
     */
    void beforeDirectoryOpen(String name) {
        // no-op in production
    }

    /** As above, immediately before the final document entry is claimed. */
    void beforeDocumentCreate(String storageKey) {
        // no-op in production
    }

    private static boolean isRegularFile(SecureDirectoryStream<Path> directory, Path name) {
        try {
            return directory.getFileAttributeView(name, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile();
        } catch (IOException e) {
            return false;
        }
    }

    private static Set<OpenOption> openOptions(OpenOption... options) {
        Set<OpenOption> set = new java.util.LinkedHashSet<>(Arrays.asList(options));
        set.add(LinkOption.NOFOLLOW_LINKS);
        return set;
    }

    /** Owner-only, applied by the create itself so no window exists where it is not. */
    private FileAttribute<?>[] directoryAttributes() {
        return posixSupported()
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE))}
                : new FileAttribute<?>[0];
    }

    private FileAttribute<?>[] fileAttributes() {
        return posixSupported()
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))}
                : new FileAttribute<?>[0];
    }

    private boolean posixSupported() {
        return storageRoot.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private boolean secureDirectoryAccessAvailable() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageRoot)) {
            return stream instanceof SecureDirectoryStream;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Turns a key into a path and proves it stayed inside the root.
     *
     * <p>Keys are generated by this class, so a key that does not look like one did not come from
     * here -- it came from a request. This is the first of two lines: it rejects the obvious
     * attempts with a clear error, while the descriptor-relative traversal above is what actually
     * holds when an attacker is racing the check.
     */
    Path resolveWithin(String storageKey) {
        validatedSegments(storageKey);

        Path resolved = storageRoot.resolve(storageKey).normalize();
        // Lexical containment first -- cheap, and catches a key that spells its way out.
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid document storage key");
        }
        assertNoSymlinkEscape(resolved);
        return resolved;
    }

    /** The shape of a key we could have issued, and nothing else. */
    private static String[] validatedSegments(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Missing document storage key");
        }
        if (storageKey.contains("..") || storageKey.startsWith("/")
                || storageKey.indexOf('\\') >= 0 || storageKey.indexOf(' ') >= 0
                || storageKey.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Invalid document storage key");
        }
        String[] segments = storageKey.split("/");
        if (segments.length != 3) {
            throw new IllegalArgumentException("Invalid document storage key");
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean hasDot = i == 2 && segment.contains(".");
            String stem = hasDot ? segment.substring(0, segment.lastIndexOf('.')) : segment;
            String extension = hasDot ? segment.substring(segment.lastIndexOf('.') + 1) : "";
            if (!stem.matches(KEY_CHARS) || (hasDot && !extension.matches(KEY_CHARS))) {
                throw new IllegalArgumentException("Invalid document storage key");
            }
        }
        return segments;
    }

    /**
     * Lexical containment is not filesystem containment.
     *
     * <p>normalize() works on the text of a path. If a directory inside the root is a symbolic
     * link -- planted by an attacker who reached the disk, or by an operator restoring a backup
     * with cp -a -- then a key that looks perfectly well-behaved resolves through it and lands
     * outside. So every segment that already exists is checked for being a link, and the deepest
     * existing ancestor is resolved to its real location and re-checked against the real root.
     *
     * <p>On its own this is check-then-use and a link planted afterwards would not be seen. It is
     * kept for the clear error it gives on the ordinary case; the guarantee comes from the
     * descriptor-relative traversal, which never resolves these names by path at all.
     */
    private void assertNoSymlinkEscape(Path resolved) {
        Path cursor = storageRoot;
        for (Path segment : storageRoot.relativize(resolved)) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                break; // not created yet; nothing here can redirect us
            }
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Invalid document storage key");
            }
            try {
                if (!cursor.toRealPath().startsWith(storageRoot)) {
                    throw new IllegalArgumentException("Invalid document storage key");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid document storage key");
            }
        }
    }

    private static String safeSegment(String reference) {
        String cleaned = reference == null ? "" : reference.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String safeExtension(String extension) {
        String cleaned = extension == null ? "" : extension.toLowerCase().replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() ? "bin" : cleaned;
    }

    private static String tail(String key) {
        return key == null || key.length() < 8 ? "(unknown)" : key.substring(key.length() - 8);
    }

    /** Nothing outside the service account needs to read a patient's records. */
    private static void restrictPermissions(Path path) {
        try {
            if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
            Set<PosixFilePermission> permissions = Files.isDirectory(path)
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                             PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Could not tighten permissions on a document storage path.");
        }
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
    }

    /** Storage trouble, described without naming anything on disk. */
    public static class ClinicalDocumentStorageException extends RuntimeException {
        public ClinicalDocumentStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
