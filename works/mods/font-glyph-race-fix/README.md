# Font Glyph Race Fix

Minecraft 1.21.1 클라이언트에서 TrueType 글꼴의 글리프 측정과 비트맵 업로드가 동시에 실행될 때 발생할 수 있는 FreeType 상태 충돌을 막습니다.

## 수정 대상

- `TrueTypeGlyphProvider#getGlyph(int)`의 글리프 측정
- 익명 글리프 구현의 `upload(int, int)` 비트맵 업로드
- 두 작업을 하나의 공유 잠금으로 직렬화해 `Glyph bitmap ... does not match image ...` 충돌을 방지

글꼴 리소스팩과 글꼴 파일은 변경하지 않습니다. 사용자 설정이 필요한 기능이 아니므로 설정 화면이나 명령어는 제공하지 않습니다.

## 빌드와 확인

```powershell
.\gradlew.bat --offline --no-daemon --configure-on-demand :font-glyph-race-fix:build
```

산출물은 `font-glyph-race-fix/build/libs/font-glyph-race-fix-1.0.1.jar`입니다.
