/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.publication

import android.accounts.AccountManager
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okhttp3.internal.closeQuietly
import okhttp3.internal.headersContentLength
import okio.BufferedSource
import okio.IOException
import okio.buffer
import okio.source
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.Thread.sleep
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class NCShareViewModel(application: Application): AndroidViewModel(application) {
    private val _shareByMe = MutableStateFlow<List<ShareByMe>>(arrayListOf())
    private val _shareWithMe = MutableStateFlow<List<ShareWithMe>>(arrayListOf())
    private val _shareWithMeProgress = MutableStateFlow(0)
    private val _sharees = MutableStateFlow<List<Sharee>>(arrayListOf())
    private val _publicationContentMeta = MutableStateFlow<List<RemotePhoto>>(arrayListOf())
    private val _blogs = MutableStateFlow<List<Blog>>(arrayListOf())
    val shareByMe: StateFlow<List<ShareByMe>> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>> = _shareWithMe
    val shareWithMeProgress: StateFlow<Int> = _shareWithMeProgress
    val sharees: StateFlow<List<Sharee>> = _sharees
    val publicationContentMeta: StateFlow<List<RemotePhoto>> = _publicationContentMeta
    val blogs: StateFlow<List<Blog>> = _blogs

    private var webDav: OkHttpWebDav

    private val baseUrl: String
    private var token: String
    private val resourceRoot: String
    private val lespasBase = Tools.getRemoteHome(application)
    private val archiveBase = Tools.getCameraArchiveHome(application)
    private val localCacheFolder = "${Tools.getLocalRoot(application)}/cache"
    private val localFileFolder = Tools.getLocalRoot(application)

    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val autoReplayKey = application.getString(R.string.auto_replay_perf_key)

    private val photoRepository = PhotoRepository(application)

    private val videoPlayerCache: SimpleCache?

    fun interface LoadCompleteListener {
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = getAccountsByType(application.getString(R.string.account_type_nc))[0]
            val userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            token = getUserData(account, application.getString(R.string.nc_userdata_secret))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            webDav = OkHttpWebDav(
                userName, token, baseUrl, getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), localCacheFolder,"LesPas_${application.getString(R.string.lespas_version)}",
                PreferenceManager.getDefaultSharedPreferences(application).getInt(SettingsFragment.CACHE_SIZE, 800)
            )

            videoPlayerCache = try { SimpleCache(File(application.cacheDir, "video"), LeastRecentlyUsedCacheEvictor(100L * 1024L * 1024L), StandaloneDatabaseProvider(application)) } catch (e: Exception) { null }
        }
    }

    fun updateWebDavAccessToken(context: Context) {
        AccountManager.get(context).run {
            val account = getAccountsByType(context.getString(R.string.account_type_nc))[0]
            val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
            token = getUserData(account, context.getString(R.string.nc_userdata_secret))
            webDav = OkHttpWebDav(
                userName, token, baseUrl, getUserData(account, context.getString(R.string.nc_userdata_selfsigned)).toBoolean(), localCacheFolder,"LesPas_${context.getString(R.string.lespas_version)}",
                PreferenceManager.getDefaultSharedPreferences(context).getInt(SettingsFragment.CACHE_SIZE, 800)
            )
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _sharees.value = refreshSharees()
            _shareByMe.value = refreshShareByMe()
            refreshShareWithMe()
        }
    }

    fun getCallFactory() = webDav.getCallFactory()
    fun getPlayerCache() = videoPlayerCache

    fun getResourceRoot(): String = resourceRoot

/*
    val themeColor: Flow<Int> = flow {
        var color = 0

        try {
            webDav.ocsGet("$baseUrl$CAPABILITIES_ENDPOINT")?.apply {
                color = Integer.parseInt(getJSONObject("data").getJSONObject("capabilities").getJSONObject("theming").getString("color").substringAfter('#'), 16)
            }
            if (color != 0) emit(color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)
*/

    private fun refreshShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var sharee: Recipient

        try {
            webDav.ocsGet("${baseUrl}${String.format(SHARED_BY_ME_ENDPOINT, lespasBase)}")?.apply {
                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        if (getString("item_type") == "folder") {
                            // Only interested in shares of sub-folders under lespas/
                            sharee = Recipient(getString("id"), getInt("permissions"), getLong("stime"), Sharee(getString("share_with"), getString("share_with_displayname"), getInt("share_type")))

                            @Suppress("SimpleRedundantLet")
                            result.find { share -> share.fileId == getString("item_source") }?.let { item ->
                                // If this folder existed in result, add new sharee only
                                item.with.add(sharee)
                            } ?: run {
                                // Create new folder share item
                                result.add(ShareByMe(getString("item_source"), getString("path").substringAfterLast('/'), mutableListOf(sharee)))
                            }
                        }
                    }
                }
            }

            return result
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        return arrayListOf()
    }

    private fun refreshShareWithMe() {
        val result = mutableListOf<ShareWithMe>()

        _shareWithMeProgress.value = 0
        try {
            webDav.ocsGet("$baseUrl$SHARED_WITH_ME_ENDPOINT")?.apply {
                var folderId: String
                var permission: Int

                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        if (getString("item_type") == "folder" && getInt("share_type") <= SHARE_TYPE_GROUP) {
                            // Only process folder share with share type of user(0) or group(1)
                            folderId = getString("item_source")
                            permission = getInt("permissions")
                            result.find { existed -> existed.albumId == folderId }?.let { existed ->
                                // Existing sharedWithMe entry, we should keep the one with more permission bits set
                                if (existed.permission < permission) {
                                    existed.shareId = getString("id")
                                    existed.permission = permission
                                    existed.sharedTime = getLong("stime")
                                }
                            } ?: run {
                                // New sharedWithMe entry, cover, lastModified and sortOrder properties will be set in getAlbumMetaForShareWithMe()
                                result.add(
                                    ShareWithMe(
                                        shareId = getString("id"),
                                        sharePath = getString("file_target"),
                                        albumId = folderId,
                                        albumName = getString("path").substringAfterLast('/'),
                                        shareBy = getString("uid_owner"),
                                        shareByLabel = getString("displayname_owner"),
                                        permission = permission,
                                        sharedTime = getLong("stime"),
                                        cover = Cover(Album.NO_COVER, 0, 0, 0, Album.NO_COVER, "", 0), Album.BY_DATE_TAKEN_ASC, 0L
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (result.isNotEmpty()) _shareWithMe.value = getAlbumMetaForShareWithMe(result).apply { sort() }

        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        } catch (e: OkHttpWebDavException) {
            Log.e(">>>>>>>>>>>>", "${e.statusCode} ${e.printStackTrace()}")
        }
    }

    fun getShareWithMe() {
        viewModelScope.launch(Dispatchers.IO) { refreshShareWithMe() }
    }

    private fun getAlbumMetaForShareWithMe(shares: List<ShareWithMe>): MutableList<ShareWithMe> {
        val result = shares.toMutableList()

        // Get shares' last modified timestamp by PROPFIND each individual share path
        val lastModified = HashMap<String, Long>()
        val offset = OffsetDateTime.now().offset
        var sPath = "."     // A string that could never be a folder's name
        result.forEach { share ->
            share.sharePath.substringBeforeLast('/').apply {
                if (this != sPath) {
                    sPath = this
                    webDav.list("${resourceRoot}${sPath}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { if (it.isFolder) lastModified[it.fileId] = it.modified.toEpochSecond(offset) }
                }
            }
        }

        // Retrieve share's meta data
        val total = result.size
        result.forEachIndexed { i, share ->
            _shareWithMeProgress.value = ((i * 100.0) / total).toInt()
            share.lastModified = lastModified[share.albumId] ?: 0L
            try {
                webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}.json", true, CacheControl.FORCE_NETWORK).use {
                    JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                        val version = try {
                            meta.getInt("version")
                        } catch (e: JSONException) {
                            1
                        }
                        share.cover = meta.getJSONObject("cover").run {
                            when {
                                // TODO Make sure later version of album meta file downward compatible
                                version >= 2 -> Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), getString("mimetype"), getInt("orientation"))
                                // Version 1 of album meta json
                                else -> Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), "image/jpeg", 0)
                            }
                        }
                        share.sortOrder = meta.getInt("sort")
                    }

                }
            } catch (e: Exception) {
                // Either there is no album meta json file in the folder, or json parse error means it's not a lespas share
            }
        }
        _shareWithMeProgress.value = 100

        return result.filter { it.cover.cover.isNotEmpty() }.toMutableList()
    }

    private fun refreshSharees(): MutableList<Sharee> {
        val result = mutableListOf<Sharee>()
        var backOff = 2500L

        while (true) {
            try {
                webDav.ocsGet("$baseUrl$SHAREE_LISTING_ENDPOINT")?.apply {
                    //if (getJSONObject("meta").getInt("statuscode") != 100) return null
                    val data = getJSONObject("data")
                    val users = data.getJSONArray("users")
                    for (i in 0 until users.length()) {
                        users.getJSONObject(i).apply {
                            result.add(Sharee(getJSONObject("value").getString("shareWith"), getString("label"), SHARE_TYPE_USER))
                        }
                    }
                    val groups = data.getJSONArray("groups")
                    for (i in 0 until groups.length()) {
                        groups.getJSONObject(i).apply {
                            result.add(Sharee(getJSONObject("value").getString("shareWith"), getString("label"), SHARE_TYPE_GROUP))
                        }
                    }
                }

                return result
            } catch (e: UnknownHostException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: SocketTimeoutException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return arrayListOf()
    }

    private fun createShares(albums: List<ShareByMe>) {
        for (album in albums) {
            for (recipient in album.with) {
                try {
                    webDav.ocsPost(
                        "$baseUrl$PUBLISH_ENDPOINT",
                        FormBody.Builder()
                            .add("path", "$lespasBase/${album.folderName}")
                            .add("shareWith", recipient.sharee.name)
                            .add("shareType", recipient.sharee.type.toString())
                            .add("permissions", recipient.permission.toString())
                            .build()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun deleteShares(recipients: List<Recipient>) {
        for (recipient in recipients) {
            try {
                webDav.ocsDelete("$baseUrl$PUBLISH_ENDPOINT/${recipient.shareId}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

/*
    fun publish(albums: List<ShareByMe>) {
        viewModelScope.launch(Dispatchers.IO) {
            createShares(albums)
            _shareByMe.value = refreshShareByMe()
        }
    }
*/

    fun unPublish(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            val recipients = mutableListOf<Recipient>()
            for (album in albums) {
                _shareByMe.value.find { it.fileId == album.id }?.apply { recipients.addAll(this.with) }
            }
            deleteShares(recipients)
            _shareByMe.value = refreshShareByMe()
        }
    }

    fun updatePublish(album: ShareByMe, removeRecipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Remove sharees
                if (removeRecipients.isNotEmpty()) deleteShares(removeRecipients)

                // Add sharees
                if (album.with.isNotEmpty()) {
                    // TODO no need to create content meta here since it's always maintained and uploaded on every update, but since client lower than release 2.5.0 will update the meta here, need to keep this
                    //    here for a while as a counter measure
                    if (!isShared(album.fileId)) {
                        // If sharing this album for the 1st time, create content.json on server
                        val content = Tools.metasToJSONString(photoRepository.getPhotoMetaInAlbum(album.fileId))
                        webDav.upload(content, "${resourceRoot}${lespasBase}/${album.folderName}/${album.fileId}${SyncAdapter.CONTENT_META_FILE_SUFFIX}", SyncAdapter.MIME_TYPE_JSON)
                    }

                    createShares(listOf(album))
                }

                // Update _shareByMe hence update UI
                if (album.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = refreshShareByMe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun renameShare(album: ShareByMe, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDav.move("$resourceRoot$lespasBase/${album.folderName}", "$resourceRoot$lespasBase/${newName}")
                deleteShares(album.with)
                album.folderName = newName
                createShares(listOf(album))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isShared(albumId: String): Boolean = _shareByMe.value.indexOfFirst { it.fileId == albumId } != -1

    fun resetPublicationContentMeta() {
        _publicationContentMeta.value = mutableListOf()
    }

    fun getCameraRollArchive(): List<Photo> {
        val result = mutableListOf<Photo>()
        try {
            webDav.listWithExtraMeta("${resourceRoot}${archiveBase}", OkHttpWebDav.RECURSIVE_DEPTH).forEach { dav ->
                if (dav.contentType.startsWith("image/") || dav.contentType.startsWith("video/")) {
                    result.add(Photo(
                        id = dav.fileId, albumId = dav.albumId, name = dav.name, eTag = dav.eTag, mimeType = dav.contentType,
                        dateTaken = dav.dateTaken, lastModified = dav.modified,
                        width = dav.width, height = dav.height, orientation = dav.orientation,
                        // Store file size in property shareId
                        shareId = dav.size.toInt(),
                        latitude = dav.latitude, longitude = dav.longitude, altitude = dav.altitude, bearing = dav.bearing
                    ))
                }
            }
            result.sortByDescending { it.dateTaken }

            // Save a snapshot
            File(localFileFolder, CameraRollFragment.CameraRollViewModel.SNAPSHOT_FILENAME).writer().use {
                it.write(Tools.photosToMetaJSONString(result))
            }
        } catch (_: Exception) {}

        return result
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getRemotePhotoList(share: ShareWithMe, forceNetwork: Boolean) {
        var doRefresh = true

        withContext(Dispatchers.IO) {
            try {
                webDav.getStreamBool("${resourceRoot}${share.sharePath}/${share.albumId}${SyncAdapter.CONTENT_META_FILE_SUFFIX}", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).apply {
                    if (forceNetwork || this.second) doRefresh = false
                    this.first.use { _publicationContentMeta.value = Tools.readContentMeta(it, share.sharePath, share.sortOrder) }
                }

                if (doRefresh) webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}${SyncAdapter.CONTENT_META_FILE_SUFFIX}", true, CacheControl.FORCE_NETWORK).use { _publicationContentMeta.value = Tools.readContentMeta(it, share.sharePath, share.sortOrder) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getMediaExif(remotePhoto: RemotePhoto): Pair<ExifInterface?, Long>? {
        var response: Response? = null
        var result: Pair<ExifInterface?, Long>? = null

        try {
            response = webDav.getRawResponse("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true)
            result = Pair(if (Tools.hasExif(remotePhoto.photo.mimeType)) ExifInterface(response.body!!.byteStream()) else null, response.headersContentLength())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            response?.close()
        }

        return result
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun getAvatar(user: Sharee, view: View, callBack: LoadCompleteListener?) {
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(downloadDispatcher) {
            var bitmap: Bitmap? = null
            var drawable: Drawable? = null
            try {
                if (user.type == SHARE_TYPE_GROUP) drawable = ContextCompat.getDrawable(view.context, R.drawable.ic_baseline_group_24)
                else {
                    // Only user has avatar
                    val key = "${user.name}-avatar"
                    imageCache.get(key)?.let { bitmap = it } ?: run {
                        // Set default avatar first
                        if (isActive) ContextCompat.getDrawable(view.context, R.drawable.ic_baseline_person_24)?.let { drawAvatar(it, view) }

                        webDav.getStream("${baseUrl}${AVATAR_ENDPOINT}${user.name}/64", true, null).use { s -> bitmap = BitmapFactory.decodeStream(s) }
                        bitmap?.let { bmp -> imageCache.put(key, bmp) }
                    }
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally {
                if (isActive) {
                    if (drawable == null && bitmap != null) drawable = BitmapDrawable(view.resources, Tools.getRoundBitmap(view.context, bitmap!!))
                    drawable?.let { drawAvatar(it, view) }
                }

                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    private suspend fun drawAvatar(drawable: Drawable, view: View) {
        withContext(Dispatchers.Main) {
            when (view) {
                is Chip -> view.chipIcon = drawable
                is TextView -> {
                    maxOf(48, (view.textSize * 1.2).roundToInt()).let { size -> drawable.setBounds(0, 0, size, size) }
                    view.setCompoundDrawables(drawable, null, null, null)
                }
            }
        }
    }

    fun getPreview(remotePhoto: RemotePhoto): Bitmap? {
        var bitmap: Bitmap? = try {
            webDav.getStream("${baseUrl}${PREVIEW_ENDPOINT}${remotePhoto.photo.id}", true, null).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
        bitmap ?: run {
            webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use {
                bitmap = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        }

        return bitmap
    }

    fun downloadFile(media: String, dest: File, stripExif: Boolean, photo: Photo, useCache: Boolean = true): Boolean {
        return try {
            webDav.getStream("${resourceRoot}${media}", useCache, null).use { remote ->
                if (stripExif) {
                    BitmapFactory.decodeStream(remote)?.let { bmp->
                        (if (photo.orientation != 0) Bitmap.createBitmap(bmp, 0, 0, photo.width, photo.height, Matrix().apply { preRotate(photo.orientation.toFloat()) }, true) else bmp)
                            .compress(Bitmap.CompressFormat.JPEG, 95, dest.outputStream())
                    }
                }
                else dest.outputStream().use { local -> remote.copyTo(local, 8192) }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()

            false
        }
    }

    fun savePhoto(context: Context, remotePhoto: RemotePhoto) {
        if (remotePhoto.photo.mimeType.startsWith("image")) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cr = context.contentResolver
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mediaDetails = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, remotePhoto.photo.name)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.MIME_TYPE, remotePhoto.photo.mimeType)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaDetails)?.let { uri ->
                            cr.openOutputStream(uri)?.use { local ->
                                webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use { remote ->
                                    remote.copyTo(local, 8192)

                                    mediaDetails.clear()
                                    mediaDetails.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    cr.update(uri, mediaDetails, null, null)
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val fileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/${remotePhoto.photo.name}"
                        File(fileName).outputStream().use { local ->
                            webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use { remote ->
                                remote.copyTo(local, 8192)
                            }
                        }
                        MediaScannerConnection.scanFile(context, arrayOf(fileName), arrayOf(remotePhoto.photo.mimeType), null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // Video is now streaming, there is no local cache available, and might take some time to download, so we resort to Download Manager
/*
            (context.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}"))
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, remotePhoto.photo.name)
                    .setTitle(remotePhoto.photo.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .addRequestHeader("Authorization", "Basic $token")
            )
*/
            batchDownload(context, listOf(remotePhoto))
        }
    }

    fun batchDownload(context: Context, targets: List<RemotePhoto>) {
        val dm = context.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager
        targets.forEach { remotePhoto ->
            dm.enqueue(
                DownloadManager.Request(Uri.parse("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}"))
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, remotePhoto.photo.name.substringAfterLast("/"))
                    .setTitle(remotePhoto.photo.name.substringAfterLast("/"))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .addRequestHeader("Authorization", "Basic $token")
            )
        }
    }

/*
    fun savePhoto(context: Context, photo: RemotePhoto) {
        // Clone a new HttpClient to avoid leaking webDav
        WorkManager.getInstance(context).enqueueUniqueWork("DOWNLOAD_${photo.fileId}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf(
                DownloadWorker.REMOTE_PHOTO_PATH_KEY to photo.path, DownloadWorker.REMOTE_PHOTO_MIMETYPE_KEY to photo.mimeType, DownloadWorker.RESOURCE_ROOT_KEY to resourceRoot)
            ).build()
        )
    }

    class DownloadWorker(private val context: Context, workerParams: WorkerParameters): CoroutineWorker(context, workerParams) {
        @Suppress("BlockingMethodInNonBlockingContext")
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val photoPath = inputData.keyValueMap[REMOTE_PHOTO_PATH_KEY] as String
            val photoMimetype = inputData.keyValueMap[REMOTE_PHOTO_MIMETYPE_KEY] as String
            val resourceRoot = inputData.keyValueMap[RESOURCE_ROOT_KEY] as String

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaDetails = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, photoPath.substringAfterLast('/'))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.MIME_TYPE, photoMimetype)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaDetails)?.let { uri ->
                        cr.openOutputStream(uri)?.use { local ->
                            httpClient.newCall(Request.Builder().url("$resourceRoot${photoPath}").build()).execute().body?.byteStream()?.use { remote->
                                remote.copyTo(local, 8192)

                                mediaDetails.clear()
                                mediaDetails.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                cr.update(uri, mediaDetails, null, null)

                                Result.success()
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), photoPath.substringAfterLast('/')).outputStream().use { local ->
                        httpClient.newCall(Request.Builder().url("$resourceRoot${photoPath}").build()).execute().body?.byteStream()?.use { remote->
                            remote.copyTo(local, 8192)

                            Result.success()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Result.failure()
        }

        companion object {
            const val REMOTE_PHOTO_PATH_KEY = "REMOTE_PHOTO_PATH_KEY"
            const val REMOTE_PHOTO_MIMETYPE_KEY = "REMOTE_PHOTO_MIMETYPE_KEY"
            const val WEBDAV_KEY = "WEBDAV_KEY"
            const val RESOURCE_ROOT_KEY = "RESOURCE_ROOT_KEY"
        }
    }

*/


    // Pico CMS integration
    fun listBlogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = getCSRFToken()

                webDav.getCallFactory().newCall(Request.Builder().url("${baseUrl}${PICO_WEBSITES_ENDPOINT}")
                    .addHeader("requesttoken", token.first).addHeader("cookie", token.second).addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").get().build()).execute().use { response ->
                    if (response.isSuccessful) _blogs.value = collectResult(response.body?.string())
                }
            } catch (_: Exception) {}
        }
    }
    
    fun createBlog(album: Album) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Site name asserted in Pico's lib/Model/Website.php
                val validName = album.name.let {
                    when(it.length) {
                        in (1..2) -> "${it}  "
                        in (3..255) -> it
                        else -> it.substring(0, 254)
                    }
                }

                val token = getCSRFToken()
                
                webDav.getCallFactory().newCall(Request.Builder().url("${baseUrl}${PICO_WEBSITES_ENDPOINT}")
                    .addHeader("requesttoken", token.first).addHeader("cookie", token.second).addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true")
                    .post(FormBody.Builder()
                        .addEncoded("data[name]", validName)
                        .addEncoded("data[path]", "${lespasBase}/${album.name}/.blog")
                        .addEncoded("data[site]", album.id)
                        .addEncoded("data[theme]", "default")
                        .addEncoded("data[template]", "empty")
                        .build()
                    ).build()
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        // If successful, Pico return blog list
                        _blogs.value = collectResult(response.body?.string())
                    }
                    else Log.e(">>>>>>>>", "createBlog: ${response.code}")
                }
            } catch (_: Exception) {}
        }
    }

    fun deleteBlog(blogId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = getCSRFToken()

                webDav.getCallFactory().newCall(Request.Builder().url("${baseUrl}${PICO_WEBSITES_ENDPOINT}/${blogId}")
                    .addHeader("requesttoken", token.first).addHeader("cookie", token.second).addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").delete().build()
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        // If successful, Pico return blog list
                        _blogs.value = collectResult(response.body?.string())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun collectResult(body: String?): List<Blog> {
        val blogs = mutableListOf<Blog>()
        body?.let {
            val sites = JSONObject(it).getJSONArray("websites")
            for (i in 0 until sites.length()) {
                sites.getJSONObject(i).run {
                    blogs.add(Blog(getString("id"), getString("name"), getString("site"), getString("theme"), getInt("type"), getString("path"), getLong("creation")))
                }
            }
        }

        return blogs
    }

    private fun getCSRFToken(): Pair<String, String> {
        var cookies = ""
        var csrfToken = ""
        webDav.getCall("${baseUrl}${CSRF_TOKEN_ENDPOINT}", false, null).execute().use { response ->
            if (response.isSuccessful) {
                response.headers.values("Set-Cookie").forEach { cookie ->
                    cookies = "$cookies$cookie; "
                }
                cookies = cookies.substringBeforeLast("; ")
                response.body?.string()?.let { json-> csrfToken = JSONObject(json).getString("token") }
            }
        }

        return Pair(csrfToken, cookies)
    }

    private val cr = application.contentResolver
    private val placeholderBitmap = ContextCompat.getDrawable(application, R.drawable.ic_baseline_placeholder_24)!!.toBitmap()
    private val loadingDrawable = ContextCompat.getDrawable(application, R.drawable.animated_loading_indicator) as AnimatedVectorDrawable
    private val loadingDrawableLV = ContextCompat.getDrawable(application, R.drawable.animated_loading_indicator_lv) as AnimatedVectorDrawable
    private val downloadDispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / MEMORY_CACHE_SIZE * 1024 * 1024)
    private val decoderJobMap = HashMap<Int, Job>()
    private val httpCallMap = HashMap<Job, Call>()
    private val mediaMetadataRetriever by lazy { MediaMetadataRetriever() }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun setImagePhoto(imagePhoto: RemotePhoto, view: ImageView, viewType: String, callBack: LoadCompleteListener? = null) {
        val jobKey = System.identityHashCode(view)

        // For full image, show a thumbnail version first
        if (viewType == TYPE_FULL) {
            imageCache.get("${imagePhoto.photo.id}${TYPE_GRID}")?.let {
                // Show cached low resolution bitmap first before loading full size bitmap
                view.setImageBitmap(it)
                callBack?.onLoadComplete()
            } ?: run {
                // For camera roll items, load thumbnail if cache missed
                if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                    try {
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            view.context.contentResolver.loadThumbnail(Uri.parse(imagePhoto.photo.id), Size(imagePhoto.photo.width / 4, imagePhoto.photo.height / 4), null)
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Thumbnails.getThumbnail(cr, imagePhoto.photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                                if (imagePhoto.photo.orientation != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(imagePhoto.photo.orientation.toFloat()) }, true)
                                else this
                            }
                        })?.let {
                            view.setImageBitmap(it)
                            callBack?.onLoadComplete()
                        }
                    } catch (_: Exception) {}
                } else view.setImageDrawable(null)
            }
        } else view.setImageDrawable(null)

        // For items of remote album, show loading animation
        if (imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
            view.background = (if (viewType == TYPE_FULL) loadingDrawableLV else loadingDrawable).apply { start() }

            // Showing photo in map requires drawable's intrinsicHeight to find proper marker position, it's not yet available
            if (viewType != TYPE_IN_MAP) callBack?.onLoadComplete()
        }

        val job = viewModelScope.launch(downloadDispatcher) {
            var bitmap: Bitmap? = null
            var animatedDrawable: Drawable? = null
            val forceNetwork = imagePhoto.photo.shareId and Photo.NEED_REFRESH == Photo.NEED_REFRESH

            try {
                var type = if (imagePhoto.photo.mimeType.startsWith("video")) TYPE_VIDEO else viewType
                //var key = if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) "camera${imagePhoto.photo.id.substringAfterLast("/media/")}" else "${imagePhoto.photo.id}$type"
                var key = "${imagePhoto.photo.id}$type"
                if ((type == TYPE_COVER) || (type == TYPE_SMALL_COVER)) key = "$key-${imagePhoto.coverBaseLine}"

                (if (forceNetwork) null else imageCache.get(key))?.let {
                    bitmap = it
                    //Log.e(">>>>>>>>>","got cache hit $key")
                } ?: run {
                    // Cache missed

                    bitmap = when (type) {
                        TYPE_GRID, TYPE_IN_MAP -> {
                            val thumbnailSize = if ((imagePhoto.photo.height < 1440) || (imagePhoto.photo.width < 1440)) 2 else 8
                            when {
                                imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED -> getRemoteThumbnail(coroutineContext.job, imagePhoto, type, forceNetwork)
                                imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, Uri.parse(imagePhoto.photo.id))) { decoder, _, _ -> decoder.setTargetSampleSize(thumbnailSize) }
                                        // TODO: For photo captured in Sony Xperia machine, loadThumbnail will load very small size bitmap
                                        //contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width/8, photo.height/8), null)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        MediaStore.Images.Thumbnails.getThumbnail(cr, imagePhoto.photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                                            if (imagePhoto.photo.orientation != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(imagePhoto.photo.orientation.toFloat()) }, true)
                                            else this
                                        }
                                    }
                                }
                                else -> {
                                    // File is available locally, already rotated to it's upright position. Fall back to remote
                                    BitmapFactory.decodeFile("${localFileFolder}/${imagePhoto.photo.id}", BitmapFactory.Options().apply { inSampleSize = thumbnailSize }) ?: run { getRemoteThumbnail(coroutineContext.job, imagePhoto, type) }
                                }
                            }
                        }
                        TYPE_VIDEO -> getVideoThumbnail(coroutineContext.job, imagePhoto)
                        TYPE_EMPTY_ROLL_COVER -> ContextCompat.getDrawable(view.context, R.drawable.empty_roll)!!.toBitmap()
                        else -> {
                            // For GIF, AGIF, AWEBP cover
                            if (imagePhoto.coverBaseLine == Album.SPECIAL_COVER_BASELINE) type = TYPE_FULL

                            ensureActive()
                            when {
                                imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED -> {
                                    // Photo is from remote album and is already uploaded
                                    getImageStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true, null, coroutineContext.job)
                                }
                                imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL -> {
                                    // Photo is from local Camrea roll
                                    cr.openInputStream(Uri.parse(imagePhoto.photo.id))
                                }
                                else -> {
                                    // Photo is from local album or not being uploaded yet, e.g., in local storage
                                    try {
                                        File("${localFileFolder}/${imagePhoto.photo.id}").inputStream()
                                    } catch (e: FileNotFoundException) {
                                        // Fall back to network fetching if loading local file failed
                                        getImageStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true, null, coroutineContext.job)
                                    }
                                }
                            }?.use { sourceStream ->
                                when (type) {
                                    //TYPE_FULL, TYPE_QUARTER -> {
                                    TYPE_FULL -> {
                                        when {
                                            (imagePhoto.photo.mimeType == "image/awebp" || imagePhoto.photo.mimeType == "image/agif") ||
                                            (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL && (imagePhoto.photo.mimeType == "image/webp" || imagePhoto.photo.mimeType == "image/gif")) -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                    // Some framework implementation will crash when using ByteBuffer as ImageDrawable source
                                                    val tempFile = File(localCacheFolder, imagePhoto.photo.name)
                                                    ensureActive()
                                                    tempFile.outputStream().run { sourceStream.copyTo(this, 8192) }
                                                    ensureActive()
                                                    animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(tempFile)).apply {
                                                        if (this is AnimatedImageDrawable) {
                                                            if (sp.getBoolean(autoReplayKey, true)) this.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                                            start()
                                                        }
                                                    }
                                                    tempFile.delete()
                                                    null
                                                } else {
                                                    ensureActive()
                                                    BitmapFactory.decodeStream(sourceStream, null, BitmapFactory.Options().apply { inSampleSize = if (imagePhoto.photo.width < 2000) 2 else 8 })
                                                }
                                            }
                                            else -> {
                                                val option = BitmapFactory.Options().apply {
                                                    // Shrink large photo, allocationByteCount could not exceed 100,000,000 bytes
                                                    inSampleSize = ((imagePhoto.photo.width * imagePhoto.photo.height) / 25000000).let { size -> if (size > 0) size * 2 else 1 }
                                                    //if (type == TYPE_QUARTER) inSampleSize *= 2
                                                    // TODO Cautious when meta is not available yet, prevent crash when viewing large photo shot by other devices, such as some Huawei
                                                    if (imagePhoto.photo.width == 0) inSampleSize = 2
                                                }

                                                ensureActive()
                                                BitmapFactory.decodeStream(sourceStream, null, option)?.run {
                                                    ensureActive()
                                                    if (imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.width == 0) {
                                                        // This is a early backup of camera roll which do not has meta info yet
                                                        getMediaExif(imagePhoto)?.first?.let { exif ->
                                                            imagePhoto.photo.orientation = exif.rotationDegrees
                                                        }
                                                    }
                                                    if (
                                                        imagePhoto.photo.orientation != 0 &&
                                                        ((imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) || imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL)
                                                    ) Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { preRotate((imagePhoto.photo.orientation).toFloat()) }, true)
                                                    else this
                                                }
                                            }
                                        }
                                    }
                                    TYPE_COVER, TYPE_SMALL_COVER -> {
                                        //Log.e(">>>>>>>>>>>", "$key $imagePhoto")
                                        var width = imagePhoto.photo.width
                                        var height = imagePhoto.photo.height
                                        var orientation = imagePhoto.photo.orientation

                                        if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                                            if (orientation == 90 || orientation == 270) {
                                                width = imagePhoto.photo.height
                                                height = imagePhoto.photo.width
                                            }
                                        } else {
                                            if (!(imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED)) orientation = 0
                                        }
                                        val rect =
                                            when (orientation) {
                                                0 -> Rect(0, imagePhoto.coverBaseLine, width - 1, min(imagePhoto.coverBaseLine + (width.toFloat() * 9 / 21).toInt(), height - 1))
                                                90 -> Rect(imagePhoto.coverBaseLine, 0, min(imagePhoto.coverBaseLine + (width.toFloat() * 9 / 21).toInt(), height - 1), width - 1)
                                                180 -> (height - imagePhoto.coverBaseLine).let { Rect(0, Integer.max(it - (width.toFloat() * 9 / 21).toInt(), 0), width - 1, it) }
                                                else -> (height - imagePhoto.coverBaseLine).let { Rect(Integer.max(it - (width.toFloat() * 9 / 21).toInt(), 0), 0, it, width - 1) }
                                            }

                                        val option = BitmapFactory.Options().apply {
                                            var sampleSize = when (width) {
                                                in (0..1439) -> 1
                                                in (1439..3000) -> 2
                                                else -> 4
                                            }
                                            if (type == TYPE_SMALL_COVER) sampleSize *= 2

                                            inSampleSize = sampleSize
                                        }

                                        ensureActive()
                                        @Suppress("DEPRECATION")
                                        (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(sourceStream) else BitmapRegionDecoder.newInstance(sourceStream, false))?.decodeRegion(rect, option)?.let { bmp ->
                                            ensureActive()
                                            if (orientation != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preRotate(orientation.toFloat()) }, true)
                                            else bmp
                                        }
                                    }
                                    else -> { null }
                                }
                            }
                        }
                    }

                    if (bitmap != null && type != TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: OkHttpWebDavException) {
                //Log.e(">>>>>>>>>>", "${e.statusCode} ${e.stackTraceString}")
            }
            catch (e: Exception) {
                //e.printStackTrace()
            }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    animatedDrawable?.let { view.setImageDrawable(it) } ?: run { view.setImageBitmap(bitmap ?: placeholderBitmap) }

                    // Stop loading indicator
                    view.setBackgroundResource(0)
                }
                callBack?.onLoadComplete()
            }
        }.apply {
            invokeOnCompletion {
                try {
                    it?.cause.let { cause ->
                        if (cause is CancellationException) {
                            decoderJobMap[jobKey]?.let { job ->
                                httpCallMap[job]?.let { httpCall ->
                                    httpCall.cancel()
                                    httpCallMap.remove(job)
                                }
                            }
                        } else httpCallMap.remove(job)
                    }
                    decoderJobMap.remove(jobKey)
                } catch (e: Exception) {}
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    private fun getVideoThumbnail(job: Job, imagePhoto: RemotePhoto): Bitmap? {
        return if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
            val photoId = imagePhoto.photo.id.substringAfterLast('/').toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    cr.loadThumbnail(Uri.parse(imagePhoto.photo.id), Size(imagePhoto.photo.width, imagePhoto.photo.height), null)
                } catch (e: Exception) {
                    // Some Android Q Rom, like AEX for EMUI 9, throw ArithmeticException
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(cr, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(cr, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
            }
        } else {
            var bitmap: Bitmap? = null
            val thumbnail = File(if (imagePhoto.remotePath.isEmpty()) localFileFolder else localCacheFolder, "${imagePhoto.photo.id}.thumbnail")

            // Load from local cache
            if (thumbnail.exists()) bitmap = BitmapFactory.decodeStream(thumbnail.inputStream())

            // Download from server
            bitmap ?: run {
                bitmap = getRemoteVideoThumbnail(imagePhoto, job)

                // Cache thumbnail in local
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, thumbnail.outputStream())
            }

            bitmap
        }
    }

    @Synchronized private fun getRemoteVideoThumbnail(imagePhoto: RemotePhoto, job: Job): Bitmap? {
        job.ensureActive()
        var bitmap: Bitmap?
        var remoteDataSource: VideoMetaDataMediaSource? = null

        mediaMetadataRetriever.apply {
            if (imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                // Should allow "/" in photo's remote path string, obviously, and name string, that's for fetching camera backups on server
                //setDataSource("$resourceRoot${Uri.encode(imagePhoto.remotePath, "/")}/${Uri.encode(imagePhoto.photo.name, "/")}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })
                remoteDataSource = VideoMetaDataMediaSource(imagePhoto, resourceRoot, webDav, job, httpCallMap)
                setDataSource(remoteDataSource)
            } else setDataSource("${localFileFolder}/${imagePhoto.photo.id}")

            bitmap = getFrameAtTime(0L, MediaMetadataRetriever.OPTION_NEXT_SYNC)

            // Call MediaDataSource close() here so that http calls can be cancelled asap
            remoteDataSource?.close()
        }

        return bitmap
    }

    private fun getRemoteThumbnail(job: Job, imagePhoto: RemotePhoto, type: String, forceNetwork: Boolean = false): Bitmap? {
        var bitmap: Bitmap?

        // Nextcloud will not provide preview for webp, heic/heif, if preview is available, then it's rotated by Nextcloud to upright position
        bitmap = try {
            getImageStream("${baseUrl}${PREVIEW_ENDPOINT}${imagePhoto.photo.id}", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null, job).use {
                job.ensureActive()
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (type == TYPE_GRID) 2 else 1 })
            }
        } catch(e: Exception) { null }

        bitmap ?: run {
            // If preview is not available, we have to use the actual image file
            getImageStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true,null, job).use {
                job.ensureActive()
                bitmap = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if ((imagePhoto.photo.height < 1440) || (imagePhoto.photo.width < 1440)) 2 else 8 })
            }
            if (imagePhoto.photo.orientation != 0) bitmap?.let {
                job.ensureActive()
                bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, Matrix().apply { preRotate((imagePhoto.photo.orientation).toFloat()) }, true)
            }
        }

        bitmap?.let { if (forceNetwork) photoRepository.resetNetworkRefresh(imagePhoto.photo.id) }

        return bitmap
    }

    private fun getImageStream(source: String, useCache: Boolean, cacheControl: CacheControl?, job: Job): InputStream {
        webDav.getCall(source, useCache, cacheControl).run {
            httpCallMap.replace(job, this)
            job.ensureActive()
            execute().also { response ->
                if (response.isSuccessful) return response.body!!.byteStream()
                else {
                    response.close()
                    throw OkHttpWebDavException(response)
                }
            }
        }
    }

    fun cancelSetImagePhoto(view: View) {
        System.identityHashCode(view).let { jobKey -> decoderJobMap[jobKey]?.cancel() }
    }

    fun invalidPhoto(photoId: String) {
        imageCache.snapshot().keys.forEach { key-> if (key.startsWith(photoId)) imageCache.remove(key) }
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
        //File(localCacheFolder, OkHttpWebDav.VIDEO_CACHE_FOLDER).deleteRecursively()
        decoderJobMap.forEach { if (it.value.isActive) {
            httpCallMap[it.value]?.cancel()
            it.value.cancel()
        }}
        downloadDispatcher.close()
        mediaMetadataRetriever.release()
        videoPlayerCache?.release()
        super.onCleared()
    }

    class VideoMetaDataMediaSource(imagePhoto: RemotePhoto, resourceRoot: String, private val webDav: OkHttpWebDav, private val job: Job, private val callMap: HashMap<Job, Call>): MediaDataSource() {
        private val mediaUrl: String
        private var mediaSize: Long = 0
        private val header = ByteArray(HEADER_SIZE)
        private var headerSize: Int = 0
        private var tail: ByteArray? = null
        private var tailStart: Long = 0

        private var mBufferSource: BufferedSource? = null
        private var mWorkBuffer: BufferedSource? = null
        private var mBufferOffset: Long = 0
        private var mBufferRangeStart: Long = Long.MAX_VALUE

        init {
            mediaUrl = "$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}"
            //Log.e(">>>>>>>>>>>", "loading $mediaUrl")
            // Get header and media size
            var retry = 0
            while(retry <= MAXIMUM_RETRY) {
                job.ensureActive()
                try {
                    webDav.getCall(mediaUrl, true, null).run {
                        callMap[job] = this
                        execute().also { response ->
                            mediaSize = response.headersContentLength()
                            tailStart = mediaSize - (mediaSize / 20)
                            //Log.e(">>>>>>>>", "VideoMetaDataMediaSource: $mediaSize $tailStart")
                            response.body!!.byteStream().source().buffer().use {
                                var byteRead = 0
                                headerSize = if (mediaSize < HEADER_SIZE.toLong()) mediaSize.toInt() else HEADER_SIZE
                                while(byteRead < headerSize) {
                                    byteRead += it.read(header, byteRead, HEADER_SIZE - byteRead)
                                }
                            }
                        }
                    }
                    break
                } catch (e: SocketTimeoutException) {
                    retry++
                    //Log.e(">>>>>>>>>>>", "head reading retry $retry")
                    sleep(retry * BACKOFF_INTERVAL)
                }
            }
        }

        private fun seekAt(position: Long) {
            //Log.e(">>>>>>>>>>>>>>", "seekAt $position ${mediaSize - position}")
            // Cancel previous call
            mWorkBuffer?.close()
            mBufferSource?.close()
            callMap[job]?.cancel()

            var retry = 0
            while(retry <= MAXIMUM_RETRY) {
                try {
                    webDav.getRangeCall(mediaUrl, position).run {
                        callMap[job] = this
                        job.ensureActive()
                        this.execute().let { response ->
                            response.body?.let {
                                mBufferSource = it.byteStream().source().buffer()
                                mWorkBuffer = mBufferSource?.peek()
                                mBufferOffset = position
                                mBufferRangeStart = position
                            }
                        }
                    }
                    break
                } catch (e: SocketTimeoutException) {
                    retry++
                    //Log.e(">>>>>>>>>>>", "seek reading retry $retry")
                    sleep(retry * BACKOFF_INTERVAL)
                }
            }
        }

        override fun close() {
            callMap[job]?.cancel()
            callMap.remove(job)
            //Log.e(">>>>>>>>>>", "closed $mediaUrl\n\n\n")
        }

        override fun getSize(): Long = mediaSize    //.apply { Log.e(">>>>>>>>>>", "getSize $this") }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            var byteRead = 0

            try {
                when {
                    position >= mediaSize -> byteRead = -1
                    position + size < headerSize -> {
                        //Log.e(">>>>>>>>>>>>>", "reading in head buffer $position $size")
                        position.toInt().let { startIndex -> header.copyInto(buffer, offset, startIndex, startIndex + size) }
                        byteRead = size
                    }
                    position > tailStart -> {
                        //Log.e(">>>>>>>>>>>>>", "reading in tail buffer $position $size")
                        tail ?: run {
                            callMap[job]?.cancel()
                            var retry = 0
                            while(retry <= MAXIMUM_RETRY) {
                                try {
                                    webDav.getRangeCall(mediaUrl, position).run {
                                        callMap[job] = this
                                        job.ensureActive()
                                        this.execute().let { response ->
                                            response.body?.let { body ->
                                                body.byteStream().source().buffer().use { s ->
                                                    val tailSize = (mediaSize - position).toInt()   //.apply { Log.e(">>>>>>>>>>>", "tail buffer size: $this") }
                                                    tail = ByteArray(tailSize)

                                                    var br = 0
                                                    while(br < tailSize) br += s.read(tail!!, br, tailSize - br)
                                                }
                                                tailStart = position
                                            }
                                        }
                                    }
                                    break
                                } catch (e: SocketTimeoutException) {
                                    retry++
                                    //Log.e(">>>>>>>>>>>", "tail reading retry $retry")
                                    sleep(retry * BACKOFF_INTERVAL)
                                }
                            }
                        }

                        (position - tailStart).toInt().let { startIndex -> tail!!.copyInto(buffer, offset, startIndex, min(startIndex + size, tail!!.size)) }
                        byteRead = size
                    }
                    else -> {
                        //Log.e(">>>>>>>>>>>>>", "reading in middle buffer $position $size")
                        // Re-seek when reading beyond buffer start
                        if (position < mBufferRangeStart) seekAt(position)

                        when {
                            position == mBufferOffset -> {}
                            position > mBufferOffset -> {
                                //Log.e(">>>>>>", "forward from $currentOffset to $position by ${position - currentOffset}")
                                // Jump forward for longer than 2MB, re-seek to avoid over-reading TODO is it worth another http call
                                if (position - mBufferOffset > SKIP_LIMIT) seekAt(position)
                                else {
                                    // Jump forward for less than 2MB ahead, use skip
                                    mWorkBuffer?.skip(position - mBufferOffset)
                                    mBufferOffset = position
                                }
                            }
                            else -> {
                                // Jump backward, rewind without another http call
                                //Log.e(">>>>>>>>>>>", "rewind from $mBufferOffset to $position by ${position - mBufferOffset}")
                                mWorkBuffer?.closeQuietly()
                                mWorkBuffer = mBufferSource?.peek()
                                mWorkBuffer?.skip(position - mBufferRangeStart)
                                mBufferOffset = position
                            }
                        }
                        job.ensureActive()
                        byteRead = mWorkBuffer?.read(buffer, offset, size) ?: -1
                        if (byteRead != -1) mBufferOffset += byteRead
                    }
                }
            } catch (e: CancellationException) {
                //Log.e(">>>>>>>>>>>", "job cancel, closing datasource")
                close()
            }

            return byteRead
        }

        companion object {
            private const val HEADER_SIZE = 312 * 1024          // 312K header box size
            private const val SKIP_LIMIT = 2 * 1024 * 1024L     // 2MB skip limit
            private const val MAXIMUM_RETRY = 2                 // Maximum retry for http read timeout
            private const val BACKOFF_INTERVAL = 800L           // Retry backoff interval in millisecond
        }
    }

    class ImageCache(maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Parcelize
    data class Sharee(
        var name: String,
        var label: String,
        var type: Int,
    ) : Parcelable

    @Parcelize
    data class Recipient(
        var shareId: String,
        var permission: Int,
        var sharedTime: Long,
        var sharee: Sharee,
    ) : Parcelable

    @Parcelize
    data class ShareByMe(
        var fileId: String,
        var folderName: String,
        var with: MutableList<Recipient>,
    ) : Parcelable

    @Parcelize
    data class ShareWithMe(
        var shareId: String,
        var sharePath: String,
        var albumId: String,
        var albumName: String,
        var shareBy: String,
        var shareByLabel: String,
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var sortOrder: Int,
        var lastModified: Long,
    ) : Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.lastModified - this.lastModified).toInt()
    }

    @Parcelize
    data class RemotePhoto(
        val photo: Photo,
        val remotePath: String = "",
        val coverBaseLine: Int = 0,
    ) : Parcelable

    @Parcelize
    data class Blog(
        var id: String = "",
        var name: String = "",
        var site: String = "",
        var theme: String = "",
        var type: Int = BLOG_TYPE_PUBLIC,
        var path: String = "",
        var timestamp: Long = 0L,
    ) : Parcelable

    companion object {
        const val TYPE_NULL = ""    // For startPostponedEnterTransition() immediately for video item
        const val TYPE_GRID = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"
        const val TYPE_SMALL_COVER = "_smallcover"
        //const val TYPE_QUARTER = "_quarter"
        const val TYPE_VIDEO = "_video"
        const val TYPE_IN_MAP = "_map"
        const val TYPE_EMPTY_ROLL_COVER = "empty"

        private const val MEMORY_CACHE_SIZE = 8     // one eighth of heap size

        private const val SHARED_BY_ME_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares?path=%s&subfiles=true&reshares=false&format=json"
        private const val SHARED_WITH_ME_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares?shared_with_me=true&format=json"
        private const val SHAREE_LISTING_ENDPOINT = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        //private const val CAPABILITIES_ENDPOINT = "/ocs/v1.php/cloud/capabilities?format=json"
        private const val PUBLISH_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares"
        private const val AVATAR_ENDPOINT = "/index.php/avatar/"
        private const val PREVIEW_ENDPOINT = "/index.php/core/preview?x=1024&y=1024&a=true&fileId="

        // Pico integration
        private const val CSRF_TOKEN_ENDPOINT = "/csrftoken"
        private const val PICO_WEBSITES_ENDPOINT = "/index.php/apps/cms_pico/personal/websites"

        const val SHARE_TYPE_USER = 0
        const val SHARE_TYPE_GROUP = 1
        //const val SHARE_TYPE_PUBLIC = 3
        //private const val SHARE_TYPE_USER_STRING = "user"
        //private const val SHARE_TYPE_GROUP_STRING = "group"
        //private const val SHARE_TYPE_PUBLIC_STRING = "public"

        const val PERMISSION_CAN_READ = 1
        private const val PERMISSION_CAN_UPDATE = 2
        private const val PERMISSION_CAN_CREATE = 4
        const val PERMISSION_JOINT = PERMISSION_CAN_CREATE + PERMISSION_CAN_UPDATE + PERMISSION_CAN_READ
        //private const val PERMISSION_CAN_DELETE = 8
        //private const val PERMISSION_CAN_SHARE = 16
        //private const val PERMISSION_ALL = 31

        const val BLOG_TYPE_PUBLIC = 1
        const val BLOG_TYPE_PRIVATE = 2
    }
}