package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.policy.plaza.Plaza
import net.minecraft.client.Minecraft

/** What Pichu says while no button is hovered: remarks on the player's state first, then its own chatter. */
object PichuLines {
    private val chatter = listOf(
        "피츄! 오늘도 같이 모험해요!",
        "볼에서 찌릿찌릿한 게 나와요… 아직 조절이 잘 안 돼요.",
        "전기를 너무 쓰면 저도 깜짝 놀라요. 피카츄 형아처럼 되고 싶은데!",
        "궁금한 게 생기면 위에 있는 버튼을 눌러 봐요. 제가 길을 알려 줄게요!",
        "포켓몬은 포켓몬끼리 배틀해야 쑥쑥 자라요. 직접 때리면 경험치가 안 생겨요!",
        "몬스터볼은 나무에 열리는 규토리로 만들어요. 아이템 위에서 R을 누르면 만드는 법이 보여요!",
        "관장님한테 도전하려면 광장의 리그 안내원을 찾아가요. 광장 도착 지점에서 북동쪽이에요!",
        "채팅 이름 앞의 볼은 리그 등급이에요. 배지를 모을수록 더 멋진 볼이 돼요!",
        "Tab을 누르면 포켓내비가 열려요. 주변에 누가 사는지 다 보여요!",
        "전설 포켓몬이 나타나면 채팅으로 알려 줘요. 놓치지 마요!",
        "저를 클릭하면 인사해 줄게요. 피츄!",
    )

    fun idle(client: Minecraft): List<String> = state(client).ifEmpty { chatter } + chatter

    /** Remarks that fit right now; several may apply, and they weigh more by being listed beside the chatter. */
    private fun state(client: Minecraft): List<String> = buildList {
        val party = CobblemonClient.storage.party.slots.filterNotNull()
        val fainted = party.count { it.currentHealth <= 0 }
        val hurt = party.count { it.currentHealth in 1 until it.maxHealth }
        val level = client.level
        when {
            party.isEmpty() -> add("파티에 포켓몬이 하나도 없어요! 먼저 같이 다닐 친구를 만들어요.")
            fainted == party.size -> add("포켓몬이 모두 쓰러져 있어요! 광장 간호순 누나한테 얼른 가 봐요.")
            fainted > 0 -> add("포켓몬 ${fainted}마리가 쓰러져 있어요. 광장 간호순 누나가 회복해 줄 거예요!")
            hurt > 0 -> add("다친 친구가 ${hurt}마리 있어요. 큰 배틀 전에는 회복부터 해요!")
            else -> add("파티 모두 기운이 넘쳐요! 오늘은 어디로 가 볼까요?")
        }
        if (party.size in 1..5) add("파티에 자리가 ${6 - party.size}칸 남았어요. 새 친구를 데려와요!")
        if (level?.dimension() == Plaza.DIMENSION) add("여기는 광장이에요! 리그·배틀타워·배틀팩토리 안내원은 북동쪽에 있어요.")
        if (level?.dimension()?.location()?.toString() == PichuScreen.MYROOM_DIMENSION) add("여기는 마이룸이에요. 아늑하고 좋네요!")
        if (level != null && level.dimension() != Plaza.DIMENSION) {
            val time = level.dayTime % 24_000
            if (time in 13_000..23_000) add("밤이 깊었어요… 밤에만 나오는 포켓몬도 있대요. 조심해요!")
            if (level.isRaining) add("비가 오네요. 비 올 때만 보이는 포켓몬도 있대요!")
        }
    }
}
