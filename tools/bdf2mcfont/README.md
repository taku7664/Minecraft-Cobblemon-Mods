# bdf2mcfont

BDF 비트맵 폰트를 마인크래프트 자바 에디션 리소스팩으로 굽는 변환기입니다.
픽셀 폰트를 원본 픽셀 그대로, 안티에일리어싱 없이 넣는 것이 목적입니다.

## 왜 TTF를 안 쓰나

마크의 `ttf` provider는 stb_truetype으로 폰트를 래스터라이즈하면서 자체 안티에일리어싱을 넣습니다.
본문용 폰트라면 상관없지만 픽셀 폰트는 가장자리가 뭉개지고, baseline이 반픽셀에 걸리면 `shift`를 손으로 맞춰야 합니다.

BDF는 애초에 픽셀이 확정된 비트맵 포맷이라, PNG 아틀라스 + `bitmap` provider로 구우면 `scale=1`에서 원본이 1:1로 보존됩니다.
갈무리처럼 TTF와 BDF를 같이 배포하는 폰트라면 BDF 쪽을 쓰는 게 항상 낫습니다.

## 필요한 것

- Python 3.9+
- Pillow (`pip install pillow`)

## 사용법

### 1. 굽기

```bash
python tools/bdf2mcfont/bdf2mcfont.py Galmuri9.bdf out/galmuri9-pack --font-name galmuri9 --prefix g9 --pack-format 34
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `--namespace` | `galmuri` | 에셋 네임스페이스 (`assets/<namespace>/...`) |
| `--font-name` | `galmuri9` | 폰트 정의 파일명. `default.json`이 이걸 참조합니다 |
| `--prefix` | `g9` | 아틀라스 PNG 파일명 접두사 |
| `--pack-format` | `34` | 1.21~1.21.1은 34. 마크 버전 올라가면 여기만 바꾸세요 |
| `--description` | | 리소스팩 목록에 뜰 설명 |
| `--cols` / `--rows` | `16` | 아틀라스 한 장의 글리프 격자 크기 |

출력 구조:

```
pack.mcmeta
assets/minecraft/font/default.json      바닐라 폰트 교체 지점
assets/<ns>/font/<font-name>.json       provider 목록
assets/<ns>/textures/font/<prefix>_NN.png
```

### 2. 확인하기

게임을 켜기 전에 마크와 같은 방식으로 렌더링해서 눈으로 봅니다.

```bash
python tools/bdf2mcfont/preview.py out/galmuri9-pack preview.png --font-name galmuri9 --jar <client.jar>
```

`--jar`에 마크 클라이언트 jar를 주면 **바닐라 폰트를 같은 baseline에 한 줄 같이 그려줍니다.**
줄 간격 문제를 잡는 용도라, 마크의 9px 줄 그리드를 그대로 씁니다. 겹치면 게임에서도 겹칩니다.

`--icon`을 주면 미리보기 대신 128x128 `pack.png`를 만듭니다.

```bash
python tools/bdf2mcfont/preview.py out/galmuri9-pack out/galmuri9-pack/pack.png --font-name galmuri9 --icon "갈무리" "  9"
```

### 3. 압축

폴더 **안쪽**을 압축해야 합니다. `pack.mcmeta`가 zip 최상위에 있어야 마크가 인식합니다.

```bash
cd out/galmuri9-pack && zip -r ../galmuri9.zip .
```

## 폰트 크기 고르기

**마크의 줄 높이는 폰트와 무관하게 9px 고정입니다.** 잉크가 이보다 높으면 채팅·책·표지판처럼 줄이 붙는 곳에서 윗줄과 아랫줄이 충돌합니다. 단일 줄 GUI 라벨은 괜찮습니다.

갈무리 기준 실측:

| 폰트 | 잉크 높이 | 9px 줄에서 |
|---|---|---|
| Galmuri7 | 8px (cap 7) | 깨끗함. 바닐라와 metric 사실상 동일 |
| Galmuri9 | 10px (cap 9) | 줄이 맞닿고 괄호·descender가 아랫줄 침범 |
| Galmuri11 | 12px (cap 11) | 심하게 겹침. 단일 줄 전용 |

굽기 전에 `preview.py`로 확인하는 걸 권합니다.

## 동작 원리

### advance는 JSON이 아니라 픽셀에서 나온다

마크는 글자 폭을 JSON에서 읽지 않습니다. 셀 왼쪽 끝(= 펜 원점)에서 **가장 오른쪽 불투명 픽셀까지** 재서 유도합니다.

```
advance = round(actual_width * scale) + 1
```

`scale = height / 셀높이`이고 이 변환기는 항상 1로 둡니다. 그래서 실질적으로 `advance = 잉크_오른쪽_끝 + 1`입니다.

갈무리는 `DWIDTH`가 이 값과 대부분 정확히 일치합니다. 잉크가 짧게 끝나는 글자는 `DWIDTH-2` 열에 **alpha=1짜리 마커 픽셀**을 심어 폭을 고정합니다. 알파 1/255라 눈에 보이지 않지만 마크의 불투명 판정(`alpha != 0`)에는 걸립니다.

반대로 잉크가 `DWIDTH`를 넘어가는 글자는 마크 규칙상 더 좁힐 방법이 없어 1~2px 넓게 렌더됩니다. 갈무리에서는 희귀 기호·라틴확장 150자 정도이고 한글·ASCII·한자에는 없습니다.

### ascent는 상쇄된다

마크는 글리프를 `3 - ascent` 위치에 놓습니다. 잉크는 셀 안에서 `ascent - 잉크윗면` 행에 있으므로, 실제 렌더 위치는 `3 - 잉크윗면`이 되어 **`ascent` 값이 상쇄됩니다.**

덕분에 셀을 아무리 크게 잡아도 baseline은 바닐라와 정확히 일치합니다. 변환기가 폰트 전체의 잉크 최대 범위로 셀을 잡고 `ascent = 잉크 최상단`, `height = 셀 높이`로 두는 게 이 때문입니다.

### provider 우선순위는 먼저 나온 쪽

`providers` 배열은 **앞에 있는 것이 이깁니다.** 생성되는 `default.json`은 바닐라 구조를 따라가면서 커스텀 폰트를 두 번째에 끼웁니다.

```json
{
  "providers": [
    { "type": "reference", "id": "minecraft:include/space" },
    { "type": "reference", "id": "<namespace>:<font-name>" },
    { "type": "reference", "id": "minecraft:include/default", "filter": { "uniform": false } },
    { "type": "reference", "id": "minecraft:include/unifont" }
  ]
}
```

커스텀 폰트에 없는 글자는 바닐라 → unifont 순으로 폴백됩니다. 바닐라를 갈아엎으면서도 누락이 안 생깁니다.
`filter: {"uniform": false}`는 바닐라의 "Force Unicode Font" 옵션 동작이라 그대로 보존합니다.

### PUA는 건드리지 않는다

**사용자 정의 영역(U+E000–U+F8FF)은 의도적으로 제외합니다.** Cobblemon, JourneyMap 같은 모드가 이 영역에 아이콘 글리프를 넣기 때문에, 폰트가 여길 덮으면 모드 아이콘이 글자로 바뀝니다. 제외해두면 자연스럽게 모드 폰트로 폴백됩니다.

제어 문자(U+0000–U+001F, U+007F–U+009F)도 제외합니다.

## 검증하기

생성된 PNG에서 마크 로직을 역산해 원본 BDF의 `DWIDTH`와 대조하면 폭이 맞는지 확인할 수 있습니다. 갈무리 3종 실측 결과는 한글 11,172자 전부와 ASCII 95자 전부에서 불일치 0이었습니다.

변환기가 출력하는 `clipped pixels`가 0이 아니면 셀 밖으로 잘려나간 픽셀이 있다는 뜻이니 확인이 필요합니다.

## 라이선스

변환기 자체는 이 저장소 라이선스를 따릅니다.
**변환한 폰트는 원본 폰트의 라이선스를 따릅니다.** 갈무리는 SIL Open Font License 1.1이라 리소스팩에 임베드·재배포할 수 있지만, 폰트 파일 자체를 유료로 판매하는 것은 금지입니다. 배포할 팩에는 원본 라이선스 파일과 출처를 같이 넣으세요.

- 갈무리: https://github.com/quiple/galmuri (Lee Minseo, OFL 1.1)
