package io.github.sanitised.st

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.Os
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class SillyTavernDocumentsProvider : DocumentsProvider() {
    companion object {
        const val ROOT_ID = "sillytavern-data"
        const val ST_ROOT_ID = "sillytavern-root"
        private const val ROOT_DOCUMENT_ID = "data"
        private const val ST_ROOT_DOCUMENT_ID = "st"
        private const val ROOTS_CACHE_MS = 1000L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    private class RootSpec(
        val rootId: String,
        val documentPrefix: String,
        val titleRes: Int,
        val declaredDir: File
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        val providerContext = context ?: return cursor
        cursor.setNotificationUri(
            providerContext.contentResolver,
            DocumentsContract.buildRootsUri(ExternalFileAccess.authority(providerContext))
        )
        if (!ExternalFileAccess.isEnabled(providerContext)) return cursor

        for (spec in rootSpecs()) {
            // A broken root must not hide the other one.
            runCatching { validateRoot(spec) }.getOrNull() ?: continue
            addRootRow(cursor, columns, spec)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        requireEnabled()
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).also { cursor ->
            addDocumentRow(cursor, columns, resolveDocument(documentId))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        requireEnabled()
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        val parent = resolveDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory")

        parent.listFiles()
            ?.asSequence()
            ?.filter { child -> runCatching { validateExistingFile(child) }.isSuccess }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
            ?.forEach { child -> addDocumentRow(cursor, columns, child) }

        context?.let { providerContext ->
            cursor.setNotificationUri(
                providerContext.contentResolver,
                DocumentsContract.buildChildDocumentsUri(
                    ExternalFileAccess.authority(providerContext),
                    parentDocumentId
                )
            )
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        requireEnabled()
        val file = resolveDocument(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file")
        val root = rootForDocument(documentId).second
        val truncateAfterValidation = mode == "w" || mode == "wt" || mode == "rwt"
        val requestedMode = ParcelFileDescriptor.parseMode(mode)
        val safeOpenMode = requestedMode and
            ParcelFileDescriptor.MODE_CREATE.inv() and
            ParcelFileDescriptor.MODE_TRUNCATE.inv()
        val descriptor = ParcelFileDescriptor.open(file, safeOpenMode)
        val openedFile = runCatching {
            File("/proc/self/fd/${descriptor.fd}").canonicalFile
        }.getOrElse {
            descriptor.close()
            throw FileNotFoundException("Could not validate opened document")
        }
        if (!openedFile.toPath().startsWith(root.toPath())) {
            descriptor.close()
            throw FileNotFoundException("Document is outside the exposed directories")
        }
        if (truncateAfterValidation) {
            runCatching { Os.ftruncate(descriptor.fileDescriptor, 0L) }
                .onFailure {
                    descriptor.close()
                    throw FileNotFoundException("Could not truncate document")
                }
        }
        return descriptor
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        requireEnabled()
        validateDisplayName(displayName)
        val parent = resolveDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory")
        val child = validateNewChild(parent, displayName)
        if (child.exists()) throw FileNotFoundException("A file with that name already exists")

        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            child.mkdir()
        } else {
            child.createNewFile()
        }
        if (!created) throw FileNotFoundException("Could not create document")
        notifyChildrenChanged(parentDocumentId)
        return documentIdFor(child)
    }

    override fun deleteDocument(documentId: String) {
        requireEnabled()
        val file = resolveDocument(documentId)
        if (isRoot(file)) throw FileNotFoundException("The root cannot be deleted")
        val parentId = documentIdFor(file.parentFile ?: throw FileNotFoundException("Missing parent"))
        if (!deleteTreeWithoutFollowingLinks(file)) {
            throw FileNotFoundException("Could not delete document")
        }
        revokeDocumentPermission(documentId)
        notifyChildrenChanged(parentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireEnabled()
        validateDisplayName(displayName)
        val file = resolveDocument(documentId)
        if (isRoot(file)) throw FileNotFoundException("The root cannot be renamed")
        val parent = file.parentFile ?: throw FileNotFoundException("Missing parent")
        val target = validateNewChild(parent, displayName)
        if (target.exists()) throw FileNotFoundException("A file with that name already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Could not rename document")
        notifyChildrenChanged(documentIdFor(parent))
        return documentIdFor(target)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        requireEnabled()
        val source = resolveDocument(sourceDocumentId)
        if (isRoot(source)) throw FileNotFoundException("The root cannot be moved")
        val sourceParent = resolveDocument(sourceParentDocumentId)
        val targetParent = resolveDocument(targetParentDocumentId)
        if (!sourceParent.isDirectory || source.parentFile?.canonicalFile != sourceParent) {
            throw FileNotFoundException("Invalid source parent")
        }
        if (!targetParent.isDirectory) throw FileNotFoundException("Invalid target parent")
        if (targetParent == source || targetParent.toPath().startsWith(source.toPath())) {
            throw FileNotFoundException("Cannot move a directory into itself")
        }
        val target = validateNewChild(targetParent, source.name)
        if (target.exists()) throw FileNotFoundException("A file with that name already exists")
        if (!source.renameTo(target)) throw FileNotFoundException("Could not move document")

        revokeDocumentPermission(sourceDocumentId)
        notifyChildrenChanged(sourceParentDocumentId)
        notifyChildrenChanged(targetParentDocumentId)
        return documentIdFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        requireEnabled()
        val parent = resolveDocument(parentDocumentId)
        val child = resolveDocument(documentId)
        return child != parent && child.toPath().startsWith(parent.toPath())
    }

    override fun getDocumentType(documentId: String): String {
        requireEnabled()
        return mimeType(resolveDocument(documentId))
    }

    private fun requireEnabled() {
        val providerContext = context ?: throw FileNotFoundException("Provider is unavailable")
        if (!ExternalFileAccess.isEnabled(providerContext)) {
            throw FileNotFoundException("External file access is disabled")
        }
    }

    private fun rootSpecs(): List<RootSpec> {
        val providerContext = context ?: throw FileNotFoundException("Provider is unavailable")
        val paths = AppPaths(providerContext)
        return listOf(
            RootSpec(ROOT_ID, ROOT_DOCUMENT_ID, R.string.external_file_access_root_title, paths.dataDir),
            RootSpec(ST_ROOT_ID, ST_ROOT_DOCUMENT_ID, R.string.external_file_access_st_root_title, paths.stDir)
        )
    }

    private fun validateRoot(spec: RootSpec): File {
        val providerContext = context ?: throw FileNotFoundException("Provider is unavailable")
        val filesRoot = providerContext.filesDir.canonicalFile
        val declaredRoot = spec.declaredDir
        if (Files.isSymbolicLink(declaredRoot.toPath())) {
            throw FileNotFoundException("Root directory cannot be a symbolic link")
        }
        if (!declaredRoot.exists() && !declaredRoot.mkdirs()) {
            throw FileNotFoundException("Root directory is unavailable")
        }
        if (!declaredRoot.isDirectory) throw FileNotFoundException("Root directory is unavailable")
        val root = declaredRoot.canonicalFile
        if (root.parentFile != filesRoot) throw FileNotFoundException("Invalid root directory")
        return root
    }

    // Root validation stats the filesystem; cache briefly so listing large
    // directories (e.g. node_modules) does not revalidate roots per row.
    private var rootsCache: List<Pair<RootSpec, File>>? = null
    private var rootsCacheAt: Long = 0L

    private fun requireRoots(): List<Pair<RootSpec, File>> {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = rootsCache
        if (cached != null && now - rootsCacheAt < ROOTS_CACHE_MS) return cached
        val roots = rootSpecs().map { spec -> spec to validateRoot(spec) }
        rootsCache = roots
        rootsCacheAt = now
        return roots
    }

    private fun rootForDocument(documentId: String): Pair<RootSpec, File> {
        val prefix = documentId.substringBefore('/')
        val spec = rootSpecs().firstOrNull { it.documentPrefix == prefix }
            ?: throw FileNotFoundException("Invalid document ID")
        return spec to validateRoot(spec)
    }

    private fun rootForFile(file: File): Pair<RootSpec, File> {
        val canonical = file.canonicalFile
        return requireRoots().firstOrNull { (_, root) -> canonical.toPath().startsWith(root.toPath()) }
            ?: throw FileNotFoundException("Document is outside the exposed directories")
    }

    private fun isRoot(file: File): Boolean =
        requireRoots().any { (_, root) -> file == root }

    private fun resolveDocument(documentId: String): File {
        val (spec, root) = rootForDocument(documentId)
        if (documentId == spec.documentPrefix) return root
        if (!documentId.startsWith("${spec.documentPrefix}/")) {
            throw FileNotFoundException("Invalid document ID")
        }
        val segments = documentId.removePrefix("${spec.documentPrefix}/").split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
            throw FileNotFoundException("Invalid document ID")
        }

        var candidate = root
        for (segment in segments) {
            candidate = File(candidate, segment)
            if (Files.isSymbolicLink(candidate.toPath())) {
                throw FileNotFoundException("Symbolic links are not exposed")
            }
        }
        return validateExistingFile(candidate)
    }

    private fun validateExistingFile(file: File): File {
        if (Files.isSymbolicLink(file.toPath())) {
            throw FileNotFoundException("Symbolic links are not exposed")
        }
        val canonical = file.canonicalFile
        if (!canonical.exists()) throw FileNotFoundException("Document does not exist")
        rootForFile(canonical)
        return canonical
    }

    private fun validateNewChild(parent: File, displayName: String): File {
        val canonicalParent = validateExistingFile(parent)
        val root = rootForFile(canonicalParent).second
        val child = File(canonicalParent, displayName)
        if (Files.isSymbolicLink(child.toPath())) {
            throw FileNotFoundException("Symbolic links are not exposed")
        }
        val canonicalChild = child.canonicalFile
        if (!canonicalChild.toPath().startsWith(root.toPath()) || canonicalChild.parentFile != canonicalParent) {
            throw FileNotFoundException("Invalid document name")
        }
        return canonicalChild
    }

    private fun validateDisplayName(displayName: String) {
        if (
            displayName.isBlank() ||
            displayName == "." ||
            displayName == ".." ||
            '/' in displayName ||
            '\\' in displayName ||
            '\u0000' in displayName
        ) {
            throw FileNotFoundException("Invalid document name")
        }
    }

    private fun documentIdFor(file: File): String {
        val canonical = validateExistingFile(file)
        val (spec, root) = rootForFile(canonical)
        if (canonical == root) return spec.documentPrefix
        val relative = root.toPath().relativize(canonical.toPath())
            .joinToString("/") { it.toString() }
        return "${spec.documentPrefix}/$relative"
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun documentFlags(file: File): Int {
        if (isRoot(file)) {
            return DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        return if (file.isDirectory) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        }
    }

    private fun addRootRow(cursor: MatrixCursor, columns: Array<out String>, spec: RootSpec) {
        val values = columns.map { column ->
            when (column) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> spec.rootId
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> spec.documentPrefix
                DocumentsContract.Root.COLUMN_TITLE -> context?.getString(spec.titleRes)
                DocumentsContract.Root.COLUMN_FLAGS ->
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                        DocumentsContract.Root.FLAG_LOCAL_ONLY
                DocumentsContract.Root.COLUMN_ICON -> R.mipmap.ic_launcher
                DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(values)
    }

    private fun addDocumentRow(cursor: MatrixCursor, columns: Array<out String>, file: File) {
        val rootTitle = requireRoots().firstOrNull { (_, root) -> file == root }
            ?.let { (spec, _) -> context?.getString(spec.titleRes) }
        val values = columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentIdFor(file)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> rootTitle ?: file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType(file)
                DocumentsContract.Document.COLUMN_FLAGS -> documentFlags(file)
                DocumentsContract.Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified().takeIf { it > 0L }
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(values)
    }

    private fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        validateExistingFile(file)
        if (file.isDirectory) {
            for (child in file.listFiles().orEmpty()) {
                if (!deleteTreeWithoutFollowingLinks(child)) return false
            }
        }
        return file.delete()
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        val providerContext = context ?: return
        providerContext.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(
                ExternalFileAccess.authority(providerContext),
                parentDocumentId
            ),
            null
        )
    }
}
