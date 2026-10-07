package jbro.cobblemon.dimensions.worldgen

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.Optional
import java.util.function.Consumer
import jbro.cobblemon.dimensions.CobblemonDimensions
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.MetadataSectionSerializer
import net.minecraft.server.packs.metadata.pack.PackMetadataSection
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.resources.IoSupplier

/**
 * A built-in data pack, always on, holding the dimensions, terrain settings and biomes [DimensionWorldgen] builds when
 * the game starts. `ServerPacksSourceMixin` adds it to every server data pack list.
 */
object WorldgenPack : RepositorySource {
    private val location = PackLocationInfo("${CobblemonDimensions.MOD_ID}:worldgen",
        Component.literal("Cobblemon Dimensions worldgen"), PackSource.BUILT_IN, Optional.empty())

    /** Built once: the installed mods do not change while the game runs. */
    private val files: Map<ResourceLocation, ByteArray> by lazy { build() }

    override fun loadPacks(consumer: Consumer<Pack>) {
        val supplier = object : Pack.ResourcesSupplier {
            override fun openPrimary(location: PackLocationInfo): PackResources = Resources
            override fun openFull(location: PackLocationInfo, metadata: Pack.Metadata): PackResources = Resources
        }
        Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, PackSelectionConfig(true, Pack.Position.TOP, false))
            ?.let(consumer::accept)
    }

    private fun build(): Map<ResourceLocation, ByteArray> {
        val loader = FabricLoader.getInstance()
        fun reader(modId: String): (String) -> String? = { path ->
            loader.getModContainer(modId).flatMap { it.findPath(path) }.filter(Files::exists).map(Files::readString).orElse(null)
        }
        val own = reader(CobblemonDimensions.MOD_ID)
        val source = if (loader.isModLoaded("terralith")) WorldgenSource.terralith(reader("terralith"), reader("minecraft"), own)
            else WorldgenSource.vanilla(reader("minecraft"), own)
        return try {
            val spec = JsonParser.parseString(own("cobblemon_dimensions/worldgen.json")).asJsonObject
            val gson = GsonBuilder().create()
            DimensionWorldgen.generate(spec, source)
                .mapKeys { (path, _) -> ResourceLocation.fromNamespaceAndPath(CobblemonDimensions.MOD_ID, path) }
                .mapValues { (_, json) -> gson.toJson(json).toByteArray() }
                .also { CobblemonDimensions.LOGGER.info("Built the dimensions from {} terrain ({} files)", source.name, it.size) }
        } catch (failure: RuntimeException) {
            CobblemonDimensions.LOGGER.error("Could not build the dimensions from {} terrain; they will be missing", source.name, failure)
            emptyMap()
        }
    }

    private object Resources : PackResources {
        override fun getRootResource(vararg path: String): IoSupplier<InputStream>? = null

        override fun getResource(type: PackType, id: ResourceLocation): IoSupplier<InputStream>? =
            if (type == PackType.SERVER_DATA) files[id]?.let(::supply) else null

        override fun listResources(type: PackType, namespace: String, path: String, output: PackResources.ResourceOutput) {
            if (type != PackType.SERVER_DATA) return
            for ((id, bytes) in files) {
                if (id.namespace == namespace && id.path.startsWith("$path/")) output.accept(id, supply(bytes))
            }
        }

        override fun getNamespaces(type: PackType): Set<String> =
            if (type == PackType.SERVER_DATA) setOf(CobblemonDimensions.MOD_ID) else emptySet()

        @Suppress("UNCHECKED_CAST")
        override fun <T> getMetadataSection(serializer: MetadataSectionSerializer<T>): T? =
            if (serializer.metadataSectionName == PackMetadataSection.TYPE.metadataSectionName) {
                PackMetadataSection(Component.literal("Cobblemon Dimensions worldgen"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA), Optional.empty()) as T
            } else null

        override fun location(): PackLocationInfo = location

        override fun close() {}

        private fun supply(bytes: ByteArray) = IoSupplier<InputStream> { ByteArrayInputStream(bytes) }
    }
}
