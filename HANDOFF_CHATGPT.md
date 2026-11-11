# LabelPrintApp 이어서 수정하기 (인수인계 프롬프트)

아래 내용을 읽고 이 안드로이드 앱을 이어서 수정해 주세요. 답변과 앱 안의 문구는 한국어로 써 주세요.

## 1. 앱이 하는 일

꿀 제품 라벨에 **배치번호, Best Before, MFG Date**를 덧인쇄하는 안드로이드 앱입니다.

- 라벨 디자인 사진(구글 드라이브 공개 폴더에서 받음)을 불러와 인쇄할 빈 영역을 정합니다.
- 구글 시트에서 배치 목록(배치번호, MGO/UMF용 Best Before 등)을 읽어 고를 수 있습니다.
- "무지 라벨"은 사진 없이 문구 템플릿(`{BATCH}`, `{MFG}`, `{BB}`) 전체를 인쇄합니다.
- 글자는 안드로이드 Canvas로 **비트맵을 그려서** 프린터에 1bpp 그래픽(EZPL `Q` 명령)으로 보냅니다. 프린터 내장 글꼴은 쓰지 않습니다.

## 2. 프린터와 연결

- 프린터: **Godex EZ2350i**, 300 dpi, 열전사/감열, 최대 용지 폭 약 60 mm
- 연결: 와이파이 같은 망에서 **raw TCP 192.168.68.55:9100**
- 명령어: Godex **EZPL**
  - 작업 머리: `^Q길이,간격` `^W폭` `^H농도(0~19)` `^P매수` `^S속도(2~7)` `^AT`/`^AD` `^C1` `^R왼쪽여백` `^E정지위치` `^L`
  - 그래픽: `Qx,y,한줄바이트수,높이` + 비트맵 바이트 + `E`
  - 상태 조회: `~S,CHECK` → 두 자리 코드. **00 준비, 50 인쇄 중, 60 데이터 처리 중은 정상**입니다. 01/02 용지 없음·걸림, 03 리본 없음, 04 헤드 열림, 09 문법 오류, 20 일시정지 등.
  - 오류 해제: `~S,CANCEL`

## 3. 프로젝트 구조

위치: `C:\Users\GGPC\Documents\VectorCraft\LabelPrintApp` (Kotlin, Jetpack Compose, git 저장소 아님)

| 파일 | 내용 |
|---|---|
| `app/build.gradle.kts` | applicationId `com.labelprint.godex`, versionCode / versionName |
| `app/src/main/java/com/labelprint/godex/Models.kt` | `NRect`, `PrintOptions`(글자 크기 `fontPt`, 글꼴, 굵게, 줄 배치 등), `LabelTemplate`(폭/길이 mm, 회전, 인쇄 영역, 여백, anchor), `PrinterSettings`(IP, 농도, 속도 등) |
| `Ezpl.kt` | 인쇄 문구 만들기, 영역 좌표 변환(`areaInLabel`, `mmBox`, `withMmBox`, `placement`), 1bpp 변환(`pack`), EZPL 작업 만들기(`job`), 상태 코드 해석(`statusText`, `isOk`, `isBusy`) |
| `Imaging.kt` | 사진 불러오기, 빈 영역 자동 찾기, **글자 그리기 `renderText`**, 지정 크기에 맞춰 영역 넓히기 `fitArea`, 인쇄 데이터 `buildJob`, 미리보기 `preview` |
| `MainActivity.kt` | 화면들(라벨 목록, 인쇄 화면, 설정). 인쇄 버튼 처리와 인쇄 후 상태 확인은 600줄 근처 |
| `EditScreen.kt` | 라벨 설정 화면(종이 가로/세로 mm, 라벨 간격, "프린터에서 먼저 나오는 쪽" 회전, 인쇄 영역) |
| `Net.kt` | 구글 드라이브/시트 읽기, `PrinterClient`(9100 포트 송신, 상태 조회) |
| `SheetData.kt` | 시트 CSV 파싱, 날짜 형식 변환 |
| `Store.kt` | 라벨·설정 JSON 저장/불러오기 |
| `app/src/test/.../EzplTest.kt`, `SheetDataTest.kt` | 단위 테스트 |

좌표 개념: "디자인 방향"(라벨을 글자가 바로 보이게 놓은 방향)과 "프린터 방향"(폭 = 헤드 방향, 길이 = 용지 진행 방향)이 따로 있습니다. `rotation`이 90/270이면 디자인 가로 = 프린터 길이입니다(`Ezpl.designSize`).

## 4. 빌드 방법 (Windows)

시스템에 JAVA_HOME이 없으므로 직접 지정해야 합니다.

```bash
cd /c/Users/GGPC/Documents/VectorCraft/LabelPrintApp
export JAVA_HOME=/c/Users/GGPC/android-dev/jdk/jdk-17.0.21+9
./gradlew.bat assembleDebug testDebugUnitTest
```

- Android SDK: `C:/Users/GGPC/android-dev/sdk` (`local.properties`에 설정됨), Gradle 8.7
- 결과물: `app/build/outputs/apk/debug/app-debug.apk`

## 5. APK 배포 규칙

- 수정해서 빌드할 때마다 `app/build.gradle.kts`의 **versionCode를 1 올리고 versionName도 올립니다**.
- APK 이름을 `LabelPrintApp-v버전.apk`로 바꿔서 구글 드라이브 폴더
  https://drive.google.com/drive/folders/16E_GTyJwjxE41ZNT6naiXq0V116jb3pp 에 올립니다.
- 현재 버전: **1.1 (versionCode 2)**. 파일 `LabelPrintApp-v1.1.apk`가 프로젝트 폴더에 있습니다. 드라이브 업로드는 아직 안 됐습니다(자동 업로드가 권한 문제로 막혀서 직접 올려야 함).

## 6. 지금까지 된 것

- 인쇄 자체는 잘 됩니다(배치번호, Best Before, MFG Date).
- v1.1에서 고친 것:
  1. **글자 크기 설정이 안 먹는 문제.** 원인: `fontPt`는 `renderText`까지 제대로 전달되지만, 지정 크기가 인쇄 영역보다 크면 들어갈 때까지 줄였고, 그 결과가 자동(0) 모드의 최대 크기와 같아서 바뀌지 않는 것처럼 보였음. 수정: `Imaging.fitArea`가 지정 크기에 필요한 크기만큼 인쇄 영역을 라벨 여백 안에서 넓힘(anchor 쪽 가장자리는 고정). `buildJob`, `preview`, 인쇄 전 확인 모두 이 넓힌 영역을 씀. 넓혔으면 미리보기 아래에 안내 문구, 여백 안에도 안 들어가면 줄였다는 경고가 나옴.
  2. **"1장을 보냈지만 프린터가 오류 상태입니다: 상태 코드 50".** 원인: 50은 "인쇄 중"인데, 보낸 뒤 1.5초 만에 상태를 물어보고 00이 아니면 오류로 처리했음. 수정: 50/60을 정상으로 보고(`Ezpl.isOk`, `isBusy`), (라벨 길이+간격)/속도 × 매수만큼 기다린 뒤 확인, 아직 50/60이면 2초 간격으로 최대 15번 더 확인.
  3. 라벨 설정 화면에서 프린터 기준 폭이 60 mm를 넘으면 빨간 경고를 띄움.

## 7. 남은 문제와 지금까지 파악한 것

1. **인쇄가 되다가 도중에 멈춤.** 사용자 화면에서 용지 설정이 47×143 mm와 300×47 mm 사이에서 바뀌어 있었음. 가장 유력한 원인은 `^Q`(길이)/`^W`(폭)로 보내는 값이 실제 라벨과 달라서 프린터가 간격 센서로 라벨을 찾다가 멈추는 것. 확인할 것:
   - 실제 라벨 크기와 롤 방향(어느 쪽이 먼저 나오는지)을 사용자에게 확인하고, `EditScreen.kt`의 가로/세로/회전 값이 맞게 저장되는지 확인.
   - `EditScreen.kt`의 `NumField`는 `remember { mutableStateOf(value) }`로 처음 값만 기억함. 숫자를 입력하는 도중의 값(예: "1", "14")마다 즉시 템플릿이 바뀌고 `withMmBox`로 영역이 잘림. 회전을 바꿔도 칸의 글자는 새로 고쳐지지 않음. 300 같은 엉뚱한 값이 여기서 생겼을 수 있음.
   - 상태 확인을 위한 두 번째 TCP 연결이 인쇄 중에 끼어드는 것이 원인일 가능성도 낮게나마 있음(v1.1에서는 인쇄가 끝날 시간만큼 기다린 뒤 물어봄).
   - `^E`(정지 위치), `^AT`/`^AD` 설정이 라벨 종류와 맞는지.
2. **글자 크기**: v1.1 수정이 실제 인쇄에서 원하는 대로 나오는지 사용자 확인이 필요함. 영역이 넓어지면 라벨 디자인과 겹칠 수 있음.

## 8. 다음 할 일

1. 사용자가 v1.1로 인쇄해 보고 결과(특히 멈춤 여부, 라벨 설정 화면 캡처)를 받는다.
2. 멈춤이 계속되면 위 7-1 항목을 차례로 확인하고, 필요하면 `NumField`를 입력이 끝났을 때(포커스 해제 또는 확인 버튼) 값을 반영하도록 고친다.
3. 고칠 때마다 단위 테스트를 돌리고, 버전을 올려 빌드하고, 5번 규칙대로 드라이브에 올린다.
