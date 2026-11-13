package io.github.simllll.panelelevate.dashboard.cards.vacuum

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.simllll.panelelevate.dashboard.format.StateFormatter
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl

private const val TAG = "VacuumMap"

/**
 * Entity translations (`component.<platform>.entity.<domain>.<translation_key>…`) for the tile
 * entities, which the frontend's `formatEntityState` / `formatEntityAttributeValue` use before the
 * generic `entity_component` strings (e.g. Roborock fan speed `balanced` → „Ausgewogen“).
 */
@Immutable
class EntityTranslations(private val keys: Map<String, String>, private val strings: Map<String, String>) {
    private fun base(entity: EntityState): String? = keys[entity.entityId]

    fun state(entity: EntityState): String? = base(entity)?.let { strings["$it.state.${entity.state}"] }

    fun attributeValue(entity: EntityState, attribute: String, value: String): String? =
        base(entity)?.let { strings["$it.state_attributes.$attribute.state.$value"] }

    companion object {
        val EMPTY = EntityTranslations(emptyMap(), emptyMap())

        /** Reads the registry entries of [entityIds] and the `entity` translations of their platforms. */
        suspend fun load(hass: HassClient, language: String, entityIds: Collection<String>): EntityTranslations = try {
            val entries = hass.command(jsonOf("type" to "config/entity_registry/get_entries", "entity_ids" to entityIds.toList())).asObject()
            val keys = HashMap<String, String>()
            entries?.forEach { (id, entry) ->
                val o = entry.asObject() ?: return@forEach
                val platform = o.string("platform") ?: return@forEach
                val key = o.string("translation_key") ?: return@forEach
                keys[id] = "component.$platform.entity.${id.substringBefore('.')}.$key"
            }
            val platforms = keys.values.map { it.split('.')[1] }.distinct()
            val strings = if (platforms.isEmpty()) emptyMap() else hass.getTranslations(language, "entity", platforms)
            EntityTranslations(keys, strings)
        } catch (e: Exception) {
            Log.w(TAG, "loading entity translations failed: ${e.message}")
            EMPTY
        }
    }
}

/** HA's formatted state of [entity] (`formatEntityState`), entity translations first. */
fun formatTileState(formatter: StateFormatter, translations: EntityTranslations, entity: EntityState, nowMs: Long): String =
    translations.state(entity) ?: formatter.formatState(entity, nowMs)

/** `formatEntityAttributeValue`: null → „—“, numbers localized, strings translated if possible. */
fun formatTileAttribute(formatter: StateFormatter, translations: EntityTranslations, entity: EntityState, attribute: String): String? {
    val raw = entity.attr(attribute) ?: return null
    val text = raw.asString() ?: return if (raw is kotlinx.serialization.json.JsonNull) "—" else raw.toString()
    translations.attributeValue(entity, attribute, text)?.let { return it }
    if (raw is kotlinx.serialization.json.JsonPrimitive && !raw.isString) return formatter.formatNumber(text)
    return text
}

/** A decoded map image and the size of the original image (calibration points refer to it). */
@Immutable
class MapImage(val bitmap: ImageBitmap, val naturalWidth: Int, val naturalHeight: Int)

/**
 * Loads the camera/image entity picture, decoded subsampled to about [targetWidth] px while
 * keeping the natural size (the shared ImageLoader does not expose it). Never cached: the map
 * changes while the robot cleans.
 */
suspend fun loadMapImage(hass: HassClient, url: String, targetWidth: Int): MapImage? = withContext(Dispatchers.IO) {
    try {
        val request = hass.request(url).newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
        val bytes = hass.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for map image")
                return@withContext null
            }
            response.body.bytes()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        if (targetWidth > 0) while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bitmap.prepareToDraw()
        MapImage(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
    } catch (e: Exception) {
        Log.w(TAG, "loading map image failed: ${e.message}")
        null
    }
}
