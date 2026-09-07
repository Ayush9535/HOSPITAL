package com.hms.service.storage;

import java.io.InputStream;

/**
 * Where the bytes of a clinical document live.
 *
 * <p>Deliberately narrow, and deliberately not a filesystem API: the service above it never sees
 * a {@code Path}, a root directory or a provider name, so moving this to object storage later is
 * a new implementation rather than a redesign of the document model, its endpoints or its UI.
 *
 * <p>The key returned by {@link #store} is opaque and generated here. Nothing derived from a
 * patient's name, an original filename or any identifier a person could guess belongs in it, and
 * no caller may invent one — a key that arrived from a client is a filesystem path in disguise.
 */
public interface ClinicalDocumentStorage {

    /** What was stored, and under what key the caller should record it. */
    record StoredDocument(String storageKey, long sizeBytes) {}

    /**
     * Writes the bytes and returns the key they can be read back with.
     *
     * @param hospitalRef opaque, stable reference for the owning facility
     * @param patientRef  opaque, stable reference for the owning patient
     * @param extension   normalised file extension, no leading dot
     */
    StoredDocument store(String hospitalRef, String patientRef, String extension,
                         InputStream content, long declaredSize);

    /** Opens the stored bytes. Throws if the key does not resolve to a readable object. */
    InputStream load(String storageKey);

    boolean exists(String storageKey);

    /**
     * Best-effort removal, used only to clean up after a failed upload.
     *
     * <p>Never called when a document is archived: archiving is a clinical-record state, and the
     * bytes stay.
     */
    void deleteQuietly(String storageKey);
}
