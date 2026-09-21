package kr.parkjh.pokefusion

import java.nio.file.Path

object PokeFusionConfigManager {
    @Volatile
    private var current = PokeFusionConfig(PokeFusionConfig.ERROR_PERMISSION_LEVEL)
    private lateinit var store: PokeFusionConfigStore

    @Synchronized
    fun initialize(configDirectory: Path): PokeFusionConfig {
        store = PokeFusionConfigStore(configDirectory)
        current = store.load()
        return current
    }

    fun current(): PokeFusionConfig = current

    @Synchronized
    fun save(config: PokeFusionConfig) {
        check(::store.isInitialized) { "Pokefusion 설정 저장소가 초기화되지 않았습니다." }
        store.save(config)
        current = config
    }
}
