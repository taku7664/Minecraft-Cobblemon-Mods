# 안내 간격 60초 적용 기록

2026-10-03. 안내 기본 간격을 30초에서 60초로 변경했다.

- 소스 커밋: `dd98933524eb5f99eed4db45598b75f97a721ef5`.
- 코드 기본값과 키가 없는 JSON의 기본값 모두 60초다. 기존 안내 목록은 그대로다.
- Windows / Java 21에서 `:jbro-policy:unitTest :jbro-policy:build` 성공. 테스트 73개 통과.
- 대상 개발 서버: `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\dev-server`.
- 해당 서버의 `config/jbro-policy.json`에서 간격만 60초로 변경했다. 안내 20개와 다른 설정은 보존했다.
- `mods/jbro-policy-0.1.0.jar`를 빌드 결과로 교체했다. 이전 JAR과 비교한 바이트코드 변경은
  `PolicyConfig.class` 한 개이며, JSON·mcmeta 리소스의 바이트 차이는 줄바꿈 형식 차이였다.
- 위키의 `config/more-cobblemon-contents/wiki/pages/systems.html` 안내 문구를 기본 1분으로 맞췄다.
- 백업: 개발 서버의 `deployment-backups/20261003-tip-60-dd989335/`.

| 파일 | 적용 후 SHA-256 |
|---|---|
| 빌드 및 개발 서버 `jbro-policy-0.1.0.jar` | `D2E04A1B861A96F1EECA500F3E3E067C77073C10CF8828E103D77CB0441760C3` |
| 개발 서버 `config/jbro-policy.json` | `A16CBAFD05CBF9C7A3570F680C3512D0B2C6A4076B42414F3833EFC6B05BE2B2` |
| 개발 서버 위키 `pages/systems.html` | `DFBCD6C25883F1DF4467431A7D6D98A53A5228042B895ACBCAB517B91A2FA17C` |

운영 서버 `C:\Users\박주형\Documents\GitHub\Mincraft-Cobblemon-Server`의 실행 훅에도
`/tipIntervalSeconds = 60`을 ONCE로 등록했다. 해당 서버에는 아직 Jbro Policy가 없어
설치 후 첫 실행 전에 설정을 만들거나 기존 간격을 초기화한다. 토큰 등 비밀값은 복사하지 않았다.

운영 훅 검사를 개발 서버 설정에 읽기 전용으로 실행했을 때 CLC 제한 3개, 디스코드 봇 설정,
`agy --version`과 `agy models`가 통과했다. 위키 공개 주소는 비어 있어 경고가 나왔다.
이는 설정·CLI 명령 검사 결과이며 봇 인증, 실제 디스코드 권한, CLI 생성 요청의 인증을
확인한 결과는 아니다. 개발 서버에는 Xaero 모드 두 개가 없어 관련 검사는 건너뛰었다.

개발 서버와 운영 서버는 시작하거나 재시작하지 않았다. 다음 실행의 실제 안내 송신 간격은
게임에서 별도로 확인해야 한다.
