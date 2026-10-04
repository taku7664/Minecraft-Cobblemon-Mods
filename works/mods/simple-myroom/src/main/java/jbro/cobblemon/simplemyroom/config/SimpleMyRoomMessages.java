package jbro.cobblemon.simplemyroom.config;

import java.util.Map;

public final class SimpleMyRoomMessages {
    public String roomNotFound = "해당 유저가 아직 마이룸을 만들지 않았습니다.";
    public String visitBanned = "해당 마이룸에서 방문이 차단되었습니다.";
    public String roomPrivate = "해당 마이룸은 비공개 상태입니다.";
    public String enterCooldown = "마이룸 입장 명령은 {seconds}초 후 다시 사용할 수 있습니다.";
    public String dimensionUnavailable = "마이룸 차원을 불러오지 못했습니다.";
    public String roomNotInitialized = "해당 마이룸은 아직 초기화되지 않았습니다.";
    public String teleportFailed = "마이룸 이동에 실패했습니다.";
    public String enteredRoom = "{owner}님의 마이룸으로 이동했습니다.";
    public String notInsideRoom = "현재 마이룸 차원에 있지 않습니다.";
    public String noReturnPointFallback = "저장된 복귀 위치가 없어 오버월드 스폰으로 이동했습니다.";
    public String noReturnPoint = "저장된 복귀 위치가 없습니다.";
    public String invalidReturnDimension = "저장된 복귀 차원 정보가 올바르지 않습니다.";
    public String returnDimensionMissing = "원래 차원을 찾을 수 없습니다. 복귀 정보는 보존되었습니다.";
    public String returnFailed = "복귀에 실패했습니다. 복귀 정보는 보존되었습니다.";
    public String returned = "마이룸 입장 전 위치로 복귀했습니다.";
    public String publicEnabled = "마이룸 방문을 허용했습니다.";
    public String publicDisabled = "마이룸 방문을 차단했습니다.";
    public String cannotTargetSelf = "자기 자신에게는 이 명령을 사용할 수 없습니다.";
    public String roomRequired = "먼저 자신의 마이룸을 생성해야 합니다.";
    public String playerBanned = "{player}님의 마이룸 방문을 차단했습니다.";
    public String playerUnbanned = "{player}님의 방문 차단을 해제했습니다.";
    public String playerTrusted = "{player}님에게 마이룸 행동 권한을 허용했습니다.";
    public String playerUntrusted = "{player}님의 마이룸 행동 권한을 해제했습니다.";
    public String playerNotInRoom = "해당 유저가 현재 자신의 마이룸에 없습니다.";
    public String playerKicked = "{player}님을 마이룸에서 내보냈습니다.";
    public String kickedByOwner = "마이룸 소유자에 의해 퇴장되었습니다.";
    public String denied = "이 마이룸에서는 해당 행동을 할 권한이 없습니다.";
    public String placementDenied = "자신의 마이룸 영역 안에서만 블록을 설치할 수 있습니다.";
    public String configReloaded = "Simple MyRoom 설정을 다시 불러왔습니다. 명령어 별칭과 방 배치 설정은 다음 서버 시작부터 적용됩니다.";
    public String configReloadFailed = "설정을 다시 불러오지 못했습니다: {reason}";
    public String invalidRoomRespawn = "사용할 수 없는 마이룸 리스폰 지점이어서 오버월드 스폰으로 이동했습니다.";
    public String activeEnabled = "마이룸 상시 활성화를 켰습니다. 농작물, 블록 엔티티, 방목 포켓몬이 계속 처리됩니다.";
    public String activeDisabled = "마이룸 상시 활성화를 껐습니다.";
    public String activeAlreadyEnabled = "마이룸 상시 활성화가 이미 켜져 있습니다.";
    public String activeAlreadyDisabled = "마이룸 상시 활성화가 이미 꺼져 있습니다.";
    public String activeGloballyDisabled = "서버 설정에서 마이룸 상시 활성화 기능이 꺼져 있습니다.";
    public String activeToggleNotAllowed = "일반 유저는 마이룸 상시 활성화 설정을 바꿀 수 없습니다.";
    public String activeRoomNotInitialized = "먼저 자신의 마이룸에 한 번 입장해 방을 생성해야 합니다.";
    public String activeRoomLimitReached = "동시에 활성화할 수 있는 마이룸 한도({limit}개)에 도달했습니다.";
    public String activeChunkLimitExceeded = "이 마이룸에 필요한 청크 수가 방당 제한({limit}개)을 초과합니다.";
    public String activeCooldown = "상시 활성화 명령은 {seconds}초 후 다시 사용할 수 있습니다.";
    public String activeStatus = "상시 활성화: {state} ({chunks}청크)";
    public String activeStatusWithoutChunks = "상시 활성화: {state}";
    public String customSpawnDisabled = "서버 설정에서 마이룸 입장 위치 지정 기능이 꺼져 있습니다.";
    public String customSpawnOwnerRoomRequired = "자신의 마이룸 영역 안에서만 입장 위치를 지정할 수 있습니다.";
    public String customSpawnUnsafe = "현재 위치는 입장 지점으로 사용하기에 안전하지 않습니다.";
    public String customSpawnSet = "현재 위치와 바라보는 방향을 마이룸 입장 위치로 지정했습니다.";
    public String customSpawnReset = "마이룸 입장 위치를 기본 중앙 위치로 되돌렸습니다.";
    public String customSpawnResetDisabled = "서버 설정에서 입장 위치 초기화가 금지되어 있습니다.";
    public String customSpawnNotSet = "별도로 지정된 마이룸 입장 위치가 없습니다.";
    public String customSpawnFallback = "지정된 입장 위치가 막혀 있어 기본 중앙 위치로 이동했습니다.";
    public String safeReturnPositionMissing = "원래 위치 주변에서 안전한 복귀 지점을 찾지 못했습니다.";
    public String safeReturnFallback = "원래 위치 주변이 막혀 있어 오버월드 스폰으로 이동합니다.";
    public String safeSpawnPositionMissing = "오버월드 스폰 주변에서도 안전한 위치를 찾지 못했습니다.";
    public String unauthorizedRoomEntry = "이 마이룸에 머무를 권한이 없어 입장 전 위치로 돌아갑니다.";
    public String roomPreparationQueued = "마이룸을 준비하고 있습니다. 완료되면 자동으로 입장합니다.";
    public String roomPreparationJoined = "이미 마이룸을 준비하고 있습니다. 완료되면 자동으로 입장합니다.";
    public String roomPreparationQueueFull = "현재 준비 중인 마이룸이 많습니다. 잠시 후 다시 시도해 주세요.";
    public String roomPreparationComplete = "마이룸 준비가 완료되었습니다.";
    public String roomPreparationFailed = "마이룸 준비 중 오류가 발생했습니다. 다시 시도해 주세요.";
    public String visitors = "현재 방문자({count}명): {players}";
    public String visitorNotificationsEnabled = "방문자 입장·퇴장 알림을 켰습니다.";
    public String visitorNotificationsDisabled = "방문자 입장·퇴장 알림을 껐습니다.";
    public String visitorNotificationStatus = "방문자 입장·퇴장 알림: {state}";
    public String visitorNotificationsGloballyDisabled = "서버 설정에서 방문자 알림 기능이 꺼져 있습니다.";
    public String visitorEntered = "{player}님이 마이룸에 들어왔습니다.";
    public String visitorExited = "{player}님이 마이룸에서 나갔습니다.";
    public String infoHeader = "===== 내 마이룸 정보 =====";
    public String infoPublic = "공개 여부: {state}";
    public String infoTrusted = "행동 권한 플레이어: {players}";
    public String infoBanned = "차단 플레이어: {players}";
    public String infoLayout = "방 위치: 슬롯 {index}, X {minX}~{maxX}, Z {minZ}~{maxZ}";
    public String infoActive = "상시 활성화: {state}";
    public String infoNotify = "방문자 알림: {state}";
    public String helpHeader = "===== 명령어 도움말 =====";
    public String statePublic = "공개";
    public String statePrivate = "비공개";
    public String stateEmpty = "없음";
    public String stateActive = "활성";
    public String stateInactive = "비활성";
    public String statePending = "설정됨(현재 적용 대기)";
    public String stateEnabled = "켜짐";
    public String stateDisabled = "꺼짐";
    public String listTruncated = "외 {count}명";

    public void normalize() {
        SimpleMyRoomMessages defaults = new SimpleMyRoomMessages();
        try {
            for (var field : SimpleMyRoomMessages.class.getFields()) {
                Object value = field.get(this);
                if (!(value instanceof String text) || text.isBlank()) {
                    field.set(this, field.get(defaults));
                }
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not normalize message configuration.", exception);
        }
    }

    public static String format(String template, Map<String, ?> values) {
        String result = template;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}
